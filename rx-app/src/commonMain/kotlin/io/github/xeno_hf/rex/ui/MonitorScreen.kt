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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
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
    val chosen by repo.monTiles.collectAsState()
    val six by repo.monSix.collectAsState()
    val eight by repo.monEight.collectAsState()
    val ride by repo.ride.collectAsState()
    val mapAlert by repo.mapAlert.collectAsState()
    val history by repo.healthHistory.collectAsState()
    val open by repo.openRequest.collectAsState()
    val overlayOn by repo.overlayOn.collectAsState()
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
    var mapOpen by remember { mutableStateOf(false) }
    var faultOpen by remember { mutableStateOf(false) }
    val shown = MonField.withOverride(eight, live, ride)
    // 點油圖通知：直接打開油圖視窗
    LaunchedEffect(open) { if (open == Repo.OPEN_MAP) { repo.openRequest.value = null; mapOpen = true } }
    // 上一趟的健檢（太短的不算）
    val lastHealth = history.firstOrNull()?.takeIf { rec == null && it.report.runningSec >= rx.session.EngineHealth.MIN_RUNNING_SEC }
    // 懸浮小儀表開著但還沒給權限：監控頁提醒一行（從系統設定回來會自己消失）
    var overlayPermitted by remember { mutableStateOf(repo.platform.overlayPermitted()) }
    val nudge = repo.platform.supportsOverlay && overlayOn && !overlayPermitted
    if (nudge) LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); overlayPermitted = repo.platform.overlayPermitted() } }

    if (style == MonStyle.D) BoxWithConstraints(Modifier.fillMaxSize().background(pal.page)) {
        MonitorD(frame, live, ride, six, pal, night, mapAlert, rec != null, maxWidth > maxHeight,
            onMap = { mapOpen = true }, onFault = { faultOpen = true }, onAck = { repo.ackMapAlert() }, onPick = { picking = it })
    } else BoxWithConstraints(Modifier.fillMaxSize().background(pal.page)) {
        val land = maxWidth > maxHeight
        val mw = maxWidth.value
        val dialH = when (style) { MonStyle.A -> A_H; MonStyle.B -> B_H; MonStyle.C, MonStyle.D -> C_H }
        val lowerDu = 4 * ROW_H + 14 + 8 + 30 + 8 + 24 + (if (mapAlert != null) 48f else 0f) + (if (nudge) 48f else 0f)
        // k：每個設計單位幾 dp
        val k = if (land) min((maxWidth.value * 0.48f) / 412f, maxHeight.value / dialH)
        else min(maxWidth.value / 412f, maxHeight.value / (dialH + lowerDu + 20))
        val dial: @Composable () -> Unit = {
            Box(Modifier.size((412 * k).dp, (dialH * k).dp)) {
                // 夜間：錶盤後面一圈玫瑰色光暈（像高速公路夜燈），不蓋到任何數字
                if (night) Canvas(Modifier.fillMaxSize()) {
                    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
                        0f to Inst.rose.copy(alpha = .30f), .62f to Inst.rose.copy(alpha = .12f), 1f to Color.Transparent,
                        center = Offset(size.width / 2, size.height * .5f), radius = size.width * .62f), size.width * .62f, Offset(size.width / 2, size.height * .5f))
                }
                Canvas(Modifier.fillMaxSize()) {
                    val d = frame.value ?: return@Canvas
                    val sx = size.width / 412f
                    withTransform({ scale(sx, sx, Offset.Zero) }) {
                        when (style) {
                            MonStyle.A -> dialCorsa(tm, d, night)
                            MonStyle.B -> dialPit(tm, d, night)
                            MonStyle.C, MonStyle.D -> dialKoso(tm, d, night)
                        }
                    }
                }
                // 點油圖：打開油圖視窗（目前油圖、這次的切換紀錄、解鎖切換）
                val (mx, my, mw, mh) = when (style) {
                    MonStyle.A -> listOf(168f, 346f, 76f, 36f)
                    MonStyle.B -> listOf(162f, 390f, 88f, 40f)
                    MonStyle.C, MonStyle.D -> listOf(340f, 316f, 56f, 72f)
                }
                Box(Modifier.offset((mx * k).dp, (my * k).dp).size((mw * k).dp, (mh * k).dp).clickable { mapOpen = true })
            }
        }
        val lower: @Composable (Float) -> Unit = { kk ->
            Column(Modifier.width((412 * kk).dp)) {
                mapAlert?.let { MapAlertBar(it, pal, kk, onOpen = { mapOpen = true }) { repo.ackMapAlert() } }
                // 八列數據：左右各四，每列＝名稱＋數字＋一條細量表
                for (row in 0 until 4) {
                    Row(Modifier.padding(start = (20 * kk).dp, top = ((if (row == 0) 14f else 0f) * kk).dp)) {
                        for (col in 0 until 2) {
                            val i = col * 4 + row
                            val f = shown.getOrNull(i) ?: continue
                            val v = f.value(live, ride)
                            DataRow(tm, f, v, f.level(v, live), pal, col == 1, Modifier.size((ROW_W * kk).dp, (ROW_H * kk).dp)) { picking = i }
                            if (col == 0) Spacer(Modifier.width((20 * kk).dp))
                        }
                    }
                }
                Canvas(Modifier.padding(top = (8 * kk).dp).size((412 * kk).dp, (30 * kk).dp).clickable { faultOpen = true }) {
                    val sx = size.width / 412f
                    withTransform({ scale(sx, sx, Offset.Zero) }) { sysRow(tm, live, ride, pal) }
                }
                Canvas(Modifier.padding(top = (8 * kk).dp).size((412 * kk).dp, (24 * kk).dp)
                    .then(if (lastHealth != null) Modifier.clickable { repo.openRequest.value = Repo.OPEN_HEALTH } else Modifier)) {
                    val sx = size.width / 412f
                    withTransform({ scale(sx, sx, Offset.Zero) }) { rideRow(tm, rec != null, ride, pal, lastHealth?.report?.score) }
                }
                if (nudge) Row(
                    Modifier.padding(start = (16 * kk).dp, top = (8 * kk).dp).size((380 * kk).dp, (40 * kk).dp)
                        .background(pal.tile, androidx.compose.foundation.shape.RoundedCornerShape((8 * kk).dp)).padding(start = (12 * kk).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.ov_nudge), color = pal.caution, fontSize = (13 * kk).sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { repo.platform.requestOverlayPermission() }) { Text(stringResource(R.string.ov_allow), color = RxColors.brandInk, fontSize = (13 * kk).sp) }
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
        FieldPicker(stringResource(R.string.pick_field_title), eight.getOrNull(i),
            onPick = { repo.setMonEight(i, it); picking = null }, onDismiss = { picking = null })
    }
    if (mapOpen) MapWindow { mapOpen = false }
    if (faultOpen) FaultWindow { faultOpen = false }
}

