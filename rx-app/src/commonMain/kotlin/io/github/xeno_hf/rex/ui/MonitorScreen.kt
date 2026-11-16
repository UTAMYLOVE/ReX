package io.github.xeno_hf.rex.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xeno_hf.rex.Live
import io.github.xeno_hf.rex.Repo
import io.github.xeno_hf.rex.res.R
import rx.protocol.Fmt
import rx.session.RevFlash
import rx.session.SessionState
import rx.session.WallClock
import kotlin.math.min

/**
 * 監控主畫面（第一個頁籤）：三種風格的錶盤＋四格可換的數值＋節氣門＋系統狀態＋本次騎乘。
 * 全畫面只有轉速條（LED 環或頂部換檔燈）會閃；有警示時只變色、不閃。
 */
@Composable
fun MonitorScreen() {
    val repo = LocalRepo.current
    val live by repo.live.collectAsState()
    val state by repo.state.collectAsState()
    val style by repo.monStyle.collectAsState()
    val tilesMap by repo.monTiles.collectAsState()
    val ride by repo.ride.collectAsState()
    val mapAlert by repo.mapAlert.collectAsState()
    val locked by repo.controlsLocked.collectAsState()
    val rec by repo.recording.collectAsState()
    val redline by repo.redline.collectAsState()
    val wot by repo.wotMode.collectAsState()
    val flashOn by repo.flashOn.collectAsState()
    val gpsSpeed by repo.speedFromGps.collectAsState()
    val night = !RxColors.palette.light
    val pal = if (night) PagePal.NIGHT else PagePal.DAY
    val tm = rememberTextMeasurer()

    // 轉速條動畫：每一幀依最新資料算一次（閃爍相位用幀時間，不用動畫器；省電模式也照樣閃）
    val anim = remember { DialAnimator() }
    anim.settings = RevFlash.Settings(redline, wot, flashOn)
    val frame = remember { mutableStateOf<DialData?>(null) }
    val liveNow by rememberUpdatedState(live)
    val connected = state == SessionState.Streaming
    val connNow by rememberUpdatedState(connected)
    val gpsNow by rememberUpdatedState(gpsSpeed)
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { ms ->
            val l = liveNow
            val spd = (if (gpsNow) repo.gps.fresh()?.speedKmh else null) ?: l?.v(Ch.SPEED)
            frame.value = anim.frame(ms, if (connNow) l?.v(Ch.RPM) else null, l?.v(Ch.TPS), spd?.let { kotlin.math.round(it).toInt() }, l?.v(Ch.MAP_NUM)?.toInt(), connNow && l != null)
        }
    }

    var picking by remember { mutableStateOf<Int?>(null) }
    var mapPick by remember { mutableStateOf(false) }
    val chosen = tilesMap[style] ?: MonField.DEFAULT_TILES
    val shown = MonField.withOverride(chosen, live, ride)

    BoxWithConstraints(Modifier.fillMaxSize().background(pal.page)) {
        val land = maxWidth > maxHeight
        val mw = maxWidth.value
        val dialH = when (style) { MonStyle.A -> A_H; MonStyle.B -> B_H; MonStyle.C -> C_H }
        val lowerDu = 2 * 104f + 8 + 10 + 44 + 8 + 30 + 8 + 24 + (if (mapAlert != null) 48f else 0f)
        // k：每個設計單位幾 dp
        val k = if (land) min((maxWidth.value * 0.48f) / 412f, maxHeight.value / dialH)
        else min(maxWidth.value / 412f, maxHeight.value / (dialH + lowerDu + 20))
        val dial: @Composable () -> Unit = {
            Box(Modifier.size((412 * k).dp, (dialH * k).dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val d = frame.value ?: return@Canvas
                    val sx = size.width / 412f
                    withTransform({ scale(sx, sx, Offset.Zero) }) {
                        when (style) {
                            MonStyle.A -> dialCorsa(tm, d, night)
                            MonStyle.B -> dialPit(tm, d, night)
                            MonStyle.C -> dialKoso(tm, d, night)
                        }
                    }
                }
                // 鎖定 chip
                val lockY = if (style == MonStyle.B) 44f else 6f
                LockChip(locked, night, Modifier.offset((352 * k).dp, (lockY * k).dp).size((44 * k).dp, (28 * k).dp)) {
                    repo.setControlsLocked(!locked)
                    Toasts.show(Strings.get(if (!locked) R.string.lock_on else R.string.lock_off))
                }
                // 點油圖：解鎖時才能切換
                val (mx, my, mw, mh) = when (style) {
                    MonStyle.A -> listOf(168f, 346f, 76f, 36f)
                    MonStyle.B -> listOf(162f, 390f, 88f, 40f)
                    MonStyle.C -> listOf(340f, 316f, 56f, 72f)
                }
                Box(Modifier.offset((mx * k).dp, (my * k).dp).size((mw * k).dp, (mh * k).dp).clickable {
                    if (locked) Toasts.show(Strings.get(R.string.map_locked_hint)) else mapPick = true
                })
            }
        }
        val lower: @Composable (Float) -> Unit = { kk ->
            Column(Modifier.width((412 * kk).dp)) {
                mapAlert?.let { MapAlertBar(it, pal, kk) { repo.ackMapAlert() } }
                val tileW = (412f - 32 - 8) / 2
                for (row in 0 until 2) {
                    Row(Modifier.padding(start = (16 * kk).dp, top = ((if (row == 0) 10f else 8f) * kk).dp)) {
                        for (col in 0 until 2) {
                            val i = row * 2 + col
                            val f = shown.getOrNull(i) ?: continue
                            val v = f.value(live, ride)
                            Tile(tm, f, v, f.level(v, live), live, pal, Modifier.size((tileW * kk).dp, (104 * kk).dp)) { picking = i }
                            if (col == 0) Spacer(Modifier.width((8 * kk).dp))
                        }
                    }
                }
                Canvas(Modifier.padding(top = (8 * kk).dp).size((412 * kk).dp, (44 * kk).dp)) {
                    val sx = size.width / 412f
                    withTransform({ scale(sx, sx, Offset.Zero) }) { tpsRow(tm, live, pal) }
                }
                Canvas(Modifier.padding(top = (8 * kk).dp).size((412 * kk).dp, (30 * kk).dp)) {
                    val sx = size.width / 412f
                    withTransform({ scale(sx, sx, Offset.Zero) }) { sysRow(tm, live, ride, pal) }
                }
                Canvas(Modifier.padding(top = (8 * kk).dp).size((412 * kk).dp, (24 * kk).dp)) {
                    val sx = size.width / 412f
                    withTransform({ scale(sx, sx, Offset.Zero) }) { rideRow(tm, rec != null, ride, pal) }
                }
            }
        }
        if (land) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                dial()
                Box(Modifier.verticalScroll(rememberScrollState())) { lower(min(k * 1.05f, mw * 0.48f / 412f)) }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(4.dp))
                dial()
                lower(k)
            }
        }
    }

    picking?.let { i ->
        FieldPicker(stringResource(R.string.pick_field_title), chosen.getOrNull(i), onPick = { repo.setMonTile(style, i, it); picking = null }, onDismiss = { picking = null })
    }
    if (mapPick) {
        val now by repo.mapNow.collectAsState()
        AlertDialog(
            onDismissRequest = { mapPick = false },
            containerColor = RxColors.card,
            title = { Text(stringResource(R.string.map_pick_title)) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    (1..3).forEach { m ->
                        Box(
                            Modifier.size(64.dp, 52.dp).background(if (now == m) mapFill(m) else RxColors.track, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clickable { repo.setMap(m); mapPick = false },
                            contentAlignment = Alignment.Center,
                        ) { Text("$m", style = digits(26.sp, FontWeight.ExtraBold), color = if (now == m) Inst.bg else RxColors.text) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { mapPick = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** 鎖頭 chip：10° 平行四邊形。鎖上＝實心玫瑰底。 */
@Composable
private fun LockChip(locked: Boolean, night: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Canvas(modifier.clickable(onClick = onClick)) {
        val w = size.width; val h = size.height
        val s = w / 44f
        withTransform({ scale(s, s, Offset.Zero) }) {
            val p = paraPath(0f, 0f, 44f, 28f)
            if (locked) drawPath(p, Inst.rose) else { drawPath(p, if (night) Inst.surf2 else Color.White); drawPath(p, Inst.mocha.copy(alpha = .4f), style = Stroke(1f)) }
            val c = if (locked) Inst.bg else if (night) Inst.beige else Inst.labelDay
            drawRoundRect(c, Offset(16.5f, 12.5f), Size(11f, 8.5f), androidx.compose.ui.geometry.CornerRadius(1.5f), style = Stroke(1.5f))
            val shackle = androidx.compose.ui.graphics.Path().apply {
                moveTo(18.75f, 12.5f); lineTo(18.75f, 10f)
                arcTo(androidx.compose.ui.geometry.Rect(18.75f, 6.75f, 25.25f, 13.25f), 180f, 180f, false)
                lineTo(25.25f, if (locked) 12.5f else 10.5f)
            }
            drawPath(shackle, c, style = Stroke(1.5f))
        }
    }
}

/** 一格數值：標籤、數值（−10° 斜）、單位；長按換欄位。警示時變色，不閃。 */
@Composable
private fun Tile(tm: TextMeasurer, f: MonField, v: Double?, lv: Lv, live: Live?, pal: PagePal, modifier: Modifier, onLongPress: () -> Unit) {
    val label = stringResource(f.label)
    Canvas(modifier.pointerInput(f) { detectTapGestures(onLongPress = { onLongPress() }) }) {
        val s = size.width / 186f
        withTransform({ scale(s, s, Offset.Zero) }) {
            val p = chamPath(0f, 0f, 186f, 104f, 10f)
            drawPath(p, pal.tile)
            if (pal.night) {
                drawLine(Color.White.copy(alpha = .05f), Offset(10f, 1f), Offset(186f, 1f), 2f)
                drawLine(Color.Black.copy(alpha = .5f), Offset(0f, 103f), Offset(176f, 103f), 2f)
            } else drawLine(Inst.mocha.copy(alpha = .3f), Offset(0f, 103f), Offset(176f, 103f), 2f)
            if (lv != Lv.OK) drawPath(chamPath(.75f, .75f, 184.5f, 102.5f, 9.7f), pal.of(lv), style = Stroke(if (lv == Lv.FAULT) 2.5f else 1.5f))
            val lc = if (lv == Lv.OK) pal.label else pal.of(lv)
            txt(tm, (if (lv != Lv.OK) "◆ " else "") + label, 12f, 28f, 13f, lc)
            val text = f.text(v)
            val w = txt(tm, text, 12f, 84f, 40f, pal.of(lv), FontWeight.ExtraBold, digits = true, skew = true, spacing = -.02f)
            val ux = 12f + w + 6f
            if (f == MonField.AFR && v != null) {
                val t = live?.v(20)
                if (t != null && t in 9.0..19.0) {
                    val dv = kotlin.math.round((v - t) * 10) / 10
                    val ds = (if (dv > 0) "+" else if (dv < 0) "-" else "±") + Fmt.fixed(kotlin.math.abs(dv), 1)
                    txt(tm, "Δ$ds", ux, 84f, 13f, pal.label, FontWeight.SemiBold)
                }
            } else if (f.unit.isNotEmpty() && v != null) txt(tm, f.unit, ux, 84f, 13f, pal.label, spacing = .06f)
        }
    }
}

private fun DrawScope.tpsRow(tm: TextMeasurer, l: Live?, pal: PagePal) {
    val tps = l?.v(Ch.TPS)
    val w = txt(tm, Strings.get(R.string.f_tps_long), 16f, 26f, 13f, pal.label)
    if (tps != null && tps >= 95) {
        val text = Strings.get(R.string.wot)
        val cw = textWidth(tm, text, 13f) + 22
        drawPath(paraPath(16 + w + 12, 12f, cw, 20f), Inst.aqua)
        txt(tm, text, 16 + w + 12 + cw / 2, 26.5f, 13f, if (pal.night) Inst.bg else Inst.ink, anchor = 'c')
    }
    val pw = txt(tm, "%", 396f, 26f, 13f, pal.label, anchor = 'r')
    txt(tm, tps?.let { "${kotlin.math.round(it).toInt()}" } ?: "—", 396f - pw - 4, 26f, 28f, pal.num, FontWeight.ExtraBold, digits = true, anchor = 'r')
    drawRect(pal.track, Offset(16f, 36f), Size(380f, 6f))
    if (tps != null) drawRect(pal.bar, Offset(16f, 36f), Size(380f * (tps / 100).toFloat().coerceIn(0f, 1f), 6f))
}

/** 系統狀態扇區（平常全暗；注意＝玫瑰外框；危險＝紅底）。 */
private fun DrawScope.sysRow(tm: TextMeasurer, l: Live?, ride: RideStats, pal: PagePal) {
    fun lv(vararg f: MonField) = f.maxOfOrNull { it.level(it.value(l, ride), l).ordinal }?.let { Lv.entries[it] } ?: Lv.OK
    val crank = if ((l?.v(72) ?: 0.0) > 0) Lv.CAUTION else Lv.OK
    val items = listOf(R.string.health_a_cooling to lv(MonField.TEMP), R.string.health_a_fuel to lv(MonField.AFR, MonField.TRIM),
        R.string.health_a_electrical to lv(MonField.VOLT), R.string.health_a_idle to Lv.OK, R.string.health_a_ignition to crank,
        R.string.health_a_faults to lv(MonField.FAULTS))
    val x0 = 16f; val W = 380f; val H = 28f; val n = items.size
    drawPath(chamPath(x0 + .5f, 1.5f, W - 1, H - 1, 5.8f), Inst.mocha.copy(alpha = .28f), style = Stroke(1f))
    items.forEachIndexed { i, (label, v) ->
        val xa = x0 + W * i / n; val xb = x0 + W * (i + 1) / n
        if (v == Lv.FAULT) drawPath(paraPath(xa + 3, 4f, xb - xa - 6, H - 6), pal.fault)
        else if (v == Lv.CAUTION) drawPath(paraPath(xa + 3, 4f, xb - xa - 6, H - 6), pal.caution, style = Stroke(1.5f))
        if (i > 0) { val o = H * Inst.T10 / 2; drawLine(Inst.mocha.copy(alpha = .28f), Offset(xa + o, 5f), Offset(xa - o, H - 3), 1f) }
        val c = when (v) { Lv.FAULT -> Color.White; Lv.CAUTION -> pal.caution; Lv.OK -> pal.label }
        txt(tm, Strings.get(label), (xa + xb) / 2, 20.5f, 13f, c, anchor = 'c', alpha = if (v == Lv.OK) .6f else 1f)
    }
}

private fun DrawScope.rideRow(tm: TextMeasurer, recording: Boolean, ride: RideStats, pal: PagePal) {
    drawCircle(if (recording) pal.rec else Inst.mocha.copy(alpha = .5f), 3.5f, Offset(20f, 13f))
    var x = 30f
    fun seg(t: String, num: Boolean) { x += txt(tm, t, x, 18f, 13f, if (num) pal.num else pal.label, if (num) FontWeight.SemiBold else FontWeight.Medium, digits = num) + 5 }
    if (!recording) { seg(Strings.get(R.string.ride_not_recording), false); return }
    val mins = ((WallClock.nowMs() - ride.startMs) / 60_000).coerceAtLeast(0)
    seg(Strings.get(R.string.ride_recording), false)
    seg("${mins / 60}:${(mins % 60).toString().padStart(2, '0')}", true); seg("·", false)
    seg(Fmt.fixed(ride.km, 1), true); seg("km", false); seg("·", false)
    seg(Strings.get(R.string.ride_max), false); seg(rpmText(ride.maxRpm), true); seg("RPM", false)
}

/** 「油圖被切換、不是從這支手機切的」提醒條（注意等級：玫瑰外框，不用紅色）。 */
@Composable
private fun MapAlertBar(ev: Repo.MapEvent, pal: PagePal, k: Float, onAck: () -> Unit) {
    val tm = rememberTextMeasurer()
    Box(Modifier.padding(start = (16 * k).dp, top = (8 * k).dp).size((380 * k).dp, (40 * k).dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val s = size.width / 380f
            withTransform({ scale(s, s, Offset.Zero) }) {
                val p = chamPath(0f, 0f, 380f, 40f, 10f)
                drawPath(p, pal.tile)
                drawPath(chamPath(.75f, .75f, 378.5f, 38.5f, 9.7f), pal.caution, style = Stroke(1.5f))
                val w = txt(tm, "◆ ${ev.from}→${ev.to}", 12f, 26f, 18f, pal.caution, FontWeight.ExtraBold, digits = true)
                txt(tm, Strings.get(R.string.map_alert_bar, WallClock.format(ev.wallMs, "HH:mm:ss")), 12 + w + 10, 25f, 13f, pal.num)
            }
        }
        TextButton(onClick = onAck, modifier = Modifier.align(Alignment.CenterEnd)) { Text(stringResource(R.string.ack), color = RxColors.brandInk, fontSize = 13.sp) }
    }
}

/** 欄位選單（監控四格與懸浮窗三列共用）。 */
@Composable
fun FieldPicker(title: String, current: MonField?, onPick: (MonField) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RxColors.card,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MonField.entries.forEach { f ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(f) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(if (f == current) RxColors.brand else RxColors.line, androidx.compose.foundation.shape.CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(f.label), modifier = Modifier.weight(1f))
                        if (f.unit.isNotEmpty()) Text(f.unit, color = RxColors.muted, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
