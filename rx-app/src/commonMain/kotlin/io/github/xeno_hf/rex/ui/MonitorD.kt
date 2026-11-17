package io.github.xeno_hf.rex.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xeno_hf.rex.Live
import io.github.xeno_hf.rex.Repo
import io.github.xeno_hf.rex.res.R
import rx.protocol.Fmt
import rx.session.WallClock

/**
 * D「資料錶」：乾淨版。第一頁只有三樣主角（轉速環、油圖號碼、車速）加六格大數字和一條狀態列；
 * 往左滑是「全部數據」。沒有數據以外的裝飾；格子超出範圍才變色（注意＝玫瑰、危險＝紅）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MonitorD(
    frame: State<DialData?>, live: Live?, ride: RideStats, six: List<MonField>, pal: PagePal, night: Boolean,
    mapAlert: Repo.MapEvent?, recording: Boolean, land: Boolean,
    onMap: () -> Unit, onFault: () -> Unit, onAck: () -> Unit, onPick: (Int) -> Unit,
) {
    val pager = rememberPagerState { 2 }
    Column(Modifier.fillMaxSize().background(pal.page)) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            if (page == 0) PageMain(frame, live, ride, six, pal, night, mapAlert, recording, land, onMap, onFault, onAck, onPick)
            else PageAll(live, ride, pal)
        }
        // 頁碼點
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
            for (i in 0..1) Box(Modifier.padding(horizontal = 4.dp).size(if (pager.currentPage == i) 18.dp else 6.dp, 6.dp)
                .background(if (pager.currentPage == i) pal.num else pal.label.copy(alpha = .4f), CircleShape))
        }
    }
}

@Composable
private fun PageMain(
    frame: State<DialData?>, live: Live?, ride: RideStats, six: List<MonField>, pal: PagePal, night: Boolean,
    mapAlert: Repo.MapEvent?, recording: Boolean, land: Boolean,
    onMap: () -> Unit, onFault: () -> Unit, onAck: () -> Unit, onPick: (Int) -> Unit,
) {
    val shown = MonField.withOverride(six, live, ride)
    val tm = rememberTextMeasurer()
    val dial: @Composable (Modifier) -> Unit = { m ->
        Canvas(m.clickable(onClick = onMap)) {
            val d = frame.value ?: return@Canvas
            val sx = size.width / 412f
            withTransform({ scale(sx, sx, Offset.Zero) }) { raceDial(tm, d, pal, night) }
        }
    }
    val tiles: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0 until 3) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0 until 2) {
                    val i = row * 2 + col
                    shown.getOrNull(i)?.let { f -> DataTile(f, live, ride, pal, 36, Modifier.weight(1f).fillMaxSize()) { onPick(i) } }
                }
            }
        }
    }
    val status: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            if (mapAlert != null) MapAlertBar(mapAlert, pal, 1f, onOpen = onMap, onAck = onAck)
            else StatusLine(live, ride, pal, recording, onFault)
        }
    }
    if (land) Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).fillMaxSize()) { dial(Modifier.weight(1f).fillMaxWidth()); status() }
        tiles(Modifier.weight(1f).fillMaxSize())
    } else Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        dial(Modifier.fillMaxWidth().weight(0.9f))
        Spacer(Modifier.height(4.dp))
        tiles(Modifier.fillMaxWidth().weight(1.45f))
        Spacer(Modifier.height(6.dp))
        status()
    }
}

/** 一格：標籤＋大數字＋單位。超出範圍才變色。 */
@Composable
private fun DataTile(f: MonField, live: Live?, ride: RideStats, pal: PagePal, size: Int, modifier: Modifier, onLong: () -> Unit) {
    val v = f.value(live, ride)
    val lv = f.level(v, live)
    val bg = when (lv) { Lv.FAULT -> pal.fault; Lv.CAUTION -> pal.caution; Lv.OK -> pal.tile }
    val fg = when (lv) { Lv.FAULT -> Color.White; Lv.CAUTION -> Inst.ink; Lv.OK -> pal.num }
    val sub = when (lv) { Lv.OK -> pal.label; else -> fg }
    Column(
        modifier.background(bg, CutCornerShape(bottomEnd = 10.dp)).pointerInput(f) { detectTapGestures(onLongPress = { onLong() }) }.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(f.label), color = sub, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(f.text(v), color = fg, style = digits(size.sp, FontWeight.ExtraBold, italic = true), maxLines = 1)
            if (v != null && f.unit.isNotEmpty()) Text(" " + f.unit, color = sub, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp), maxLines = 1)
        }
    }
}

