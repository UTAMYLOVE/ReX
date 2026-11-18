package io.github.xeno_hf.rex

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import rx.defs.BuiltinDefs
import rx.defs.ChannelRegistry
import rx.defs.Loga
import rx.session.DecodedLayout
import io.github.xeno_hf.rex.ui.DerivedChannels

import rx.protocol.Frame

import rx.session.LogReplayTransport
import rx.session.LogaValueSource
import rx.session.Presets
import rx.session.Recorder
import rx.protocol.DashFn
import rx.session.Session
import rx.session.SessionListener
import rx.session.SessionState
import rx.session.UplinkLink
import rx.session.WallClock
import io.github.xeno_hf.rex.Fs.nameWithoutExtension
import io.github.xeno_hf.rex.res.R
import io.github.xeno_hf.rex.ui.Strings
import io.github.xeno_hf.rex.ui.ThemeChoice
import io.github.xeno_hf.rex.ui.Ch
import io.github.xeno_hf.rex.ui.MonField
import io.github.xeno_hf.rex.ui.MonStyle
import io.github.xeno_hf.rex.ui.RideStats
import io.github.xeno_hf.rex.ui.findingString
import io.github.xeno_hf.rex.ui.metricString
import rx.session.EngineHealth
import rx.session.HealthStore
import rx.session.MapWatch
import rx.session.RevFlash

/** 一輪即時資料。 */
class Live(val layout: DecodedLayout, val raw: LongArray, val tNanos: Long, val seq: Long) {
    fun v(absid: Int): Double? = layout.physical(absid, raw)
    /** 依通道名稱取值（各平台的 absid 不一樣的通道用這個）。 */
    fun byName(name: String): Double? = layout.registry.byName[name]?.absid?.let { v(it) }
}

/** 環形緩衝：示波器用（最近 [capacity] 輪）。 */
class History(val capacity: Int = 1200) {
    private val lock = SynchronizedObject()
    private val t = LongArray(capacity)
    private val rows = arrayOfNulls<LongArray>(capacity)
    private var head = 0
    var size = 0; private set

    fun add(tn: Long, raw: LongArray) = synchronized(lock) {
        t[head] = tn; rows[head] = raw; head = (head + 1) % capacity
        if (size < capacity) size++
    }

    fun clear() = synchronized(lock) { size = 0; head = 0 }

    /** 由舊到新回傳 (t, raw)。 */
    fun snapshot(): List<Pair<Long, LongArray>> = tail(size)

    /** 最近 [n] 輪，由舊到新。 */
    fun tail(n: Int): List<Pair<Long, LongArray>> = synchronized(lock) {
        val m = minOf(n, size)
        (0 until m).map { k -> val i = (head - m + k + capacity) % capacity; t[i] to rows[i]!! }
    }
}

sealed interface Target {
    data class Ecu(val host: String) : Target
    /** 體驗模式：離線重播使用者提供的騎乘紀錄。 */
    data class Demo(val log: Path? = null) : Target
}

/** 檔案位置（ControlFile 快取）。 */
private val Platform.controlDir get() = Path(filesDir, "controlfile")
private val Platform.xmlCache get() = Path(controlDir, "ControlFile_S3.xml")
/** 解析後的精簡版（[ChannelRegistry.toCompact]），啟動時優先讀。 */
private val Platform.compactCache get() = Path(controlDir, "channels.rxdef")

/** 連線綁網路（Android 專用；iOS 不顯示）。 */
enum class BindMode { AUTO, WIFI, NONE }

/** 全 App 單例狀態（Android：Application 持有；iOS：App 啟動時建立）。 */
/** aRacer ECU 出廠預設密碼（XTune 沒存過密碼時用的值）。 */
const val DEFAULT_ECU_PASSWORD = 888888

