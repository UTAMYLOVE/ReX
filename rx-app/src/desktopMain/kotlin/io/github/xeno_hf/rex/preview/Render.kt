package io.github.xeno_hf.rex.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.xeno_hf.rex.KeyValueStore
import io.github.xeno_hf.rex.Live
import io.github.xeno_hf.rex.PhoneGps
import io.github.xeno_hf.rex.Platform
import io.github.xeno_hf.rex.Repo
import io.github.xeno_hf.rex.SecretStore
import io.github.xeno_hf.rex.ui.LocalRepo
import io.github.xeno_hf.rex.ui.MonStyle
import io.github.xeno_hf.rex.ui.MonitorScreen
import io.github.xeno_hf.rex.ui.RxTheme
import io.github.xeno_hf.rex.ui.Strings
import io.github.xeno_hf.rex.ui.ThemeChoice
import io.github.xeno_hf.rex.ui.WingOverlayContent
import io.github.xeno_hf.rex.ui.YozakuraApp
import rx.session.EngineHealth
import rx.session.HealthStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import org.jetbrains.skia.EncodedImageFormat
import rx.session.DecodedLayout
import rx.session.Presets
import rx.session.SessionState
import java.io.File

/**
 * 開發用桌面預覽：用假資料把真正的 App 畫面（監控三種風格、懸浮小儀表、引擎健檢）畫成 PNG。
 * 執行：gradle -Prx.preview :rx-app:renderPreview（輸出在 rx-app/build/preview）。
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/preview").apply { mkdirs() }
    Strings.setLanguage(listOf(System.getProperty("rx.lang") ?: "zh-Hant-TW"))
    val repo = Repo(PreviewPlatform(File(out, "data").apply { mkdirs() }))
    repo.useBuiltin("S3")
    val reg = repo.registry.value!!
    val layout = DecodedLayout(reg, Repo.tableFor(Presets.ALL_CHANNELS, reg))

    fun live(vararg v: Pair<Int, Double>): Live {
        val m = v.toMap()
        val raw = LongArray(layout.entryDefs.size) { i -> val d = layout.entryDefs[i]; m[d.absid]?.let { Math.round(it * d.scale + d.offset) } ?: 0L }
        return Live(layout, raw, System.nanoTime(), 1)
    }
    val cruise = live(26 to 5240.0, 51 to 62.0, 34 to 32.0, 33 to 96.0, 29 to 14.1, 55 to 13.2, 20 to 13.0, 22 to 2.1, 144 to 2.0, 25 to 28.5)
    val rev = live(26 to 9900.0, 51 to 104.0, 34 to 100.0, 33 to 99.0, 29 to 14.1, 55 to 12.8, 20 to 12.8, 22 to 0.4, 144 to 2.0, 25 to 31.0)
    val hot = live(26 to 9900.0, 51 to 104.0, 34 to 100.0, 33 to 118.0, 29 to 14.0, 55 to 12.9, 20 to 12.8, 22 to 0.4, 144 to 2.0, 25 to 31.0)

    repo.setSpeedFromGps(false)
    repo.autoConnect = false // 預覽不真的連線
    repo.state.value = SessionState.Streaming
    repo.target.value = io.github.xeno_hf.rex.Target.Ecu("192.168.88.88")

    // 假的兩趟騎乘紀錄（健檢分頁用）
    fun fakeRide(name: String, score: Int, level: EngineHealth.Level, findings: List<EngineHealth.Finding>, ai: String?) {
        val d = File(File(out, "data/sessions"), name).apply { mkdirs() }
        File(d, "meta.json").writeText("{\n  \"rounds\": 9800,\n  \"frames\": 41000\n}\n")
        File(d, "decoded.loga").writeText("aRacer preview")
        val r = EngineHealth.Report(score, level, 2460.0, 2310.0, 9800,
            listOf(EngineHealth.AreaScore(EngineHealth.Area.COOLING, if (level == EngineHealth.Level.OK) 100 else 70, level, true)),
            findings, listOf(EngineHealth.Metric("t_eng_max", 96.0, "°C", 0)), emptyMap())
        File(d, HealthStore.FILE).writeText(HealthStore.encode(r))
        if (ai != null) File(d, Repo.AI_FILE).writeText(ai)
    }
    fakeRide("ECU_20261116_083000", 86, EngineHealth.Level.WARN, listOf(EngineHealth.Finding(EngineHealth.Area.COOLING, EngineHealth.Level.WARN, "temp_warn", listOf("117", "42"))),
        "整體狀況正常。水溫在長上坡時短暫到 117 °C，建議檢查冷卻液量與水箱散熱片是否被堵住；空燃比與電壓都在正常範圍。")
    fakeRide("ECU_20261115_191200", 95, EngineHealth.Level.OK, emptyList(), null)

    fun shot(name: String, w: Int, h: Int, theme: ThemeChoice, l: Live, times: List<Long>, wait: Long = 400, content: @androidx.compose.runtime.Composable () -> Unit) {
        repo.live.value = l
        val density = 2f
        val scene = ImageComposeScene((w * density).toInt(), (h * density).toInt(), Density(density)) {
            CompositionLocalProvider(LocalRepo provides repo) {
                RxTheme(theme) { content() }
            }
        }
        var img = scene.render(0)
        Thread.sleep(wait)
        for (t in times) img = scene.render(t * 1_000_000)
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println(File(out, "$name.png").path)
    }
    // 爆閃動畫：加速 → 撞紅線 → 收油，每 40 ms 一張，給使用者比較幾種爆閃配色
    System.getProperty("rx.anim")?.let {
        repo.setMonStyle(MonStyle.C)
        repo.setRedline(10_000); repo.setFlashRpm(9000); repo.setFlashPre(750)
        io.github.xeno_hf.rex.ui.DialAnimator.demoPeak = null
        for ((idx, theme) in listOf(ThemeChoice.NIPPON, ThemeChoice.YOZAKURA).withIndex()) {
            val dir = File(out, "anim$idx").apply { mkdirs() }
            fun at(ms: Long): Live {
                val e = { x: Double -> x * x * (3 - 2 * x) }
                val rpm = when {
                    ms < 1400 -> 5000 + 3400 * e(ms / 1400.0)                 // 加速到慢閃區
                    ms < 3400 -> 8400 + 350 * ((ms - 1400) / 2000.0)          // 8,400→8,750：慢閃
                    ms < 3700 -> 8750 + 400 * e((ms - 3400) / 300.0)          // 衝過 9,000
                    ms < 5600 -> 9150 + 80 * Math.sin(ms / 70.0)              // 快閃
                    else -> 9150 - 4000 * e(((ms - 5600) / 700.0).coerceAtMost(1.0))
                }
                return live(26 to rpm, 51 to 40 + ms / 80.0, 34 to (if (ms < 5600) 62.0 else 8.0), 33 to 96.0, 29 to 14.1, 22 to 2.1, 144 to 2.0, 25 to 28.5, 24 to 38.0, 46 to 780.0, 45 to (if (ms < 5600) 96.0 else 40.0))
            }
            repo.live.value = at(0)
            val scene = ImageComposeScene(618, 780, Density(1.5f)) { CompositionLocalProvider(LocalRepo provides repo) { RxTheme(theme) { MonitorScreen() } } }
            scene.render(0); Thread.sleep(400)
            for (i in 0 until 165) { val ms = i * 40L; repo.live.value = at(ms); Thread.sleep(15); val img = scene.render((1000 + ms) * 1_000_000)
                File(dir, "%03d.png".format(i)).writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
            scene.close()
        }
        return
    }
    val steady = listOf(20L, 40L, 60L)
    io.github.xeno_hf.rex.ui.DialAnimator.demoPeak = 7400.0
    val revOn = listOf(1000L, 1050L, 1100L, 1150L) // 100 ms 後進 REV；1150 時在 ON 相位
    for (st in MonStyle.entries) {
        repo.setMonStyle(st)
        shot("monitor_${st.name}_night", 412, 760, ThemeChoice.YOZAKURA, cruise, steady) { MonitorScreen() }
        shot("monitor_${st.name}_rev", 412, 760, ThemeChoice.YOZAKURA, rev, revOn) { MonitorScreen() }
        shot("monitor_${st.name}_day", 412, 760, ThemeChoice.NIPPON, cruise, steady) { MonitorScreen() }
    }
    repo.setMonStyle(MonStyle.C)
    repo.mapAlert.value = Repo.MapEvent(2, 3, 1_763_280_501_000, false, 6480.0, 71.0, null, null)
    repo.setMonStyle(MonStyle.C)
    shot("monitor_C_warm", 412, 760, ThemeChoice.YOZAKURA, live(26 to 3200.0, 51 to 28.0, 34 to 18.0, 33 to 52.0, 29 to 14.1, 22 to 2.1, 144 to 2.0, 25 to 18.5, 24 to 26.0, 46 to 620.0, 45 to 48.0), steady) { MonitorScreen() }
    shot("monitor_C_warm_day", 412, 760, ThemeChoice.NIPPON, live(26 to 3200.0, 51 to 28.0, 34 to 18.0, 33 to 52.0, 29 to 14.1, 22 to 2.1, 144 to 2.0, 25 to 18.5, 24 to 26.0, 46 to 620.0, 45 to 48.0), steady) { MonitorScreen() }
    shot("monitor_C_mapalert", 412, 760, ThemeChoice.YOZAKURA, hot, steady) { MonitorScreen() }
    repo.mapAlert.value = null
    shot("overlay_normal", 232, 148, ThemeChoice.YOZAKURA, cruise, steady) { Box(Modifier.fillMaxSize().background(Color(0xFFECE8E1)).padding(10.dp)) { WingOverlayContent(repo) } }
    shot("overlay_hot_rev", 232, 148, ThemeChoice.YOZAKURA, hot, revOn) { Box(Modifier.fillMaxSize().background(Color(0xFFECE8E1)).padding(10.dp)) { WingOverlayContent(repo) } }

    // 整個夜櫻 App（頂列＋分頁）
    repo.loadHealthHistory()
    Thread.sleep(500)
    repo.mapNow.value = 2
    repo.setTheme(ThemeChoice.YOZAKURA)
    for (tab in listOf("Monitor", "Health", "Settings")) {
        shot("app_${tab.lowercase()}", 412, 892, ThemeChoice.YOZAKURA, cruise, steady, wait = 800) { YozakuraApp(repo, startTab = tab) }
    }
    repo.setTheme(ThemeChoice.NIPPON)
    shot("app_monitor_day", 412, 892, ThemeChoice.NIPPON, cruise, steady, wait = 800) { YozakuraApp(repo, startTab = "Monitor") }
    repo.setTheme(ThemeChoice.YOZAKURA)
    // 小螢幕（360 dp 寬）檢查有沒有擠爆
    shot("app_monitor_360", 360, 760, ThemeChoice.YOZAKURA, cruise, steady, wait = 800) { YozakuraApp(repo, startTab = "Monitor") }
    shot("app_settings_360", 360, 760, ThemeChoice.YOZAKURA, cruise, steady, wait = 800) { YozakuraApp(repo, startTab = "Settings") }
    shot("app_health_360", 360, 760, ThemeChoice.YOZAKURA, cruise, steady, wait = 800) { YozakuraApp(repo, startTab = "Health") }
    repo.setMonStyle(MonStyle.D)
    shot("d_night", 412, 892, ThemeChoice.YOZAKURA, cruise, steady, wait = 800) { YozakuraApp(repo, startTab = "Monitor") }
    shot("d_rev", 412, 892, ThemeChoice.YOZAKURA, rev, revOn, wait = 800) { YozakuraApp(repo, startTab = "Monitor") }
    shot("d_day", 412, 892, ThemeChoice.NIPPON, cruise, steady, wait = 800) { YozakuraApp(repo, startTab = "Monitor") }
    shot("d_alert", 412, 892, ThemeChoice.YOZAKURA, hot, steady, wait = 800) { YozakuraApp(repo, startTab = "Monitor") }
    shot("d_land", 816, 392, ThemeChoice.YOZAKURA, cruise, steady, wait = 800) { MonitorScreen() }
    repo.setMonStyle(MonStyle.C)
    repo.openRequest.value = Repo.OPEN_MAP
    shot("app_map_window", 412, 892, ThemeChoice.YOZAKURA, cruise, steady, wait = 800) { YozakuraApp(repo, startTab = "Monitor") }
    repo.openRequest.value = null
    repo.target.value = null; repo.state.value = SessionState.Idle
    shot("app_idle", 412, 892, ThemeChoice.YOZAKURA, cruise, steady, wait = 800) { YozakuraApp(repo, startTab = "Monitor") }
}

private class PreviewPlatform(dir: File) : Platform {
    private val m = HashMap<String, Any?>()
    override val kv = object : KeyValueStore {
        override fun getString(key: String, def: String?) = m[key] as? String ?: def
        override fun putString(key: String, value: String?) { m[key] = value }
        override fun getInt(key: String, def: Int) = m[key] as? Int ?: def
        override fun putInt(key: String, value: Int) { m[key] = value }
        override fun getBoolean(key: String, def: Boolean) = m[key] as? Boolean ?: def
        override fun putBoolean(key: String, value: Boolean) { m[key] = value }
    }
    override val secrets = object : SecretStore { override fun get(slot: String): String? = null; override fun put(value: String?, slot: String) {} }
    override val gps = object : PhoneGps {
        override val fix = MutableStateFlow<PhoneGps.Fix?>(null)
        override fun hasPermission() = false
        override fun start() {}
        override fun stop() {}
        override fun fresh(): PhoneGps.Fix? = null
    }
    override val filesDir = Path(dir.path)
    override val sessionsRoot = Path(File(dir, "sessions").path)
    override val appVersion = "preview"
    override val deviceName = "preview"
    override val supportsNetworkBinding = false
    override val canDecryptControlFile = false
    override suspend fun controlFileFromBytes(bytes: ByteArray) = error("preview")
    override fun builtinDemoLoga(): ByteArray? = null
    override suspend fun connectEcu(host: String, port: Int) = error("preview")
    override val ecuAp = MutableStateFlow<String?>(null)
    override fun ecuFinder() = error("preview")
    override fun onSessionActive(active: Boolean) {}
    override fun keepScreenOn(on: Boolean) {}
    override fun share(path: Path, mime: String, title: String) {}
    override fun unzipFirst(zip: ByteArray, nameFilter: (String) -> Boolean): Pair<String, ByteArray>? = null
}

