package io.github.xeno_hf.rex.ui

import io.github.xeno_hf.rex.res.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xeno_hf.rex.Fs
import io.github.xeno_hf.rex.SessionDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rx.defs.Loga
import rx.protocol.Fmt
import rx.session.EngineHealth
import rx.session.EngineHealth.Area
import rx.session.EngineHealth.Level

/**
 * 引擎健檢頁：分析一份錄製紀錄（decoded.loga），用儀表板排版顯示總分、各系統、關鍵數值、走勢與發現。
 * 分析在背景執行緒跑（[EngineHealth.analyze]），不連網。
 */
@Composable
fun HealthScreen(session: SessionDir, onClose: () -> Unit) {
    var report by remember(session.dir) { mutableStateOf<EngineHealth.Report?>(null) }
    var error by remember(session.dir) { mutableStateOf<String?>(null) }
    val repo = LocalRepo.current
    LaunchedEffect(session.dir) {
        // 分析並存檔（健檢清單、AI 都讀這份）
        runCatching { repo.runHealth(session.dir, notify = false) ?: error("no data") }
            .onSuccess { report = it }.onFailure { error = it.message ?: it.toString() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹ " + stringResource(R.string.health_back)) }
            Text(stringResource(R.string.health_title), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.weight(1f))
            Text(rideTitle(session.dir.name), color = RxColors.muted, fontSize = 12.sp)
        }
        val r = report
        val e = error
        when {
            e != null -> Text(stringResource(R.string.health_error, e), color = RxColors.danger, modifier = Modifier.padding(12.dp))
            r == null -> Text(stringResource(R.string.health_loading), color = RxColors.muted, modifier = Modifier.padding(12.dp))
            else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                AiPanel(session.dir)
                HealthReportBody(r)
            }
        }
    }
}