class Repo(val platform: Platform) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = platform.kv
    // 夜櫻：舊設定一次性調成固定值（要在下面各設定讀 prefs 之前跑）
    init { Yozakura.migrate(prefs); Yozakura.migrate2(prefs); Yozakura.migrate3(prefs) }
    val secrets get() = platform.secrets

    val registry = MutableStateFlow<ChannelRegistry?>(null)
    /** 用內建定義時＝平台代號（S3、X2…）；用使用者匯入的 XML 時為 null。 */
    val builtinKey = MutableStateFlow<String?>(null)
    val registryError = MutableStateFlow<String?>(null)
    /** 啟動時正在讀快取的定義檔（7 MB XML 要幾秒）；讀完前不能顯示匯入頁，否則像是「重裝後要重匯」。 */
    val registryLoading = MutableStateFlow(false)
    val state = MutableStateFlow<SessionState>(SessionState.Idle)
    val live = MutableStateFlow<Live?>(null)
    val target = MutableStateFlow<Target?>(null)
    val recording = MutableStateFlow<Recorder?>(null)
    /** 正在錄的那筆到目前為止的標記（收檔後從 events.jsonl 讀）。 */
    val liveMarks = MutableStateFlow<List<Recorder.Mark>>(emptyList())
    val events = MutableStateFlow<List<String>>(emptyList())
    /** 本次連線出現過的故障碼：名稱 → 首次出現時間。 */
    val malfSeen = MutableStateFlow<Map<String, Long>>(emptyMap())
    val history = History()
    /** 最近幀（診斷頁），只存物件，顯示時才格式化。 */
    private val recentFrames = ArrayDeque<Triple<Long, Int, Frame>>()
    private val framesLock = SynchronizedObject()
    val tick = MutableStateFlow(0L) // 診斷頁刷新用

    /** 分享用副本的位置；[Platform.cacheDir] 底下，Android 的 FileProvider 只開放這裡。 */
    val shareDir: Path get() = Path(platform.cacheDir, "share")

    init {
        // 上次分享留下的副本（含舊版放在 filesDir/share 的）在啟動時清掉；分享當下不清，接收端可能還在讀
        scope.launch { runCatching { Fs.delete(shareDir); Fs.delete(Path(platform.filesDir, "share")) } }
    }

    var session: Session? = null; private set
    private var stateJob: Job? = null
    private var idleJob: Job? = null
    private var seq = 0L

    /** 預留的體驗紀錄來源；本版不附範例紀錄。 */
    val hasBuiltinDemo: Boolean by lazy { runCatching { platform.builtinDemoLoga() != null }.getOrDefault(false) }

    /** 使用者匯入的騎乘紀錄（從 XTune 分享或選檔），體驗模式用。 */
    val demoLogsDir: Path get() = Fs.mkdirs(Path(platform.filesDir, "demo_logs"))
    val demoLogs = MutableStateFlow(listDemoLogs())
    private fun listDemoLogs(): List<Path> = Fs.list(demoLogsDir).filter { it.name.endsWith(".loga") }.sortedByDescending { it.name }

    /** ReX 自己錄的紀錄（sessions/<資料夾>/decoded.loga），體驗模式可以直接重播。 */
    fun ownRecordings(): List<Path> = Fs.list(sessionsRoot).mapNotNull { d -> Path(d, "decoded.loga").takeIf { Fs.size(it) > 0 && !d.name.startsWith("DEMO") } }
        .sortedByDescending { it.parent!!.name }

    /** 匯入一份 loga：可以是 XTune 分享的 zip（內含一個 .loga），也可以是 .loga 本身。 */
    fun importDemoLog(fileName: String?, bytes: ByteArray, done: (String) -> Unit) = scope.launch(Dispatchers.Default) {
        val msg = runCatching {
            val (name, data) = if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                platform.unzipFirst(bytes) { it.endsWith(".loga", true) }?.let { (n, d) -> n.substringAfterLast('/') to d }
                    ?: error(Strings.get(R.string.err_zip_no_loga))
            } else {
                (fileName ?: "ride_${WallClock.nowMs()}.loga") to bytes
            }
            require(data.decodeToString(0, minOf(data.size, 64)).contains("aRacer")) { Strings.get(R.string.err_not_loga) }
            Loga.parse(data) // 解析得了才收
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".loga", true)) it else "$it.loga" }
            Fs.writeBytes(Path(demoLogsDir, safe), data)
            demoLogs.value = listDemoLogs()
            Strings.get(R.string.demo_added, safe)
        }.getOrElse { Strings.get(R.string.demo_import_failed, it.message ?: "") }
        log(msg)
        done(msg)
    }

    fun deleteDemoLog(f: Path) { Fs.delete(f); demoLogs.value = listDemoLogs() }

    val sessionsRoot: Path get() = Fs.mkdirs(platform.sessionsRoot)

    // ---------------- 設定 ----------------
    var host: String
        get() = prefs.getString("host", "192.168.88.88")!!
        set(v) = prefs.putString("host", v)
    var presetId: String
        get() = prefs.getString("preset", Presets.ALL_CHANNELS.id)!!
        set(v) = prefs.putString("preset", v)
    /** ECU 熱點。有填就用 local-only 副連線加入，主 WiFi 保留上網＋同步。 */
    var ecuSsid: String
        get() = prefs.getString("ecuSsid", "")!!
        set(v) = prefs.putString("ecuSsid", v.trim())
    var ecuWifiPw: String
        get() = prefs.getString("ecuWifiPw", "")!!
        set(v) = prefs.putString("ecuWifiPw", v)
    var bind: BindMode
        get() = runCatching { BindMode.valueOf(prefs.getString("bind", "AUTO")!!) }.getOrDefault(BindMode.AUTO)
        set(v) = prefs.putString("bind", v.name)
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keepScreenOn", true)
        set(v) = prefs.putBoolean("keepScreenOn", v)
    /** 從最近使用的 App 清單滑掉 ReX 後，是否繼續在背景連線。預設不繼續（關掉就是關掉）。 */
    var runAfterClose: Boolean
        get() = prefs.getBoolean("runAfterClose", false)
        set(v) = prefs.putBoolean("runAfterClose", v)
    /** 收不到資料多久自動斷線（分鐘，0＝不自動斷）。 */
    var idleStopMinutes: Int
        get() = prefs.getInt("idleStopMin", 0)
        set(v) = prefs.putInt("idleStopMin", v)

    // ---------------- 儀表選項（同 XTune 設定頁） ----------------
    /** 進階儀表轉速上限：10、12、16（×1000 rpm，XTune GaugeRpmMax 1／2／3）。 */
    val rpmMax = MutableStateFlow(prefs.getInt("rpmMax", 10).takeIf { it in RPM_MAXES } ?: 10)
    fun setRpmMax(v: Int) { rpmMax.value = v; prefs.putInt("rpmMax", v) }
    /** 進階儀表顯示檔位（XTune ShowGearNum，預設開）。 */
    val showGear = MutableStateFlow(prefs.getBoolean("showGear", true))
    fun setShowGear(v: Boolean) { showGear.value = v; prefs.putBoolean("showGear", v) }
    /** 連線就自動錄製（預設關）。 */
    val autoRecord = MutableStateFlow(prefs.getBoolean("autoRecord", AUTO_RECORD_DEFAULT))
    fun setAutoRecord(v: Boolean) { autoRecord.value = v; prefs.putBoolean("autoRecord", v) }
    /** 手機 GPS（XTune「Phone GPS」）：開了才定位，錄製時 loga 多四欄 Phone_GPS_*。 */
    val gps get() = platform.gps
    val gpsEnabled = MutableStateFlow(prefs.getBoolean("gps", true))
    fun setGpsEnabled(v: Boolean) {
        gpsEnabled.value = v; prefs.putBoolean("gps", v)
        if (session != null) platform.onSessionActive(true)
        if (v && session != null) gps.start() else if (!v) gps.stop()
    }
    /** 儀表時速改用手機 GPS（XTune 點時速切換；ECU 車速沒接或不準時用）。 */
    val speedFromGps = MutableStateFlow(prefs.getBoolean("speedFromGps", true))
    fun setSpeedFromGps(v: Boolean) { speedFromGps.value = v; prefs.putBoolean("speedFromGps", v) }
    /** 同步到電腦：連線時把資料即時轉送到 [uplinkAddr]（host:port）。 */
    var uplinkEnabled: Boolean
        get() = prefs.getBoolean("uplink", false)
        set(v) = prefs.putBoolean("uplink", v)
    var uplinkAddr: String
        get() = prefs.getString("uplinkAddr", "")!!
        set(v) = prefs.putString("uplinkAddr", v.trim())
    var scopeChannels: List<Int>
        get() = prefs.getString("scope", "26,34,29,1433")!!.split(',').mapNotNull { it.toIntOrNull() }
        set(v) = prefs.putString("scope", v.joinToString(","))

    // ---------------- 外觀／儀表 ----------------
    val theme = MutableStateFlow(
        ThemeChoice.parse(prefs.getString("theme", null))
    )
    fun setTheme(t: ThemeChoice) { theme.value = t; prefs.putString("theme", t.name) }

    // ---------------- 監控畫面／懸浮小儀表 ----------------
    val monStyle = MutableStateFlow(MonStyle.parse(prefs.getString("monStyle", null)))
    fun setMonStyle(v: MonStyle) { monStyle.value = v; prefs.putString("monStyle", v.name) }
    /** 監控四格（三種錶盤風格共用一組）。 */
    val monTiles = MutableStateFlow(MonField.parse(prefs.getString(Yozakura.TILES_KEY, null), MonField.DEFAULT_TILES))
    fun setMonTile(index: Int, f: MonField) {
        val l = monTiles.value.toMutableList().also { it[index] = f }
        monTiles.value = l; prefs.putString(Yozakura.TILES_KEY, l.joinToString(",") { it.id })
    }
    /** D 風格的六格。 */
    val monSix = MutableStateFlow(MonField.parse(prefs.getString("monSix", null), MonField.DEFAULT_SIX).let { if (it.size == 6) it else MonField.DEFAULT_SIX })
    val monEight = MutableStateFlow(MonField.parse(prefs.getString("monEight", null), MonField.DEFAULT_EIGHT).let { if (it.size == 8) it else MonField.DEFAULT_EIGHT })
    fun setMonEight(index: Int, f: MonField) {
        val l = monEight.value.toMutableList().also { it[index] = f }
        monEight.value = l; prefs.putString("monEight", l.joinToString(",") { it.id })
    }
    val monDial = MutableStateFlow(MonField.parse(prefs.getString("monDial", null), MonField.DEFAULT_DIAL).let { if (it.size == 3) it else MonField.DEFAULT_DIAL })
    fun setMonDial(index: Int, f: MonField) {
        val l = monDial.value.toMutableList().also { it[index] = f }
        monDial.value = l; prefs.putString("monDial", l.joinToString(",") { it.id })
    }
    fun setMonSix(index: Int, f: MonField) {
        val l = monSix.value.toMutableList().also { it[index] = f }
        monSix.value = l; prefs.putString("monSix", l.joinToString(",") { it.id })
    }
    /** 懸浮小儀表側翼的三列。 */
    val overlayRows = MutableStateFlow(MonField.parse(prefs.getString("ovRows", null), MonField.DEFAULT_ROWS))
    fun setOverlayRow(index: Int, f: MonField) {
        val l = overlayRows.value.toMutableList().also { it[index] = f }
        overlayRows.value = l; prefs.putString("ovRows", l.joinToString(",") { it.id })
    }
    val overlayOn = MutableStateFlow(prefs.getBoolean("overlay", true))
    fun setOverlayOn(v: Boolean) { overlayOn.value = v; prefs.putBoolean("overlay", v) }
    /** 轉速條：紅線、全油門行為、要不要閃。 */
    val redline = MutableStateFlow(prefs.getInt("redline", 10_000))
    fun setRedline(v: Int) { redline.value = v; prefs.putInt("redline", v) }
    val wotMode = MutableStateFlow(runCatching { RevFlash.Wot.valueOf(prefs.getString("wotMode", "BURST3")!!) }.getOrDefault(RevFlash.Wot.BURST3))
    fun setWotMode(v: RevFlash.Wot) { wotMode.value = v; prefs.putString("wotMode", v.name) }
    val flashOn = MutableStateFlow(prefs.getBoolean("flashOn", true))
    fun setFlashOn(v: Boolean) { flashOn.value = v; prefs.putBoolean("flashOn", v) }
    /** 爆閃轉速；0＝還沒設，跟著紅線（紅線前 200）。 */
    val flashRpm = MutableStateFlow(prefs.getInt("flashRpm", 0))
    fun setFlashRpm(v: Int) { val x = v.coerceIn(3000, 12_000); flashRpm.value = x; prefs.putInt("flashRpm", x) }
    /** 熱車模式：引擎還沒熱時，把紅區和爆閃轉速暫時壓低。 */
    val warmMode = MutableStateFlow(prefs.getBoolean("warmMode", true))
    fun setWarmMode(v: Boolean) { warmMode.value = v; prefs.putBoolean("warmMode", v) }
    /** 先慢閃：比爆閃轉速低幾轉就開始慢閃；0＝關。 */
    val flashPre = MutableStateFlow(prefs.getInt("flashPre", 500))
    fun setFlashPre(v: Int) { flashPre.value = v; prefs.putInt("flashPre", v) }
    fun flashSettings() = RevFlash.Settings(redline.value, wotMode.value, flashOn.value, flashRpm.value.takeIf { it > 0 }, flashPre.value)

    /** 開 App 就自動連上次的 ECU（預設開）。 */
    var autoConnect: Boolean
        get() = prefs.getBoolean("autoConnect", true)
        set(v) = prefs.putBoolean("autoConnect", v)
    /**
     * 使用者自己按了「中斷連線」或通知上的「結束」：之後 [USER_STOP_HOLD_MS] 內回到 App 不自動連（例如停車後回來看健檢報告），
     * 要連就手動按；過了這段時間（通常是下一次騎車）打開 App 又會自動連。存在設定裡，App 被系統關掉也一樣。
     */
    private fun userStoppedAt() = prefs.getString("userStoppedAt", null)?.toLongOrNull() ?: 0L
    val userStopped = MutableStateFlow(WallClock.nowMs() - userStoppedAt() < USER_STOP_HOLD_MS)
    fun stopByUser() {
        prefs.putString("userStoppedAt", WallClock.nowMs().toString()); userStopped.value = true
        disconnect()
    }
    /** App 畫面是否在前景（MainActivity onStart／onStop 設定）。 */
    @kotlin.concurrent.Volatile var appVisible = false
    private var autoConnectAtMs = -1L
    /** 回到 App（或 App 剛開好）時呼叫：沒連線、使用者也沒自己停掉，就自動連上次的 ECU。 */
    fun autoConnectIfIdle() {
        if (userStopped.value && WallClock.nowMs() - userStoppedAt() >= USER_STOP_HOLD_MS) userStopped.value = false
        if (!autoConnect || userStopped.value || target.value != null || registry.value == null || host.isBlank()) return
        val now = platform.elapsedNanos() / 1_000_000
        if (autoConnectAtMs >= 0 && now - autoConnectAtMs < 10_000) return // 剛送出的連線還在建立
        autoConnectAtMs = now
        connect(Target.Ecu(host.trim()))
    }

    /** 從通知或 App 內其他地方要求打開某個畫面（[OPEN_MAP]、[OPEN_HEALTH]）；畫面處理完清回 null。 */
    val openRequest = MutableStateFlow<String?>(null)

    /** 油圖視窗裡的「解鎖切換」：只在這次暫時解鎖，不存檔；關掉視窗或切完就用 [relock] 回到存檔的狀態。 */
    fun unlockOnce() { controlsLocked.value = false }
    fun relock() { controlsLocked.value = prefs.getBoolean("locked", true) }

    // ---------------- AI ----------------
    val aiProvider = MutableStateFlow(AiProvider.parse(prefs.getString("aiProvider", null)))
    fun setAiProvider(p: AiProvider) { aiProvider.value = p; prefs.putString("aiProvider", p.id) }
    fun aiModel(p: AiProvider): String = prefs.getString("aiModel.${p.id}", null)?.ifBlank { null } ?: p.defaultModel
    fun setAiModel(p: AiProvider, m: String) = prefs.putString("aiModel.${p.id}", m.trim())
    fun aiKey(p: AiProvider): String? = secrets.get("ai.${p.id}")?.ifBlank { null }
    fun setAiKey(p: AiProvider, k: String?) = secrets.put(k?.trim()?.ifEmpty { null }, "ai.${p.id}")
    /** 每趟騎完自動請 AI 解讀（預設關，因為每次都會花 API 費用）。 */
    val aiAuto = MutableStateFlow(prefs.getBoolean("aiAuto", false))
    fun setAiAuto(v: Boolean) { aiAuto.value = v; prefs.putBoolean("aiAuto", v) }
    /** 車輛資料（第一次開 App 會問；給 AI 和健檢參考）。空白＝沒填。 */
    var vehModel: String get() = prefs.getString("veh.model", "")!!; set(v) = prefs.putString("veh.model", v.trim())
    var vehCc: String get() = prefs.getString("veh.cc", "")!!; set(v) = prefs.putString("veh.cc", v.filter { it.isDigit() })
    /** "water"／"air"／""。 */
    var vehCooling: String get() = prefs.getString("veh.cooling", "")!!; set(v) = prefs.putString("veh.cooling", v)
    /** "single"／"dual"／""。 */
    var vehInjector: String get() = prefs.getString("veh.inj", "")!!; set(v) = prefs.putString("veh.inj", v)
    /** "yes"／"no"／""。 */
    var vehAf2: String get() = prefs.getString("veh.af2", "")!!; set(v) = prefs.putString("veh.af2", v)
    var vehMods: String get() = prefs.getString("veh.mods", "")!!; set(v) = prefs.putString("veh.mods", v.trim())
    /** "commute"／"fun"／"mixed"／""。 */
    var vehUse: String get() = prefs.getString("veh.use", "")!!; set(v) = prefs.putString("veh.use", v)
    /** 問過了（或選了「之後再說」）就不再主動跳出來。 */
    var vehAsked: Boolean get() = prefs.getBoolean("veh.asked", false); set(v) = prefs.putBoolean("veh.asked", v)
    /** 給 AI 的車輛描述：把上面各項整理成一段話，最後接自由說明。 */
    fun vehicleProfileText(): String = buildString {
        if (vehModel.isNotBlank()) appendLine("車款：$vehModel")
        if (vehCc.isNotBlank()) appendLine("排氣量：${vehCc} cc")
        when (vehCooling) { "water" -> appendLine("冷卻：水冷"); "air" -> appendLine("冷卻：氣冷（引擎溫度正常範圍比水冷高）") }
        when (vehInjector) { "single" -> appendLine("噴油：單噴油嘴"); "dual" -> appendLine("噴油：雙噴油嘴（總噴油量要把兩支的噴油時間一起看；第二支通常只在大油門、高轉才噴）") }
        when (vehAf2) { "yes" -> appendLine("有裝 AF2 寬頻空燃比感知器"); "no" -> appendLine("沒有裝 AF2 寬頻空燃比感知器（空燃比相關欄位是空的或 0，不是故障）") }
        if (vehMods.isNotBlank()) appendLine("改裝：$vehMods")
        when (vehUse) { "commute" -> appendLine("用途：通勤為主"); "fun" -> appendLine("用途：常拉高轉、跑山"); "mixed" -> appendLine("用途：通勤和休閒都有") }
        if (vehicleNote.isNotBlank()) appendLine("車主補充：$vehicleNote")
    }.trim()

    /** 車輛說明（給 AI 參考）：車款、氣冷／水冷、改裝。 */
    var vehicleNote: String
        get() = prefs.getString("vehicleNote", "")!!
        set(v) = prefs.putString("vehicleNote", v)

    var dashPage: Int
        get() = prefs.getInt("dashPage", 0)
        set(v) = prefs.putInt("dashPage", v)

    /** 監控儀表的預設通道格。 */
    val monitorTiles = MutableStateFlow(intList("monitor", DEFAULT_MONITOR))
    fun setMonitorTiles(v: List<Int>) { monitorTiles.value = v; prefs.putString("monitor", v.joinToString(",")) }

    /** 儀表上可自選的格：key → absid。 */
    val slots = MutableStateFlow(DEFAULT_SLOTS.mapValues { (k, d) -> prefs.getInt("slot.$k", d).let { if (it == 124) PhoneGps.SPEED_ID else it } })
    fun setSlot(key: String, absid: Int) { slots.value = slots.value + (key to absid); prefs.putInt("slot.$key", absid) }

    /** 進階直向（race，最多 4 條）／巡航（cruise，最多 6 格）顯示幾格；預設放滿。 */
    val slotCount = MutableStateFlow(SLOT_MAX.mapValues { (g, _) -> prefs.getInt("slots.$g", SLOT_MAX[g]!!).coerceIn(1, SLOT_MAX[g]!!) })
    fun addSlot(group: String) {
        val n = slotCount.value[group]!!
        if (n >= SLOT_MAX[group]!!) return
        slotCount.value = slotCount.value + (group to n + 1); prefs.putInt("slots.$group", n + 1)
    }
    /** 移除第 [index] 格（1 起算），後面的往前補。 */
    fun removeSlot(group: String, index: Int) {
        val n = slotCount.value[group]!!
        if (n <= 1) return
        for (i in index until n) setSlot("$group$i", slots.value["$group${i + 1}"]!!)
        slotCount.value = slotCount.value + (group to n - 1); prefs.putInt("slots.$group", n - 1)
    }

    /** 兩顆多功能鈕的功能 ID（XTune 偏好鍵 MFButton_A／_B，預設 241 AT、244 TC），進階與巡航共用。 */
    val funButtons = MutableStateFlow(listOf(prefs.getInt("mfb.A", 241), prefs.getInt("mfb.B", 244)))
    fun setFunButton(i: Int, id: Int) {
        funButtons.value = funButtons.value.toMutableList().also { it[i] = id }
        prefs.putInt("mfb.${"AB"[i]}", id)
    }

    // ---- 儀表運行期控制 ----
    // 就算有密碼，ReX 也只能改儀表上的功能（MAP／AT／Pit／LC／TC／EB）；
    // 沒登入時 MAP／Pit／LC 能切，其他顯示「功能限制」；AT 要再確認一次才開。
    private val NO_LOGIN_FUNS = setOf(DashFn.PIT, DashFn.LC)

    /** 控制結果。UI 依 [outcome] 挑在地化字串，[label] 是功能名（AT、MAP 2…）。 */
    data class ControlMsg(val label: String, val outcome: Outcome, val detail: String? = null, val serial: Long = 0) {
        enum class Outcome { ON, OFF, MAP_OK, NEEDS_LOGIN, NOT_LICENSED, REFUSED, ERROR, NOT_CONNECTED, DEMO, UNVERIFIED, LOCKED }
    }
    private var controlSerial = 0L
    /** 最近一次控制寫入結果，給 UI 顯示 toast（null＝沒有）。 */
    val lastControl = MutableStateFlow<ControlMsg?>(null)
    /** 控制寫入進行中旗標，避免連點重入。 */
    val controlBusy = MutableStateFlow(false)
    /** 這次連線有沒有登入成功（儀表鈕除 MAP 外都要）。 */
    val ecuLoggedIn = MutableStateFlow(false)
    private var loginJob: Job? = null
    /** 儀表鈕在 ECU 上的開關（[Session.dashSwitch]）；沒讀到的不在表裡，按鈕退回看即時通道。 */
    val dashSwitch = MutableStateFlow<Map<DashFn, Boolean>>(emptyMap())
    private var switchJob: Job? = null

    private fun emit(label: String, o: ControlMsg.Outcome, detail: String? = null) {
        lastControl.value = ControlMsg(label, o, detail, ++controlSerial)
        log("Control $label -> $o${detail?.let { " ($it)" } ?: ""}")
    }

    /** 控制鈕鎖定（預設鎖上）：鎖上時 MAP／AT／Pit／LC／TC／EB 都按不動，手機架在車上不會誤觸。 */
    val controlsLocked = MutableStateFlow(prefs.getBoolean("locked", true))
    fun setControlsLocked(v: Boolean) { controlsLocked.value = v; prefs.putBoolean("locked", v) }

    private fun runControl(label: String, needsLogin: Boolean, onOk: (Session) -> suspend () -> ControlMsg.Outcome, op: suspend (Session) -> Session.WriteResult) {
        if (controlsLocked.value) return emit(label, ControlMsg.Outcome.LOCKED)
        val s = session ?: return emit(label, ControlMsg.Outcome.NOT_CONNECTED)
        if (target.value is Target.Demo) return emit(label, ControlMsg.Outcome.DEMO)
        if (needsLogin && !ecuLoggedIn.value) return emit(label, ControlMsg.Outcome.NEEDS_LOGIN)
        if (controlBusy.value) return
        controlBusy.value = true
        scope.launch {
            try {
                when (val r = runCatching { op(s) }.getOrElse { Session.WriteResult.Error(it.message ?: "exception") }) {
                    Session.WriteResult.Ack -> emit(label, onOk(s)())
                    is Session.WriteResult.Aborted -> when (r.abort.code) {
                        0x06010001L, 0x06010003L -> emit(label, ControlMsg.Outcome.NEEDS_LOGIN)
                        0x06010004L -> emit(label, ControlMsg.Outcome.NOT_LICENSED)
                        else -> emit(label, ControlMsg.Outcome.REFUSED, r.abort.toString())
                    }
                    is Session.WriteResult.Error -> emit(label, ControlMsg.Outcome.ERROR, r.reason)
                    is Session.WriteResult.Unverified -> emit(label, ControlMsg.Outcome.UNVERIFIED, r.platform)
                }
            } finally { controlBusy.value = false }
        }
    }

    /** 切 MAP 檔位（1-3）。免登入。 */
    fun setMap(mapNo: Int) = runControl("MAP $mapNo", needsLogin = false, onOk = { { ControlMsg.Outcome.MAP_OK } }) {
        mapWatch.expect(mapNo, platform.elapsedNanos() / 1_000_000)
        it.setMap(mapNo)
    }

    /** 按儀表多功能鈕：[on]=null 依 ECU 現值反轉。結果讀回 ECU 再報「已開啟／已關閉」。 */
    fun pressFun(fn: DashFn, label: String, on: Boolean? = null) = runControl(label, needsLogin = fn !in NO_LOGIN_FUNS,
        onOk = { s -> { when (s.readDash(fn)) { true -> ControlMsg.Outcome.ON; false -> ControlMsg.Outcome.OFF; null -> ControlMsg.Outcome.ERROR } } },
    ) { it.setDash(fn, on) }

    private fun intList(key: String, def: List<Int>): List<Int> =
        prefs.getString(key, null)?.split(',')?.mapNotNull { it.toIntOrNull() } ?: def

    // ---------------- ControlFile ----------------
    fun loadRegistry() {
        val f = platform.xmlCache
        val c = platform.compactCache
        if (!Fs.exists(f) && !Fs.exists(c)) { useBuiltin(lastPlatformKey); return }
        registryLoading.value = true
        scope.launch(Dispatchers.Default) {
            try {
                runCatching {
                    runCatching { ChannelRegistry.fromCompact(Fs.readText(c)) }.getOrNull()
                        ?: ChannelRegistry.parse(Fs.readText(f)).also { saveCompact(it) }
                }
                    .onSuccess { registry.value = it; builtinKey.value = null; registryError.value = null }
                    .onFailure { useBuiltin(lastPlatformKey); registryError.value = Strings.get(R.string.err_registry) }
            } finally { registryLoading.value = false }
        }
    }

    /** 上次連到的 ECU 平台的定義代號；下次啟動直接用它的內建定義。 */
    private var lastPlatformKey: String
        get() = prefs.getString("builtinDefs", "S3")!!.takeIf(BuiltinDefs::has) ?: "S3"
        set(v) = prefs.putString("builtinDefs", v)

    /** 改用 ReX 內建的監控定義（不需要匯入任何檔案）。 */
    fun useBuiltin(key: String) {
        val k = if (BuiltinDefs.has(key)) key else "S3"
        registry.value = BuiltinDefs.forKey(k)
        builtinKey.value = k
        lastPlatformKey = k
    }

    /**
     * 連線判別平台後決定頻道表。用內建定義時依 ECU 平台換名錄；匯入的 XML 一律沿用。
     * 名錄換了就重建欄位、清歷史，錄製和同步也重開，避免舊欄位對到新資料。
     */
    private fun tableForPlatform(s: () -> Session?, t: Target, preset: Presets.Preset, p: rx.protocol.EcuPlatform?, current: rx.protocol.ChannelTable): rx.protocol.ChannelTable {
        val key = builtinKey.value ?: return current
        val want = p?.controlFile?.takeIf(BuiltinDefs::has) ?: key
        if (want == key) return current
        if (recording.value != null) stopRecording()
        useBuiltin(want)
        val reg = registry.value!!
        val table = tableFor(preset, reg)
        currentLayout = DecodedLayout(reg, table)
        history.clear(); malfSeen.value = emptyMap(); live.value = null
        log("Using built-in definitions for ${p?.label ?: want}: ${reg.channels.size} channels")
        s()?.let { session ->
            uplink.value?.let { u -> session.removeListener(u); uplink.value = null; scope.launch { u.close() }; startUplink(session, t, table) }
        }
        return table
    }

    private fun saveCompact(reg: ChannelRegistry) {
        runCatching { Fs.writeText(platform.compactCache, reg.toCompact()) }
    }

    /** [block] 回傳車輛資料 XML；解析成功就存快取。 */
    suspend fun importFrom(block: suspend () -> String) {
        runCatching {
            val xml = block()
            require(xml.contains("<Variables>")) { Strings.get(R.string.err_not_controlfile) }
            withContext(Dispatchers.Default) { ChannelRegistry.parse(xml).also { Fs.writeText(platform.xmlCache, xml); saveCompact(it) } }
        }
            .onSuccess { registry.value = it; builtinKey.value = null; registryError.value = null; log("Vehicle data imported: ${it.channels.size} channels") }
            .onFailure { registryError.value = it.message ?: it::class.simpleName }
    }

    /** 使用者選的檔案（ControlFile XML）。 */
    suspend fun importControlFile(bytes: ByteArray) = importFrom { platform.controlFileFromBytes(bytes) }

    /** 刪掉匯入的定義檔，改回內建定義。 */
    fun clearRegistry() {
        Fs.delete(platform.xmlCache); Fs.delete(platform.compactCache)
        registryError.value = null
        useBuiltin(lastPlatformKey)
    }

    // ---------------- 頻道組 ----------------
    /** 使用者自訂的頻道組（內建只有「全部」）。存成一行一組：id|名稱|absid,absid… */
    val customPresets = MutableStateFlow(loadPresets())

    private fun loadPresets(): List<Presets.Preset> =
        prefs.getString("customPresets", "")!!.lines().mapNotNull { l ->
            val p = l.split('|')
            if (p.size < 3) null else Presets.Preset(p[0], p[1], "", p[2].split(',').mapNotNull { it.toIntOrNull() })
        }

    private fun savePresets(v: List<Presets.Preset>) {
        customPresets.value = v
        prefs.putString("customPresets", v.joinToString("\n") { "${it.id}|${it.title.replace('|', ' ').replace('\n', ' ')}|${it.absids.joinToString(",")}" })
    }

    fun upsertPreset(p: Presets.Preset) {
        val l = customPresets.value
        savePresets(if (l.any { it.id == p.id }) l.map { if (it.id == p.id) p else it } else l + p)
    }

    fun deletePreset(id: String) {
        savePresets(customPresets.value.filter { it.id != id })
        if (presetId == id) presetId = Presets.ALL_CHANNELS.id
    }

    val allPresets: List<Presets.Preset> get() = listOf(Presets.ALL_CHANNELS) + customPresets.value
    fun currentPreset(): Presets.Preset = allPresets.firstOrNull { it.id == presetId } ?: Presets.ALL_CHANNELS

    // ---------------- 連線 ----------------
    private val listener = object : SessionListener {
        override fun onFrame(tNanos: Long, dir: Int, frame: Frame) {
            synchronized(framesLock) {
                recentFrames.addLast(Triple(tNanos, dir, frame))
                while (recentFrames.size > 60) recentFrames.removeFirst()
            }
        }

        override fun onRound(tNanos: Long, raw: LongArray) {
            val layout = currentLayout ?: return
            history.add(tNanos, raw)
            live.value = Live(layout, raw, tNanos, ++seq)
            onRide(tNanos, layout, raw)
            mapWatch.onSample(tNanos / 1_000_000, layout.physical(Ch.MAP_NUM, raw)?.toInt())?.let { onMapChange(it, layout, raw) }
            mapWatch.current?.let { if (mapNow.value != it) mapNow.value = it }
            val active = layout.activeMalfunctions(raw)
            if (active.isNotEmpty()) {
                val m = malfSeen.value
                val add = active.filter { it.name !in m }
                if (add.isNotEmpty()) malfSeen.value = m + add.associate { it.name to WallClock.nowMs() }
            }
            if (seq % 8 == 0L) tick.value = seq
        }

        override fun onEvent(tNanos: Long, text: String) = log(text)
    }

    var currentLayout: DecodedLayout? = null; private set

    // ---------------- 油圖監看 ----------------
    /** 一次油圖變動：[local]＝這支手機切的。 */
    data class MapEvent(val from: Int, val to: Int, val wallMs: Long, val local: Boolean, val rpm: Double?, val speed: Double?, val lat: Double?, val lon: Double?)
    private val mapWatch = MapWatch()
    val mapNow = MutableStateFlow<Int?>(null)
    /** 這次連線的油圖變動紀錄（最新在後）。 */
    val mapEvents = MutableStateFlow<List<MapEvent>>(emptyList())
    /** 還沒確認的「不是這支手機切的」變動；畫面顯示提醒條，按「知道了」清掉。 */
    val mapAlert = MutableStateFlow<MapEvent?>(null)
    fun ackMapAlert() { mapAlert.value = null }

    private fun onMapChange(c: MapWatch.Change, layout: DecodedLayout, raw: LongArray) {
        val fix = gps.fresh()
        val ev = MapEvent(c.from, c.to, WallClock.nowMs(), c.local, layout.physical(Ch.RPM, raw), layout.physical(Ch.SPEED, raw), fix?.lat, fix?.lon)
        mapEvents.value = (mapEvents.value + ev).takeLast(200)
        val text = "MAP ${c.from} -> ${c.to}${if (c.local) " (this phone)" else " (NOT from this phone)"}" +
            " rpm=${ev.rpm?.toInt() ?: "-"} speed=${ev.speed?.toInt() ?: "-"}" + (fix?.let { " gps=${rx.protocol.Fmt.fixed(it.lat, 6)},${rx.protocol.Fmt.fixed(it.lon, 6)}" } ?: "")
        recording.value?.onEvent(platform.elapsedNanos(), text)
        log(text)
        if (!c.local) {
            mapAlert.value = ev
            platform.alert(ALERT_MAP, Strings.get(R.string.map_alert_title, c.from, c.to),
                Strings.get(R.string.map_alert_text, WallClock.format(ev.wallMs, "HH:mm:ss"), ev.speed?.toInt() ?: 0), vibrate = true)
        }
    }

    // ---------------- 騎乘統計與自動分段 ----------------
    val ride = MutableStateFlow(RideStats())
    private var rideKm = 0.0
    private var rideMaxRpm = 0.0
    private var rideLastNanos = -1L

    private fun resetRide() { rideKm = 0.0; rideMaxRpm = 0.0; rideLastNanos = -1; ride.value = RideStats(startMs = WallClock.nowMs()) }

    private fun onRide(tNanos: Long, layout: DecodedLayout, raw: LongArray) {
        val dt = if (rideLastNanos < 0) 0.0 else ((tNanos - rideLastNanos) / 1e9).coerceIn(0.0, 1.0)
        rideLastNanos = tNanos
        val spd = (if (speedFromGps.value) gps.fresh()?.speedKmh else null) ?: layout.physical(Ch.SPEED, raw) ?: 0.0
        rideKm += spd * dt / 3600.0
        layout.physical(Ch.RPM, raw)?.let { if (it > rideMaxRpm) rideMaxRpm = it }
        if (seq % 8 == 0L) ride.value = ride.value.copy(km = rideKm, maxRpm = rideMaxRpm)
    }

    /**
     * 自動分段：引擎發動（轉速 >300）就開始錄，熄火或收不到資料超過 [RIDE_END_MS] 就收檔並自動健檢，下次發動再開新的一筆。
     * 只用在真的 ECU；體驗模式照舊。
     */
    private suspend fun rideLoop(s: Session, t: Target) {
        var quietSince = -1L
        var lastDataMs = platform.elapsedNanos() / 1_000_000
        var everStreamed = false
        var gpsPaused = false
        while (true) {
            kotlinx.coroutines.delay(1_000)
            if (session !== s) return
            if (t !is Target.Ecu) continue
            val now = platform.elapsedNanos() / 1_000_000
            if (state.value == SessionState.Streaming) {
                everStreamed = true; lastDataMs = now
                if (gpsPaused) { gpsPaused = false; if (gpsEnabled.value) gps.start() }
            } else if (!gpsPaused && now - lastDataMs > GPS_PAUSE_MS) {
                // 車子沒在送資料（熄火、離車）：先關 GPS，最耗電的是它；資料回來再開
                gpsPaused = true; gps.stop()
            }
            val quietMs = now - lastDataMs
            // 省電：一直沒收到資料（在家打開 App 後切走、車子不在旁邊），或停車後很久都沒資料，就自己停。
            // 騎車中（還在錄、或資料有在來）不會觸發；App 畫面開著時也不停。之後回到 App 會自動再連。
            val stopReason = when {
                appVisible || recording.value != null -> null
                !everStreamed && quietMs > NEVER_STREAMED_STOP_MS -> "No data from the ECU for ${quietMs / 60_000} min since connecting"
                everStreamed && quietMs > PARKED_STOP_MS -> "Parked: no data for ${quietMs / 60_000} min"
                else -> null
            }
            if (stopReason != null) {
                log("$stopReason, stopping to save battery")
                disconnect()
                return
            }
            val l = live.value
            val fresh = l != null && state.value == SessionState.Streaming && now - l.tNanos / 1_000_000 < 3_000
            val running = fresh && (l!!.v(Ch.RPM) ?: 0.0) > 300
            if (running) quietSince = -1 else if (quietSince < 0) quietSince = now
            val rec = recording.value
            if (rec == null) { if (autoRecord.value && running) startRecording() }
            else if (quietSince >= 0 && now - quietSince > RIDE_END_MS) {
                log("Engine off for ${RIDE_END_MS / 1000} s, closing this ride")
                stopRecording()
            }
        }
    }
    private var rideJob: Job? = null
    /** 最近一趟騎完後的健檢＋AI 工作。 */
    @kotlin.concurrent.Volatile private var postRideJob: Job? = null

    // ---------------- 騎完自動健檢 ----------------
    /** 一趟騎乘的健檢結果（最新在前）。 */
    data class HealthEntry(val dir: Path, val report: EngineHealth.Report, val ai: String?)
    val healthHistory = MutableStateFlow<List<HealthEntry>>(emptyList())

    /** 健檢清單讀完了沒（通知點進來要等讀完才知道最新一趟是哪個）。 */
    val healthLoaded = MutableStateFlow(false)
    fun loadHealthHistory() = scope.launch(Dispatchers.Default) {
        healthHistory.value = Fs.list(sessionsRoot).filter { Fs.isDir(it) && Fs.exists(Path(it, HealthStore.FILE)) }
            .sortedByDescending { it.name.substringAfter('_') }
            .mapNotNull { d -> runCatching { HealthStore.decode(Fs.readText(Path(d, HealthStore.FILE)))?.let { HealthEntry(d, it, readAi(d)) } }.getOrNull() }
        healthLoaded.value = true
    }

    private fun readAi(d: Path) = Path(d, AI_FILE).let { if (Fs.exists(it)) Fs.readText(it) else null }

    /** 分析一趟紀錄、存檔、更新清單；[notify] 時推一則通知。 */
    suspend fun runHealth(dir: Path, notify: Boolean): EngineHealth.Report? = withContext(Dispatchers.Default) {
        val loga = Path(dir, "decoded.loga")
        if (Fs.size(loga) <= 0) return@withContext null
        val r = runCatching { EngineHealth.analyze(Loga.parse(Fs.readBytes(loga))) }.getOrNull() ?: return@withContext null
        runCatching { Fs.writeText(Path(dir, HealthStore.FILE), HealthStore.encode(r)) }
        healthHistory.value = (listOf(HealthEntry(dir, r, readAi(dir))) + healthHistory.value.filter { it.dir != dir })
            .sortedByDescending { it.dir.name.substringAfter('_') }
        if (notify && r.runningSec >= EngineHealth.MIN_RUNNING_SEC) {
            platform.alert(ALERT_HEALTH, Strings.get(R.string.health_notif_title, r.score), healthHeadline(r), vibrate = r.level == EngineHealth.Level.DANGER)
        }
        r
    }

    /** 通知用的一句話：最嚴重的發現，沒有就說良好。 */
    private fun healthHeadline(r: EngineHealth.Report): String {
        val f = r.findings.firstOrNull { it.level != EngineHealth.Level.INFO } ?: return Strings.get(R.string.health_none)
        return io.github.xeno_hf.rex.ui.findingString(f)
    }

    /** 請 AI 解讀一趟騎乘；成功就存成 ai.txt。 */
    suspend fun aiExplain(dir: Path): Result<String> = runCatching {
        val client = platform.ai ?: error(Strings.get(R.string.ai_unavailable))
        val p = aiProvider.value
        val key = aiKey(p) ?: error(Strings.get(R.string.ai_no_key, p.label))
        val entry = healthHistory.value.firstOrNull { it.dir == dir }
        val report = entry?.report ?: runHealth(dir, notify = false) ?: error(Strings.get(R.string.health_error, "no data"))
        // 引擎數據詳細摘要：從原始紀錄重新整理（不含車速、GPS）；解析大檔要在背景執行緒
        val digest = withContext(Dispatchers.Default) {
            runCatching { rx.session.EngineDigest.build(Loga.parse(Fs.readBytes(Path(dir, "decoded.loga")))) }.getOrNull()
        }
        val user = AiPrompt.user(report, vehicleProfileText(), dir.name, ::findingString, ::metricString, digest)
        val text = client.complete(p, key, aiModel(p), AiPrompt.SYSTEM, user).trim()
        require(text.isNotEmpty()) { Strings.get(R.string.ai_empty) }
        Fs.writeText(Path(dir, AI_FILE), text)
        healthHistory.value = healthHistory.value.map { if (it.dir == dir) it.copy(ai = text) else it }
        text
    }

    fun recentFrames(): List<Triple<Long, Int, Frame>> = synchronized(framesLock) { recentFrames.toList() }

    fun log(text: String) {
        val ts = WallClock.format(WallClock.nowMs(), "HH:mm:ss")
        events.value = (events.value + "$ts  $text").takeLast(300)
    }

    fun connect(t: Target) {
        val reg = registry.value ?: return
        if (userStopped.value) { userStopped.value = false; prefs.putString("userStoppedAt", null) }
        scope.launch {
            disconnectInternal()
            val preset = currentPreset()
            val table = tableFor(preset, reg)
            val layout = DecodedLayout(reg, table)
            currentLayout = layout
            history.clear(); malfSeen.value = emptyMap(); live.value = null
            mapWatch.reset(); mapNow.value = null; mapEvents.value = emptyList(); mapAlert.value = null
            val connector: suspend () -> rx.session.Transport = when (t) {
                is Target.Ecu -> ({ platform.connectEcu(t.host) })
                is Target.Demo -> {
                    val loga = withContext(Dispatchers.Default) {
                        Loga.parse(t.log?.let(Fs::readBytes) ?: platform.builtinDemoLoga() ?: error("no demo log"))
                    }
                    val src = LogaValueSource(loga, reg)
                    ({ LogReplayTransport(scope, src) })
                }
            }
            // 密碼留空＝用 aRacer 出廠預設 888888
            val pw = if (t is Target.Demo) DEFAULT_ECU_PASSWORD else secrets.get()?.trim()?.toIntOrNull() ?: DEFAULT_ECU_PASSWORD
            var created: Session? = null
            val s = Session(connector, table, pw, scope, nanoTime = platform::elapsedNanos,
                tableFor = if (t is Target.Ecu) ({ p -> tableForPlatform({ created }, t, preset, p, created?.table ?: table) }) else null,
                dashSwitches = if (t is Target.Ecu) ECU_SWITCH_FUNS else emptySet())
            created = s
            s.addListener(listener)
            if (uplinkEnabled) startUplink(s, t, table)
            session = s
            target.value = t
            loginJob = scope.launch { s.loggedIn.collect { ecuLoggedIn.value = it } }
            switchJob = scope.launch { s.dashSwitch.collect { dashSwitch.value = it } }
            stateJob = scope.launch {
                s.state.collectLatest {
                    state.value = it
                }
            }
            idleJob = scope.launch { autoStopWhenIdle(s) }
            rideJob = scope.launch { rideLoop(s, t) }
            log("Connect ${if (t is Target.Ecu) t.host else "demo"}; group ${preset.title}: ${table.entries.size} ch")
            s.start()
            if (gpsEnabled.value) gps.start()
            platform.onSessionActive(true)
        }
    }

    fun disconnect() = scope.launch {
        disconnectInternal()
        // 騎完的健檢／AI 還在跑：等它做完（最多 2 分鐘）才放掉前景服務，不然 App 在背景時可能被凍住做不完
        postRideJob?.let { j -> kotlinx.coroutines.withTimeoutOrNull(120_000) { j.join() } }
        platform.onSessionActive(false)
    }

    /** 收不到資料（熄火、離車）超過 [idleStopMinutes] 分鐘就自己斷線，不在背景一直重連。 */
    private suspend fun autoStopWhenIdle(s: Session) {
        var lastData = platform.elapsedNanos() / 1_000_000
        while (true) {
            kotlinx.coroutines.delay(5_000)
            if (session !== s) return
            val now = platform.elapsedNanos() / 1_000_000
            if (state.value == SessionState.Streaming) lastData = now
            val limit = idleStopMinutes
            if (limit > 0 && now - lastData > limit * 60_000L) {
                log("No data for $limit min, disconnecting")
                disconnect()
                return
            }
        }
    }

    private suspend fun disconnectInternal() {
        idleJob?.cancel(); idleJob = null
        rideJob?.cancel(); rideJob = null
        stopRecording()
        platform.releaseEcuAp()
        uplink.value?.let { u -> session?.removeListener(u); u.close() }
        uplink.value = null
        session?.let { it.stop(); it.removeListener(listener) }
        session = null
        gps.stop()
        stateJob?.cancel(); stateJob = null
        loginJob?.cancel(); loginJob = null; ecuLoggedIn.value = false
        switchJob?.cancel(); switchJob = null; dashSwitch.value = emptyMap()
        state.value = SessionState.Idle
        target.value = null
    }

    /** 目前用的車上熱點（顯示用）。 */
    val ecuAp get() = platform.ecuAp

    // ---------------- 分身鏡射 ----------------
    val uplink = MutableStateFlow<UplinkLink?>(null)

    private fun startUplink(s: Session, t: Target, table: rx.protocol.ChannelTable) {
        val host = uplinkAddr.substringBeforeLast(':')
        val port = uplinkAddr.substringAfterLast(':').toIntOrNull() ?: UPLINK_PORT
        val id = WallClock.format(WallClock.nowMs(), "yyyyMMdd_HHmmss") + "_" + platform.deviceName.replace(' ', '_')
        val info = mapOf(
            "ECU SN" to (if (t is Target.Demo) "REPLAY" else ""), "Table ID" to "", "Product ID" to "",
            "rx.version" to platform.appVersion, "rx.target" to t.toString(), "rx.preset" to presetId,
            "rx.device" to platform.deviceName,
        )
        val u = platform.startUplink(host, port, { rx.session.uplinkHello(id, table, info) }) ?: return
        s.addListener(u)
        uplink.value = u
        log("Sync -> $host:$port (id $id)")
    }

    // ---------------- 錄製 ----------------
    fun startRecording() {
        val s = session ?: return
        val layout = currentLayout ?: return
        if (recording.value != null) return
        val label = if (target.value is Target.Demo) "DEMO" else "ECU"
        val dir = Recorder.newSessionDir(sessionsRoot, label)
        val info = mapOf(
            "ECU SN" to (if (label == "DEMO") "REPLAY" else ""),
            "Table ID" to "", "Product ID" to "",
        ) + mapOf(
            "rx.version" to platform.appVersion,
            "rx.target" to (target.value?.toString() ?: ""),
            "rx.preset" to presetId,
            "rx.device" to platform.deviceName,
            "rx.node" to s.node.toString(),
        )
        val rec = Recorder(dir, layout, info,
            extras = (if (gpsEnabled.value) gps.logColumns() else emptyList()) + powerColumns(layout),
            nanoTime = platform::elapsedNanos, clockDomain = "platform.elapsedNanos")
        rec.onEvent(platform.elapsedNanos(), "Recording started: ${layout.table.entries.size} ch, ${layout.columns.size} columns")
        s.addListener(rec)
        liveMarks.value = emptyList()
        resetRide()
        recording.value = rec
        log("Recording -> ${dir.name}")
    }

    /**
     * ReX 自己的欄位：MGU／BMS 電功率（V×A，保留來源電流正負號；BMS 正＝充電、負＝放電）。接在最後、VarID 用 XTune 沒用到的 -9100／-9101，
     * 其他欄位順序不變，XTune 和其他工具照樣讀前面的官方欄。車上沒 MGU／BMS 就不加。
     */
    private fun powerColumns(layout: DecodedLayout): List<Recorder.ExtraColumn> = buildList {
        fun pair(v: Int, a: Int) = layout.indexOf(v) >= 0 && layout.indexOf(a) >= 0
        fun w(raw: LongArray, v: Int, a: Int) = DerivedChannels.product(layout.physical(v, raw), layout.physical(a, raw))
        if (pair(163, 164)) add(Recorder.ExtraColumn(DerivedChannels.MGU_POWER, "ReX", "MGU_Power", 0) { w(it, 163, 164) })
        if (pair(1432, 1433)) add(Recorder.ExtraColumn(DerivedChannels.BMS_POWER, "ReX", "BMS_Power", 0) { w(it, 1432, 1433) })
    }

    fun stopRecording() {
        val rec = recording.value ?: return
        session?.removeListener(rec)
        rec.onEvent(platform.elapsedNanos(), "Recording stopped: ${rec.rounds} rounds")
        rec.close()
        recording.value = null
        liveMarks.value = emptyList()
        log("Recording stopped: ${rec.rounds} rounds, ${rec.frames} frames")
        val dir = rec.dir
        if (!dir.name.startsWith("DEMO") && rec.rounds > 0) postRideJob = scope.launch {
            val r = runHealth(dir, notify = true)
            if (r != null && aiAuto.value && r.runningSec >= EngineHealth.MIN_RUNNING_SEC && aiKey(aiProvider.value) != null) aiExplain(dir)
        }
    }

    /** 錄製中打一個標記；[label] 拿到這筆錄製裡的編號（從 1 起）。沒在錄回 null。 */
    fun mark(label: (Int) -> String): Recorder.Mark? {
        val rec = recording.value ?: return null
        val m = rec.mark(label) ?: return null
        liveMarks.value = rec.marks
        val text = "Mark: ${label(m.n)}"
        uplink.value?.onEvent(platform.elapsedNanos(), text)
        log(text)
        return m
    }

    companion object {
        val DEFAULT_MONITOR = listOf(
            20, 22, 24, 25, 26, 27, 28, 29, 30, 33, 34, 35, 44, 45, 46, 51, 52, 53, 55, 61, 66, 70, 72, 77, 78, 82, 88,
            124, 130, 144, 1183, 1265, 1420, 1427, 1428, 1429, 1430, 1431, 1432, 1433, 1434, 1435, 1436, 1437,
        )
        /**
         * 進階直向兩條 `RaceDBVars_V`="25,34"（SA、TPS%）、進階橫向斜條 `RaceDBVars_H`="34"、
         * 巡航兩格 `SimpleDBVars`="34,55"（TPS%、AFR_WBO2）。
         */
        val DEFAULT_SLOTS = mapOf(
            // 只放每台車都有的通道（BMS／MGU 沒裝就沒有）：GPS 車速、TPS、平均油耗、Fuel_CL、馬力、扭力
            "race1" to PhoneGps.SPEED_ID, "race2" to 34, "race3" to 53, "race4" to 22, "raceH" to 34,
            "cruise1" to 34, "cruise2" to PhoneGps.SPEED_ID, "cruise3" to 22, "cruise4" to 53, "cruise5" to 1420, "cruise6" to 1427,
        )
        const val AUTO_RECORD_DEFAULT = true
        /** 熄火（或收不到資料）多久算一趟騎完。 */
        const val RIDE_END_MS = 5 * 60_000L
        /** 連上後一直沒有資料，多久自己停（App 在背景時）。 */
        const val NEVER_STREAMED_STOP_MS = 30 * 60_000L
        /** 騎完停車後沒有資料，多久自己停（App 在背景時）。 */
        const val PARKED_STOP_MS = 60 * 60_000L
        /** 沒資料多久先關 GPS。 */
        const val GPS_PAUSE_MS = 60_000L
        /** 使用者自己中斷後，多久內不自動連。 */
        const val USER_STOP_HOLD_MS = 30 * 60_000L
        const val OPEN_MAP = "map"
        const val OPEN_HEALTH = "health"
        const val AI_FILE = "ai.txt"
        const val ALERT_MAP = 101
        const val ALERT_HEALTH = 102
        const val UPLINK_PORT = 6680
        val SLOT_MAX = mapOf("race" to 4, "cruise" to 6)

        /**
         * 儀表燈號／多功能鈕要的狀態通道，預設組沒有就連線時補上：Fuel5 #4（AT）、Mode1 #9（過熱）、Malf8 #16（引擎燈）、
         * TCStatus #125（TC）、MGU_Mode2／4 #160／162＋MGU_Energy_Percent #1398（EB）、IOModule_Indicator_Status #973（Pit／LC）。
         */
        val DASH_CHANNELS = listOf(4, 9, 16, 125, 130, 160, 162, 973, 1398)
        /**
         * 開關狀態要直接讀 ECU 物件的儀表鈕（XTune 這三顆讀的也是校正表開關）。
         * TC 的即時通道 TCStatus.TCEnable 在開關打開、車子靜止時仍是 0（實車），騎乘中來回跳；EB 沒有對應的即時通道。
         * Pit／LC 的即時位元就是原版用的來源，不必另讀。
         */
        val ECU_SWITCH_FUNS = setOf(DashFn.AT, DashFn.TC, DashFn.EB)
        val RPM_MAXES = listOf(10, 12, 16)

        fun tableFor(p: Presets.Preset, reg: ChannelRegistry): rx.protocol.ChannelTable {
            val base = if (p.id == Presets.ALL_CHANNELS.id) Presets.allAbsids(reg) else p.absids
            return Presets.table(p.copy(absids = base + DASH_CHANNELS), reg)
        }
    }
}

