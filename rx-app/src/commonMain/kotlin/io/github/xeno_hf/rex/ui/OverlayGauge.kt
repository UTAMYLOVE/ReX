package io.github.xeno_hf.rex.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.xeno_hf.rex.Repo
import rx.session.RevFlash
import rx.session.SessionState

/**
 * 懸浮小儀表的內容（側翼式 212×128 dp）：Android 放在「顯示在其他應用程式上層」的視窗裡，疊在 Google 地圖上。
 * 轉速環照樣會閃；警示只變色，不閃整個視窗。
 */
@Composable
fun WingOverlayContent(repo: Repo) {
    val live by repo.live.collectAsState()
    val state by repo.state.collectAsState()
    val rows by repo.overlayRows.collectAsState()
    val ride by repo.ride.collectAsState()
    val redline by repo.redline.collectAsState()
    val wot by repo.wotMode.collectAsState()
    val flashOn by repo.flashOn.collectAsState()
    val flashRpm by repo.flashRpm.collectAsState()
    val flashPre by repo.flashPre.collectAsState()
    val gpsSpeed by repo.speedFromGps.collectAsState()
    val tm = rememberTextMeasurer()
    val anim = remember { DialAnimator() }
    val warmOn by repo.warmMode.collectAsState()
    val warmup = remember { rx.session.Warmup() }
    val warmLimit = if (warmOn && state == SessionState.Streaming) warmup.limit(live?.v(Ch.T_ENG), if (flashRpm > 0) flashRpm else redline - 200) else null
    anim.settings = RevFlash.Settings(warmLimit?.let { kotlin.math.min(redline, ((it + 499) / 500) * 500) } ?: redline, wot, flashOn, warmLimit ?: flashRpm.takeIf { it > 0 }, flashPre)
    val frame = remember { mutableStateOf<DialData?>(null) }
    val liveNow by rememberUpdatedState(live)
    val connNow by rememberUpdatedState(state == SessionState.Streaming)
    val gpsNow by rememberUpdatedState(gpsSpeed)
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { ms ->
            val l = liveNow
            val spd = (if (gpsNow) repo.gps.fresh()?.speedKmh else null) ?: l?.v(Ch.SPEED)
            frame.value = anim.frame(ms, if (connNow) l?.v(Ch.RPM) else null, l?.v(Ch.TPS), spd?.let { kotlin.math.round(it).toInt() }, l?.v(Ch.MAP_NUM)?.toInt(), connNow && l != null)
        }
    }
    val shown = MonField.withOverride(rows, live, ride).map { f ->
        val v = f.value(live, ride)
        WingRow(Strings.get(f.label), f.text(v), if (v == null) "" else f.unit, f.level(v, live))
    }
    InstrumentFonts {
        Canvas(Modifier.size(WING_W.dp, WING_H.dp)) {
            val d = frame.value ?: return@Canvas
            val s = size.width / WING_W
            withTransform({ scale(s, s, Offset.Zero) }) { wingGauge(tm, d, shown) }
        }
    }
}