/**
 * 油圖視窗：目前油圖、這次連線的切換紀錄（不是這支手機切的用紅字），以及「解鎖切換」。
 * 解鎖只在這個視窗開著時有效、不存檔；切完一次或關掉視窗就鎖回去。
 */
@Composable
private fun MapWindow(onDismiss: () -> Unit) {
    val repo = LocalRepo.current
    val now by repo.mapNow.collectAsState()
    val events by repo.mapEvents.collectAsState()
    var unlocked by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        repo.ackMapAlert() // 打開視窗就算看過了
        onDispose { repo.relock() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RxColors.card,
        title = { Text(stringResource(R.string.map_win_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.map_win_now), color = RxColors.muted, modifier = Modifier.padding(end = 10.dp))
                    Box(
                        Modifier.size(84.dp, 56.dp).background(now?.let { mapFill(it) } ?: RxColors.track, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text(now?.let { "MAP $it" } ?: "—", style = digits(22.sp, FontWeight.ExtraBold), color = if (now != null) Inst.bg else RxColors.muted) }
                    if (now == null) Text(stringResource(R.string.map_win_unknown), color = RxColors.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
                }
                Spacer(Modifier.height(14.dp))
                if (!unlocked) {
                    YzButton(stringResource(R.string.map_win_unlock), Modifier.fillMaxWidth()) { unlocked = true; repo.unlockOnce() }
                } else {
                    Text(stringResource(R.string.map_win_pick), fontSize = 13.sp)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        (1..3).forEach { m ->
                            Box(
                                Modifier.size(64.dp, 52.dp).background(if (now == m) mapFill(m) else RxColors.track, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                    .clickable { repo.setMap(m); unlocked = false; repo.relock() },
                                contentAlignment = Alignment.Center,
                            ) { Text("$m", style = digits(26.sp, FontWeight.ExtraBold), color = if (now == m) Inst.bg else RxColors.text) }
                        }
                    }
                }
                Text(stringResource(R.string.map_win_note), color = RxColors.muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.map_win_log), fontWeight = FontWeight.Bold)
                if (events.isEmpty()) Text(stringResource(R.string.map_log_empty), color = RxColors.muted, fontSize = 13.sp)
                events.reversed().forEach { e ->
                    Text(
                        stringResource(R.string.map_log_row, WallClock.format(e.wallMs, "HH:mm:ss"), e.from, e.to,
                            stringResource(if (e.local) R.string.map_log_local else R.string.map_log_remote), e.rpm?.toInt() ?: 0, e.speed?.toInt() ?: 0),
                        color = if (e.local) RxColors.muted else RxColors.danger, fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** 故障碼視窗：現在亮著的、這次連線出現過的。沒連線時不說「沒有故障」，避免讓人誤以為沒事。 */
@Composable
private fun FaultWindow(onDismiss: () -> Unit) {
    val repo = LocalRepo.current
    val live by repo.live.collectAsState()
    val seen by repo.malfSeen.collectAsState()
    val state by repo.state.collectAsState()
    // 斷線或資料停了：不顯示「沒有故障」，避免拿舊資料讓人以為沒事
    val l = live?.takeIf { state == SessionState.Streaming && (repo.platform.elapsedNanos() - it.tNanos) < 3_000_000_000L }
    val active = l?.layout?.activeMalfunctions(l.raw).orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RxColors.card,
        title = { Text(stringResource(R.string.fault_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.malf_now), fontWeight = FontWeight.Bold)
                when {
                    l == null -> Text(stringResource(R.string.fault_no_data), color = RxColors.muted)
                    active.isEmpty() -> Text(stringResource(R.string.malf_none), color = RxColors.accent)
                    else -> active.forEach { d -> Text("● ${d.name}", color = RxColors.danger, modifier = Modifier.padding(vertical = 2.dp)) }
                }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.malf_seen, seen.size), fontWeight = FontWeight.Bold)
                if (seen.isEmpty()) Text("—", color = RxColors.muted)
                seen.entries.sortedBy { it.value }.forEach { (n, t) ->
                    Text(stringResource(R.string.malf_first, n, WallClock.format(t, "HH:mm:ss")), color = RxColors.warn, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                }
                Text(stringResource(R.string.malf_hint), color = RxColors.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

private const val ROW_W = (412f - 40 - 20) / 2
private const val ROW_H = 56f

/** 一列數據：名稱＋數字（−10° 斜）＋細量表（刻度、紅區、亮點）。右欄左右對調。長按換欄位；警示時變色，不閃。 */
@Composable
private fun DataRow(tm: TextMeasurer, f: MonField, v: Double?, lv: Lv, pal: PagePal, mirror: Boolean, modifier: Modifier, onLongPress: () -> Unit) {
    val label = stringResource(f.label)
    Canvas(modifier.pointerInput(f) { detectTapGestures(onLongPress = { onLongPress() }) }) {
        val W = ROW_W; val s = size.width / W
        withTransform({ scale(s, s, Offset.Zero) }) {
            val col = pal.of(lv)
            val lc = if (lv == Lv.OK) pal.label else col
            val text = f.text(v)
            val unit = if (f.unit.isNotEmpty() && v != null) f.unit else ""
            val lw = textWidth(tm, label, 13f) + 8f
            val uw = if (unit.isEmpty()) 0f else textWidth(tm, unit, 12f) + 4f
            val w0 = textWidth(tm, text, 27f, FontWeight.ExtraBold, digits = true)
            val fs = 27f * minOf(1f, (W - lw - uw) / w0)
            val w = w0 * fs / 27f
            if (mirror) {
                txt(tm, text, 0f, 24f, fs, col, FontWeight.ExtraBold, digits = true, skew = true, spacing = -.02f)
                if (unit.isNotEmpty()) txt(tm, unit, w + 4f, 24f, 12f, lc)
                txt(tm, (if (lv != Lv.OK) "◆ " else "") + label, W, 22f, 13f, lc, anchor = 'r')
            } else {
                txt(tm, (if (lv != Lv.OK) "◆ " else "") + label, 0f, 22f, 13f, lc)
                txt(tm, text, W - uw - w, 24f, fs, col, FontWeight.ExtraBold, digits = true, skew = true, spacing = -.02f)
                if (unit.isNotEmpty()) txt(tm, unit, W, 24f, 12f, lc, anchor = 'r')
            }
            // 細量表
            val by = 36f
            val track = if (pal.night) Color.White.copy(alpha = .10f) else Inst.mocha.copy(alpha = .30f)
            drawLine(track, Offset(0f, by), Offset(W, by), 3f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            f.redFrom?.let { drawLine(Inst.red.copy(alpha = .6f), Offset(W * f.frac(it), by), Offset(W, by), 3f, cap = androidx.compose.ui.graphics.StrokeCap.Round) }
            for (k in 0..4) drawLine(pal.label.copy(alpha = .6f), Offset(W * k / 4f, by + 4f), Offset(W * k / 4f, by + 7f), 1f)
            if (v != null) {
                val x = W * f.frac(v)
                val fill = if (lv != Lv.OK) col else if (pal.night) Inst.rose else Inst.roseLine
                drawLine(fill, Offset(0f, by), Offset(x, by), 3f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawCircle(fill.copy(alpha = .35f), 6f, Offset(x, by))
                drawCircle(if (pal.night) Color.White else Inst.ink, 3f, Offset(x, by))
            }
        }
    }
}

private const val TILE_W = (412f - 32 - 16) / 3
private const val TILE_H = 84f

/** 一格數值：標籤、數值（−10° 斜）、單位；長按換欄位。警示時變色，不閃。 */
@Composable
private fun Tile(tm: TextMeasurer, f: MonField, v: Double?, lv: Lv, live: Live?, pal: PagePal, modifier: Modifier, onLongPress: () -> Unit) {
    val label = stringResource(f.label)
    Canvas(modifier.pointerInput(f) { detectTapGestures(onLongPress = { onLongPress() }) }) {
        val W = TILE_W; val H = TILE_H
        val s = size.width / W
        withTransform({ scale(s, s, Offset.Zero) }) {
            val p = chamPath(0f, 0f, W, H, 9f)
            drawPath(p, pal.tile)
            if (pal.night) {
                drawLine(Color.White.copy(alpha = .05f), Offset(9f, 1f), Offset(W, 1f), 2f)
                drawLine(Color.Black.copy(alpha = .5f), Offset(0f, H - 1), Offset(W - 9f, H - 1), 2f)
            } else drawLine(Inst.mocha.copy(alpha = .3f), Offset(0f, H - 1), Offset(W - 9f, H - 1), 2f)
            if (lv != Lv.OK) drawPath(chamPath(.75f, .75f, W - 1.5f, H - 1.5f, 8.7f), pal.of(lv), style = Stroke(if (lv == Lv.FAULT) 2.5f else 1.5f))
            val lc = if (lv == Lv.OK) pal.label else pal.of(lv)
            txt(tm, (if (lv != Lv.OK) "◆ " else "") + label, 10f, 24f, 13f, lc)
            val text = f.text(v)
            // 數字太長（例如 12,000）就縮一點，不要撞到單位
            val size0 = 32f
            val w0 = textWidth(tm, text, size0, FontWeight.ExtraBold, digits = true)
            val unit = if (f.unit.isNotEmpty() && v != null) f.unit else ""
            val uw = if (unit.isEmpty()) 0f else textWidth(tm, unit, 12f) + 5f
            val fs = size0 * minOf(1f, (W - 20f - uw) / w0)
            val w = txt(tm, text, 10f, 68f, fs, pal.of(lv), FontWeight.ExtraBold, digits = true, skew = true, spacing = -.02f)
            if (unit.isNotEmpty()) txt(tm, unit, 10f + w + 5f, 68f, 12f, pal.label)
        }
    }
}

private fun DrawScope.tpsRow(tm: TextMeasurer, l: Live?, pal: PagePal) {
    val tps = l?.v(Ch.TPS)
    val w = txt(tm, Strings.get(R.string.f_tps_long), 16f, 26f, 13f, pal.label)
    if (tps != null && tps >= 95) {
        val text = Strings.get(R.string.wot)
        val cw = textWidth(tm, text, 13f) + 22
        drawPath(paraPath(16 + w + 12, 12f, cw, 20f), if (pal.night) Inst.sakura else Inst.roseLine)
        txt(tm, text, 16 + w + 12 + cw / 2, 26.5f, 13f, if (pal.night) Inst.bg else Color.White, anchor = 'c')
    }
    val pw = txt(tm, "%", 396f, 26f, 13f, pal.label, anchor = 'r')
    txt(tm, tps?.let { "${kotlin.math.round(it).toInt()}" } ?: "—", 396f - pw - 4, 26f, 28f, pal.num, FontWeight.ExtraBold, digits = true, anchor = 'r')
    // 20 格分段條（像 90 年代的液晶轉速條），沒亮的格子也淡淡畫出來，看得到全刻度
    val n = 20; val gap = 3f; val bw = (380f - gap * (n - 1)) / n
    val lit = if (tps == null) 0 else kotlin.math.ceil((tps / 100).coerceIn(0.0, 1.0) * n - 0.001).toInt()
    for (i in 0 until n) {
        val x = 16f + i * (bw + gap)
        drawPath(paraPath(x, 34f, bw, 8f), if (i < lit) pal.bar else pal.track)
    }
}

/** 系統狀態：一排「檢查合格貼紙」。合格＝深底細框＋水色 ✓；注意＝玫瑰底；危險＝紅底，打 ✕。 */
private fun DrawScope.sysRow(tm: TextMeasurer, l: Live?, ride: RideStats, pal: PagePal) {
    fun lv(vararg f: MonField) = f.maxOfOrNull { it.level(it.value(l, ride), l).ordinal }?.let { Lv.entries[it] } ?: Lv.OK
    val crank = if ((l?.v(72) ?: 0.0) > 0) Lv.CAUTION else Lv.OK
    val items = listOf(R.string.health_a_cooling to lv(MonField.TEMP), R.string.health_a_fuel to lv(MonField.AFR, MonField.TRIM),
        R.string.health_a_electrical to lv(MonField.VOLT), R.string.health_a_ignition to crank,
        R.string.health_a_faults to lv(MonField.FAULTS))
    val gap = 6f; val H = 28f
    val labels = items.map { Strings.get(it.first) }
    val natural = labels.map { textWidth(tm, it, 13f) + 30f }
    val k = (380f - gap * (items.size - 1)) / natural.sum()
    var x = 16f
    items.forEachIndexed { i, (_, v) ->
        val w = natural[i] * k
        val p = chamPath(x, 1f, w, H - 2, 6f)
        val fg = when (v) {
            Lv.FAULT -> { drawPath(p, pal.fault); Color.White }
            Lv.CAUTION -> { drawPath(p, pal.caution); Inst.ink }
            Lv.OK -> { drawPath(p, pal.tile); drawPath(p, pal.chipStroke, style = Stroke(1f)); pal.label }
        }
        val mark = if (v == Lv.OK) "✓" else "✕"
        val mw = txt(tm, mark, x + 8f, 20f, 13f, if (v == Lv.OK) pal.bar else fg, FontWeight.Black)
        txt(tm, labels[i], x + 8f + mw + 4f, 20f, 13f, fg, FontWeight.Bold, alpha = if (v == Lv.OK) .9f else 1f)
        x += w + gap
    }
}

private fun DrawScope.rideRow(tm: TextMeasurer, recording: Boolean, ride: RideStats, pal: PagePal, lastScore: Int?) {
    drawCircle(if (recording) pal.rec else Inst.mocha.copy(alpha = .5f), 3.5f, Offset(20f, 13f))
    var x = 30f
    fun seg(t: String, num: Boolean) { x += txt(tm, t, x, 18f, 13f, if (num) pal.num else pal.label, if (num) FontWeight.SemiBold else FontWeight.Medium, digits = num) + 5 }
    if (!recording && lastScore != null) { txt(tm, Strings.get(R.string.ride_last_health, lastScore), x, 18f, 13f, pal.num, FontWeight.SemiBold); return }
    if (!recording) { seg(Strings.get(R.string.ride_not_recording), false); return }
    val mins = ((WallClock.nowMs() - ride.startMs) / 60_000).coerceAtLeast(0)
    x += txt(tm, Strings.get(R.string.ride_recording), x, 18f, 13f, pal.rec, FontWeight.Bold) + 8
    seg("${mins / 60}:${(mins % 60).toString().padStart(2, '0')}", true); seg("·", false)
    seg(Fmt.fixed(ride.km, 1), true); seg("km", false); seg("·", false)
    seg(Strings.get(R.string.ride_max), false); seg(rpmText(ride.maxRpm), true); seg("RPM", false)
}

/** 「油圖被切換、不是從這支手機切的」提醒條（注意等級：玫瑰外框，不用紅色）。 */
@Composable
internal fun MapAlertBar(ev: Repo.MapEvent, pal: PagePal, k: Float, onOpen: () -> Unit, onAck: () -> Unit) {
    val tm = rememberTextMeasurer()
    Box(Modifier.padding(start = (16 * k).dp, top = (8 * k).dp).size((380 * k).dp, (40 * k).dp).clickable(onClick = onOpen)) {
        Canvas(Modifier.fillMaxSize()) {
            val s = size.width / 380f
            withTransform({ scale(s, s, Offset.Zero) }) {
                val p = chamPath(0f, 0f, 380f, 40f, 10f)
                drawPath(p, pal.tile)
                // 上下緣粉白斜紋（警示條，不用黃黑）
                clipPath(p) {
                    for (band in listOf(0f, 35f)) clipRect(0f, band, 380f, band + 5f) {
                        drawRect(if (pal.night) Inst.white else Inst.sakura, Offset(0f, band), Size(380f, 5f))
                        var sx = -10f
                        while (sx < 390f) { drawPath(paraPath(sx, band, 6f, 5f), pal.caution); sx += 12f }
                    }
                }
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