/** 已錄製的 session 夾。 */
data class SessionDir(val dir: Path, val sizeBytes: Long, val rounds: Long?, val frames: Long?, val marks: List<Recorder.Mark> = emptyList()) {
    companion object {
        private val ROUNDS = Regex("\"rounds\": (\\d+)")
        private val FRAMES = Regex("\"frames\": (\\d+)")
        private const val MAX_EVENTS_FOR_MARKS = 2L shl 20
        fun list(root: Path): List<SessionDir> = Fs.list(root).filter { Fs.isDir(it) }
            .sortedByDescending { it.name.substringAfter('_') } // 依時間（ECU_／DEMO_ 前綴不影響），最新在上
            .map { d ->
                val metaFile = Path(d, "meta.json")
                val meta = if (Fs.exists(metaFile)) Fs.readText(metaFile) else ""
                // 事件檔通常只有幾十 KB；異常大的不為了列標記整檔讀進來
                val events = Path(d, "events.jsonl")
                val marks = if (Fs.size(events) in 1..MAX_EVENTS_FOR_MARKS) runCatching { Recorder.parseMarks(Fs.readText(events)) }.getOrDefault(emptyList()) else emptyList()
                SessionDir(d, Fs.walkFiles(d).sumOf { Fs.size(it) },
                    ROUNDS.find(meta)?.groupValues?.get(1)?.toLong(), FRAMES.find(meta)?.groupValues?.get(1)?.toLong(), marks)
            }
    }
}