/** AI 解讀：有存過就直接顯示，沒有就給一顆按鈕（要在設定填 API 金鑰）。 */
@Composable
private fun AiPanel(dir: kotlinx.io.files.Path) {
    val repo = LocalRepo.current
    if (repo.platform.ai == null) return
    val history by repo.healthHistory.collectAsState()
    val saved = history.firstOrNull { it.dir == dir }?.ai
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember(dir) { mutableStateOf(false) }
    var err by remember(dir) { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).background(RxColors.card, YzCardShape)
            .border(1.5.dp, Yz.rose.copy(alpha = .7f), YzCardShape).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai_title, repo.aiProvider.collectAsState().value.label), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            androidx.compose.material3.OutlinedButton(enabled = !busy, onClick = {
                busy = true; err = null
                scope.launch { repo.aiExplain(dir).onFailure { err = it.message ?: it.toString() }; busy = false }
            }) { Text(stringResource(if (busy) R.string.ai_busy else if (saved == null) R.string.ai_run else R.string.ai_rerun)) }
        }
        saved?.let { Text(aiRich(it), fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp)) }
        err?.let { Text(it, color = RxColors.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        Text(stringResource(R.string.ai_privacy), color = RxColors.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * AI 回覆有時會帶 Markdown（**粗體**、# 標題）：轉成真的粗體，符號不顯示。
 */
internal fun aiRich(text: String): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    text.lines().forEachIndexed { i, raw ->
        if (i > 0) append('\n')
        val heading = Regex("^#{1,6}\\s+").find(raw)
        val line = if (heading != null) raw.substring(heading.range.last + 1) else raw
        val bold = androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)
        if (heading != null) { withStyle(bold) { append(line.replace("**", "")) }; return@forEachIndexed }
        val parts = line.split("**")
        parts.forEachIndexed { k, p -> if (k % 2 == 1 && k < parts.lastIndex + (if (parts.size % 2 == 1) 1 else 0)) withStyle(bold) { append(p) } else append(p) }
    }
}

/** AI 回覆的純文字版（卡片摘要用）：拿掉 Markdown 符號。 */
internal fun aiPlain(text: String): String = text.replace("**", "").replace(Regex("(?m)^#{1,6}\\s+"), "")

/** 健檢趨勢：最近 8 趟的分數，一趟一根分段柱（像液晶條）；80 分以下用玫瑰色板標出來。點柱子看那一趟的報告。 */
@Composable
fun HealthTrend(onOpen: (kotlinx.io.files.Path) -> Unit) {
    val repo = LocalRepo.current
    val history by repo.healthHistory.collectAsState()
    if (history.isEmpty()) return
    val recent = history.take(8).reversed()
    val light = RxColors.palette.light
    YzSectionHeader(1, stringResource(R.string.trend_title, history.size))
    Column(Modifier.fillMaxWidth().background(RxColors.card, YzCardShape).padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(150.dp)) {
            recent.forEachIndexed { i, e ->
                val low = e.report.score < 80
                val latest = i == recent.lastIndex
                Column(Modifier.weight(1f).fillMaxHeight().clickable { onOpen(e.dir) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${e.report.score}", style = digits(15.sp, FontWeight.ExtraBold),
                        color = if (low) Yz.ink else if (light) RxColors.text else Yz.pastel,
                        modifier = if (low) Modifier.background(Yz.rose, SlantShape).padding(horizontal = 5.dp) else Modifier,
                    )
                    val lit = if (low) Yz.rose else if (light) Color(0xFF1F0E16) else if (latest) Yz.pastel else Yz.petal
                    val ghost = (if (light) Color(0xFFFEDFE1) else Yz.petal).copy(alpha = if (light) .45f else .13f)
                    Canvas(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp)) {
                        val n = 14; val gap = 2.dp.toPx(); val bh = (size.height - gap * (n - 1)) / n
                        val on = kotlin.math.round(e.report.score / 100f * n).toInt()
                        for (k in 0 until n) {
                            val y = size.height - (k + 1) * bh - k * gap
                            drawRect(if (k < on) lit else ghost, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, bh))
                        }
                    }
                    Text(rideDay(e.dir.name), fontSize = 10.sp, color = if (latest) RxColors.text else RxColors.muted, fontWeight = if (latest) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}

/** 報告本體（總分、關鍵數值、走勢、發現）。跟讀檔分開，桌面預覽可以直接餵一份報告進來畫。 */
@Composable
internal fun HealthReportBody(r: EngineHealth.Report, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScorePanel(r)
        MetricsPanel(r)
        if (r.series.isNotEmpty()) TrendsPanel(r)
        FindingsPanel(r)
        Text(stringResource(R.string.health_disclaimer), color = RxColors.muted, fontSize = 11.sp, lineHeight = 15.sp)
        Spacer(Modifier.height(16.dp))
    }
}

internal fun levelColor(l: Level): Color = when (l) {
    Level.OK -> RxColors.accent
    Level.INFO -> RxColors.muted
    Level.WARN -> RxColors.warn
    Level.DANGER -> RxColors.danger
}

private fun levelLabel(l: Level): Int = when (l) {
    Level.OK -> R.string.health_level_ok
    Level.INFO -> R.string.health_level_info
    Level.WARN -> R.string.health_level_warn
    Level.DANGER -> R.string.health_level_danger
}

private fun areaLabel(a: Area): Int = when (a) {
    Area.COOLING -> R.string.health_a_cooling
    Area.FUEL -> R.string.health_a_fuel
    Area.ELECTRICAL -> R.string.health_a_electrical
    Area.IDLE -> R.string.health_a_idle
    Area.IGNITION -> R.string.health_a_ignition
    Area.FAULTS -> R.string.health_a_faults
}

private fun metricLabel(key: String): Int? = when (key) {
    "t_eng_max" -> R.string.health_m_t_eng_max
    "volt_charge" -> R.string.health_m_volt_charge
    "afr_dev" -> R.string.health_m_afr_dev
    "afr_wot" -> R.string.health_m_afr_wot
    "fuel_cl" -> R.string.health_m_fuel_cl
    "inj_duty" -> R.string.health_m_inj_duty
    "idle_rpm" -> R.string.health_m_idle_rpm
    "idle_std" -> R.string.health_m_idle_std
    "miss_crk" -> R.string.health_m_miss_crk
    "fault_count" -> R.string.health_m_fault_count
    else -> null
}

/** 一條發現的完整文字（通知、AI 提示詞用）。 */
fun findingString(f: EngineHealth.Finding): String = findingText(f.code)?.let { Strings.get(it, *f.args.toTypedArray()) } ?: (f.code + " " + f.args.joinToString(" "))

/** 關鍵數值的名稱。 */
fun metricString(key: String): String = metricLabel(key)?.let { Strings.get(it) } ?: key

private fun findingText(code: String): Int? = when (code) {
    "temp_warn" -> R.string.health_f_temp_warn
    "temp_danger" -> R.string.health_f_temp_danger
    "temp_cold" -> R.string.health_f_temp_cold
    "overheat_flag" -> R.string.health_f_overheat_flag
    "volt_low_warn" -> R.string.health_f_volt_low_warn
    "volt_low_danger" -> R.string.health_f_volt_low_danger
    "volt_high_warn" -> R.string.health_f_volt_high_warn
    "volt_high_danger" -> R.string.health_f_volt_high_danger
    "volt_dip" -> R.string.health_f_volt_dip
    "afr_lean" -> R.string.health_f_afr_lean
    "afr_rich" -> R.string.health_f_afr_rich
    "wot_lean" -> R.string.health_f_wot_lean
    "trim_add" -> R.string.health_f_trim_add
    "trim_cut" -> R.string.health_f_trim_cut
    "inj_duty" -> R.string.health_f_inj_duty
    "idle_unstable" -> R.string.health_f_idle_unstable
    "idle_offset" -> R.string.health_f_idle_offset
    "miss_crk" -> R.string.health_f_miss_crk
    "fault_codes" -> R.string.health_f_fault_codes
    "short_log" -> R.string.health_f_short_log
    else -> null
}

/** 儀表板面板：白卡片＋細框＋左上小標。 */
@Composable
private fun Panel(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().background(RxColors.card, YzCardShape).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(RxColors.brand, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(title.uppercase(), color = RxColors.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun ScorePanel(r: EngineHealth.Report) {
    val c = levelColor(r.level)
    Panel(stringResource(R.string.health_score)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(118.dp)) {
                Text(r.score.toString(), fontFamily = Digits, fontWeight = FontWeight.ExtraBold, fontSize = 60.sp, color = c, lineHeight = 60.sp)
                Text(
                    stringResource(levelLabel(r.level)), color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(c.copy(alpha = 0.14f), androidx.compose.foundation.shape.CutCornerShape(bottomEnd = 5.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                r.areas.forEach { a -> AreaBar(a) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.health_summary, Fmt.fixed(r.runningSec / 60.0, 1), Fmt.grouped(r.samples.toLong())),
            color = RxColors.muted, fontSize = 11.sp,
        )
    }
}

@Composable
private fun AreaBar(a: EngineHealth.AreaScore) {
    val c = if (a.checked) levelColor(a.level) else RxColors.line
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(areaLabel(a.area)), fontSize = 11.sp, modifier = Modifier.width(64.dp), maxLines = 1)
        Box(Modifier.weight(1f).height(7.dp).clip(SlantShape).background(RxColors.track)) {
            if (a.checked) Box(Modifier.fillMaxWidth(a.score / 100f).height(7.dp).background(c))
        }
        Text(
            if (a.checked) a.score.toString() else stringResource(R.string.health_not_checked),
            fontFamily = if (a.checked) Digits else FontFamily.Default, fontSize = if (a.checked) 12.sp else 9.sp,
            color = if (a.checked) c else RxColors.muted, modifier = Modifier.width(44.dp).padding(start = 6.dp), maxLines = 1,
        )
    }
}

@Composable
private fun MetricsPanel(r: EngineHealth.Report) {
    Panel(stringResource(R.string.health_metrics)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            r.metrics.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pair.forEach { m -> MetricTile(m, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricTile(m: EngineHealth.Metric, modifier: Modifier) {
    val v = m.value
    val c = if (v == null) RxColors.muted else if (m.level == Level.OK) RxColors.text else levelColor(m.level)
    Row(modifier.background(RxColors.cardHi.copy(alpha = 0.55f), androidx.compose.foundation.shape.CutCornerShape(bottomEnd = 6.dp)).height(50.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(50.dp).background(if (v == null) RxColors.line else levelColor(m.level), SlantShape))
        Column(Modifier.padding(horizontal = 8.dp)) {
            Text(metricLabel(m.key)?.let { stringResource(it) } ?: m.key, color = RxColors.muted, fontSize = 10.sp, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (v == null) "—" else (if (m.key == "afr_dev" || m.key == "fuel_cl") signed(v, m.decimals) else Fmt.fixed(v, m.decimals)),
                    fontFamily = Digits, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = c, lineHeight = 22.sp,
                )
                if (v != null && m.unit.isNotEmpty()) Text(" " + m.unit, color = RxColors.muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

/** 帶正負號的數字；四捨五入後是零就不帶號（不顯示 -0.00）。 */
private fun signed(v: Double, decimals: Int): String {
    val t = Fmt.fixed(v, decimals).removePrefix("-")
    if (t.all { it == '0' || it == '.' }) return t
    return (if (v > 0) "+" else "-") + t
}

@Composable
private fun TrendsPanel(r: EngineHealth.Report) {
    Panel(stringResource(R.string.health_trends)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            r.series.entries.forEachIndexed { k, (name, data) ->
                val color = RxColors.traces[k % RxColors.traces.size]
                val lo = data.minOrNull() ?: 0f
                val hi = data.maxOrNull() ?: 0f
                val dec = if (hi - lo < 20f && hi < 100f) 1 else 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(92.dp)) {
                        Text(name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
                        Text("${Fmt.fixed(lo.toDouble(), dec)} – ${Fmt.fixed(hi.toDouble(), dec)}", fontFamily = Digits, fontSize = 10.sp, color = RxColors.muted, maxLines = 1)
                    }
                    Sparkline(data, lo, hi, color, Modifier.weight(1f).height(34.dp))
                }
            }
        }
    }
}

@Composable
private fun Sparkline(data: FloatArray, lo: Float, hi: Float, color: Color, modifier: Modifier) {
    val grid = RxColors.track
    Canvas(modifier) {
        drawLine(grid, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
        if (data.size < 2) return@Canvas
        val span = (hi - lo).takeIf { it > 1e-6f } ?: 1f
        val pad = 2.dp.toPx()
        val h = size.height - pad * 2
        val p = Path()
        data.forEachIndexed { i, v ->
            val x = size.width * i / (data.size - 1)
            val y = pad + h * (1f - (v - lo) / span)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun FindingsPanel(r: EngineHealth.Report) {
    Panel(stringResource(R.string.health_findings)) {
        if (r.findings.isEmpty()) {
            Text(stringResource(R.string.health_none), color = RxColors.accent, fontSize = 13.sp)
            return@Panel
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            r.findings.forEach { f ->
                val c = levelColor(f.level)
                Row {
                    Box(Modifier.padding(top = 5.dp).size(8.dp).background(c, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(areaLabel(f.area)), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RxColors.muted)
                            Spacer(Modifier.width(6.dp))
                            Badge(stringResource(levelLabel(f.level)), c)
                        }
                        Text(findingText(f.code)?.let { stringResource(it, *f.args.toTypedArray()) } ?: (f.code + " " + f.args.joinToString(" ")), fontSize = 13.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}
