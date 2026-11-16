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
    repo.state.value = SessionState.Streaming

    fun shot(name: String, w: Int, h: Int, theme: ThemeChoice, l: Live, times: List<Long>, content: @androidx.compose.runtime.Composable () -> Unit) {
        repo.live.value = l
        val density = 2f
        val scene = ImageComposeScene((w * density).toInt(), (h * density).toInt(), Density(density)) {
            CompositionLocalProvider(LocalRepo provides repo) {
                RxTheme(theme) { content() }
            }
        }
        var img = scene.render(0)
        Thread.sleep(400)
        for (t in times) img = scene.render(t * 1_000_000)
        File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println(File(out, "$name.png").path)
    }
    val steady = listOf(20L, 40L, 60L)
    val revOn = listOf(1000L, 1050L, 1100L, 1150L) // 100 ms 後進 REV；1150 時在 ON 相位
    for (st in MonStyle.entries) {
        repo.setMonStyle(st)
        shot("monitor_${st.name}_night", 412, 760, ThemeChoice.YOZAKURA, cruise, steady) { MonitorScreen() }
        shot("monitor_${st.name}_rev", 412, 760, ThemeChoice.YOZAKURA, rev, revOn) { MonitorScreen() }
        shot("monitor_${st.name}_day", 412, 760, ThemeChoice.NIPPON, cruise, steady) { MonitorScreen() }
    }
    repo.setMonStyle(MonStyle.C)
    repo.mapAlert.value = Repo.MapEvent(2, 3, 1_763_280_501_000, false, 6480.0, 71.0, null, null)
    shot("monitor_C_mapalert", 412, 760, ThemeChoice.YOZAKURA, hot, steady) { MonitorScreen() }
    repo.mapAlert.value = null
    shot("overlay_normal", 232, 148, ThemeChoice.YOZAKURA, cruise, steady) { Box(Modifier.fillMaxSize().background(Color(0xFFECE8E1)).padding(10.dp)) { WingOverlayContent(repo) } }
    shot("overlay_hot_rev", 232, 148, ThemeChoice.YOZAKURA, hot, revOn) { Box(Modifier.fillMaxSize().background(Color(0xFFECE8E1)).padding(10.dp)) { WingOverlayContent(repo) } }
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