/** 底部一條狀態：沒事＝小字「✓ 一切正常」；有事＝整條玫瑰／紅色寫原因（點它看故障碼）。 */
@Composable
private fun StatusLine(live: Live?, ride: RideStats, pal: PagePal, recording: Boolean, onFault: () -> Unit) {
    val bad = MonField.entries.filter { it.watch }.map { it to it.level(it.value(live, ride), live) }
        .filter { it.second != Lv.OK }.maxByOrNull { it.second.ordinal }
    if (bad != null) {
        val (f, lv) = bad
        val v = f.value(live, ride)
        val bg = if (lv == Lv.FAULT) pal.fault else pal.caution
        val fg = if (lv == Lv.FAULT) Color.White else Inst.ink
        Row(Modifier.fillMaxWidth().height(44.dp).background(bg, CutCornerShape(bottomEnd = 10.dp)).clickable(onClick = onFault).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("◆ " + stringResource(f.label), color = fg, fontSize = 17.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            Text(f.text(v) + (if (f.unit.isNotEmpty()) " " + f.unit else ""), color = fg, style = digits(20.sp, FontWeight.ExtraBold))
        }
    } else Row(Modifier.fillMaxWidth().height(36.dp).clickable(onClick = onFault).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.yz_all_ok), color = pal.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (recording) {
            val mins = ((WallClock.nowMs() - ride.startMs) / 60_000).coerceAtLeast(0)
            Text("● " + stringResource(R.string.ride_recording) + "  ${mins / 60}:${(mins % 60).toString().padStart(2, '0')}  ${Fmt.fixed(ride.km, 1)} km", color = pal.rec, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 第二頁：全部數據，兩欄大數字，分「引擎」「行程」兩組。 */
@Composable
private fun PageAll(live: Live?, ride: RideStats, pal: PagePal) {
    val trip = setOf(MonField.TRIP, MonField.MAX_RPM, MonField.KML, MonField.KML_NOW, MonField.FAULTS)
    val groups = listOf(R.string.yz_grp_engine to MonField.entries.filter { it !in trip }, R.string.yz_grp_trip to MonField.entries.filter { it in trip })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Text(stringResource(R.string.yz_all_data), color = pal.num, fontSize = 20.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(vertical = 10.dp))
        groups.forEach { (title, list) ->
            Text(stringResource(title), color = pal.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
            list.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { f -> DataTile(f, live, ride, pal, 30, Modifier.weight(1f).height(84.dp)) {} }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** D 的錶：LED 轉速環＋正中央超大油圖號碼＋下方車速與轉速。設計座標 412×330。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.raceDial(tm: androidx.compose.ui.text.TextMeasurer, d: DialData, pal: PagePal, night: Boolean) {
    val cx = 206f; val cy = 160f
    ledRing(d, cx, cy, 138f, 156f, night, revColor = Inst.sakura, cap = true)
    txt(tm, "MAP", cx, 78f, 16f, pal.label, FontWeight.Bold, anchor = 'c', spacing = .1f)
    txt(tm, d.map?.toString() ?: "–", cx, 172f, 112f, pal.num, FontWeight.ExtraBold, digits = true, skew = true, anchor = 'c')
    txt(tm, d.speed?.toString() ?: "--", cx - 18f, 248f, 64f, pal.num, FontWeight.ExtraBold, digits = true, skew = true, anchor = 'c')
    txt(tm, "km/h", cx + 52f, 248f, 16f, pal.label, anchor = 'l', spacing = .06f)
    if (d.connected) {
        val s = rpmText(d.rpm)
        txt(tm, s, cx - 10f, 286f, 26f, pal.num, FontWeight.Bold, digits = true, anchor = 'c')
        txt(tm, "rpm", cx + 40f, 286f, 14f, pal.label, anchor = 'l')
    }
}
