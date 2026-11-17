package io.github.xeno_hf.rex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xeno_hf.rex.AiProvider
import io.github.xeno_hf.rex.Fs
import io.github.xeno_hf.rex.Repo
import io.github.xeno_hf.rex.SessionDir
import io.github.xeno_hf.rex.res.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import rx.session.WallClock

/*
 * 夜櫻的「健檢」與「設定」分頁，以及共用的小元件。
 */

// ---------------- 共用 ----------------

/** 手機返回鍵／返回手勢（Android 用系統的返回；其他平台沒有返回鍵，不做事）。 */
@Composable
internal expect fun YzBack(enabled: Boolean = true, onBack: () -> Unit)

/** 一個設定區塊：編號路牌標題＋切角卡片。 */
@Composable
internal fun YzSection(index: Int?, title: String, content: @Composable ColumnScope.() -> Unit) {
    YzSectionHeader(index, title)
    YzCard(content = content)
}

@Composable
internal fun YzSwitchRow(text: String, checked: Boolean, divider: Boolean = false, onChange: (Boolean) -> Unit) {
    YzRow(text, divider) { YzSwitch(checked, onChange) }
}

/** 資料夾名 → 「11/16」（趨勢圖下的日期）。 */
internal fun rideDay(folder: String): String =
    Regex("""^[A-Za-z]+_\d{4}(\d{2})(\d{2})_""").find(folder)?.let { "${it.groupValues[1]}/${it.groupValues[2]}" } ?: ""

/** 健檢分頁的車牌式日期牌：上排日期（不是今年加年份、同一分鐘第二趟加 #2），下排大字時間。 */
@Composable
internal fun YzDatePlate(folder: String) {
    val m = Regex("""^([A-Za-z]+)_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})\d{2}(?:_(\d+))?$""").find(folder)
    val top: String; val big: String
    if (m == null) { top = ""; big = folder } else {
        val (_, y, mo, d, h, mi, n) = m.destructured
        top = (if (y != WallClock.format(WallClock.nowMs(), "yyyy")) "$y/" else "") + "$mo/$d" + (if (n.isNotEmpty()) " #$n" else "")
        big = "$h:$mi"
    }
    val light = RxColors.palette.light
    Box(Modifier.background(if (light) Color.White else Color(0xFF170811)).border(1.5.dp, Yz.keyline.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 4.dp)) {
        // 車牌兩顆螺絲
        Box(Modifier.align(Alignment.TopStart).padding(top = 1.dp).size(3.dp).background(Yz.label, androidx.compose.foundation.shape.CircleShape))
        Box(Modifier.align(Alignment.TopEnd).padding(top = 1.dp).size(3.dp).background(Yz.label, androidx.compose.foundation.shape.CircleShape))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(top, color = if (light) Color(0xFFA8406A) else Yz.petal, style = digits(12.sp, FontWeight.Bold))
            Text(big, color = RxColors.text, style = digits(24.sp, FontWeight.ExtraBold))
        }
    }
}

/** 分數號碼牌（比賽號碼布）：好＝櫻粉、注意＝玫瑰、危險＝紅底白字。 */
@Composable
internal fun YzScoreBoard(score: Int, level: rx.session.EngineHealth.Level) {
    val (bg, fg) = when (level) {
        rx.session.EngineHealth.Level.DANGER -> Yz.danger to Color.White
        rx.session.EngineHealth.Level.WARN -> Yz.rose to Yz.ink
        else -> Yz.pastel to Yz.ink
    }
    // 比賽號碼布：右下一塊硬陰影
    Box {
        Box(Modifier.matchParentSize().padding(start = 4.dp, top = 4.dp).background(if (level == rx.session.EngineHealth.Level.OK) Yz.rose else Yz.ink))
        Box(Modifier.padding(end = 4.dp, bottom = 4.dp).background(bg).border(1.5.dp, Yz.ink).padding(horizontal = 10.dp, vertical = 2.dp)) {
            Text("$score", color = fg, style = digits(30.sp, FontWeight.ExtraBold))
        }
    }
}

/** 各系統的「檢查合格貼紙」：✓ 合格、✕ 有問題（玫瑰／紅）。 */
@Composable
internal fun YzPassChip(label: String, level: rx.session.EngineHealth.Level) {
    val bad = level == rx.session.EngineHealth.Level.WARN || level == rx.session.EngineHealth.Level.DANGER
    val fill = when (level) { rx.session.EngineHealth.Level.DANGER -> Yz.danger; rx.session.EngineHealth.Level.WARN -> Yz.rose; else -> null }
    val shape = CutCornerShape(bottomEnd = 6.dp)
    Row(
        Modifier.then(if (fill != null) Modifier.background(fill, shape) else Modifier.border(1.dp, RxColors.line, shape)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (bad) "✕" else "✓", color = if (fill != null) (if (level == rx.session.EngineHealth.Level.DANGER) Color.White else Yz.ink) else if (RxColors.palette.light) Color(0xFFA8406A) else Yz.petal, fontSize = 12.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(4.dp))
        Text(label, color = if (fill != null) (if (level == rx.session.EngineHealth.Level.DANGER) Color.White else Yz.ink) else RxColors.text.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private fun areaLabel(a: rx.session.EngineHealth.Area): Int? = when (a) {
    rx.session.EngineHealth.Area.COOLING -> R.string.health_a_cooling
    rx.session.EngineHealth.Area.FUEL -> R.string.health_a_fuel
    rx.session.EngineHealth.Area.ELECTRICAL -> R.string.health_a_electrical
    rx.session.EngineHealth.Area.IGNITION -> R.string.health_a_ignition
    rx.session.EngineHealth.Area.FAULTS -> R.string.health_a_faults
    rx.session.EngineHealth.Area.IDLE -> null
}

@Composable
internal fun YzHint(text: String) = Text(text, color = RxColors.muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))

@Composable
internal fun YzBackHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.yz_back), color = RxColors.brandInk) }
        Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

/** 紀錄資料夾名（ECU_20261116_083000[_2]）→ 看得懂的標題（11/16 08:30）；不是今年就加年份。 */
internal fun rideTitle(folder: String): String {
    val m = Regex("""^([A-Za-z]+)_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})\d{2}(?:_(\d+))?$""").find(folder) ?: return folder
    val (kind, y, mo, d, h, mi, n) = m.destructured
    val thisYear = WallClock.format(WallClock.nowMs(), "yyyy")
    val date = (if (y != thisYear) "$y/" else "") + "$mo/$d $h:$mi"
    val demo = if (kind.equals("DEMO", ignoreCase = true)) Strings.get(R.string.demo_short) + " " else ""
    return demo + date + (if (n.isNotEmpty()) " #$n" else "")
}

// ---------------- 健檢分頁 ----------------

@Composable
fun YzHealthTab() {
    val repo = LocalRepo.current
    val rec by repo.recording.collectAsState()
    val history by repo.healthHistory.collectAsState()
    val open by repo.openRequest.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<SessionDir>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf<SessionDir?>(null) }
    var confirmShare by remember { mutableStateOf<SessionDir?>(null) }
    var report by remember { mutableStateOf<Path?>(null) }
    val scope = rememberCoroutineScope()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val loaded by repo.healthLoaded.collectAsState()

    // 點健檢通知、或監控頁的「上趟健檢」：直接打開最新一趟的報告（App 剛開時等清單讀完）
    LaunchedEffect(open, loaded) {
        if (open == Repo.OPEN_HEALTH && loaded) {
            repo.openRequest.value = null
            repo.healthHistory.value.firstOrNull()?.let { report = it.dir }
        }
    }
    LaunchedEffect(refresh, rec, history.size) { list = withContext(Dispatchers.Default) { SessionDir.list(repo.sessionsRoot) } }

    report?.let { d ->
        YzBack { report = null }
        HealthScreen(SessionDir(d, 0, null, null)) { report = null }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "top") { Spacer(Modifier.height(4.dp)) }
        item(key = "trend") { HealthTrend { d -> report = d } }
        item(key = "title") { YzSectionHeader(if (history.isEmpty()) 1 else 2, stringResource(R.string.yz_rides, list.size)) }
        items(list, key = { it.dir.name }) { s ->
            val live = rec?.dir == s.dir
            val entry = history.firstOrNull { it.dir == s.dir }
            YzCard(accent = if (s === list.firstOrNull()) Yz.rose else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    YzDatePlate(s.dir.name)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val top = entry?.report?.findings?.firstOrNull { it.level != rx.session.EngineHealth.Level.INFO }
                        if (entry != null && !live) Row(verticalAlignment = Alignment.Bottom) {
                            // 引擎運轉幾分鐘：大數字＋小單位
                            val min = (entry.report.runningSec / 60).toInt()
                            Text("$min", style = digits(26.sp, FontWeight.ExtraBold, italic = true), color = if (RxColors.palette.light) RxColors.text else Yz.pastel)
                            Text(" " + stringResource(R.string.yz_minutes, min).replace("$min", "").trim(), color = Yz.label, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                        } else Text(
                            when {
                                live -> stringResource(R.string.yz_recording_now)
                                s.rounds != null -> "${rx.protocol.Fmt.fixed(s.sizeBytes / 1e6, 1)} MB"
                                else -> stringResource(R.string.rec_unfinished, s.sizeBytes / 1e6)
                            },
                            color = if (live) Yz.rose else RxColors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        )
                        if (entry != null) Text(top?.let(::findingString) ?: stringResource(R.string.health_none), color = RxColors.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    entry?.let { e -> Spacer(Modifier.width(8.dp)); YzScoreBoard(e.report.score, e.report.level) }
                }
                entry?.let { e ->
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        e.report.areas.filter { it.checked }.forEach { a -> areaLabel(a.area)?.let { YzPassChip(stringResource(it), a.level) } }
                    }
                }
                entry?.ai?.let { ai ->
                    Row(Modifier.padding(top = 10.dp)) {
                        Text("AI", color = Yz.ink, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.background(Yz.rose).padding(horizontal = 4.dp, vertical = 1.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(aiPlain(ai).replace('\n', ' '), fontSize = 13.sp, color = RxColors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                    }
                }
                if (!live) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (Fs.size(Path(s.dir, "decoded.loga")) > 0) YzButton(stringResource(R.string.health_title), primary = true) { report = s.dir }
                    YzButton(stringResource(R.string.rec_share_public)) { confirmShare = s }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { confirmDelete = s }) { Text(stringResource(R.string.delete), color = Yz.danger, fontWeight = FontWeight.Bold) }
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(12.dp)) }
    }

    confirmShare?.let { s ->
        fun share(mode: rx.session.ShareExport.Mode) {
            confirmShare = null
            scope.launch {
                val tag = if (mode == rx.session.ShareExport.Mode.PUBLIC) "" else "_full"
                val staging = Path(repo.shareDir, "staging", s.dir.name + tag)
                val zip = Path(repo.shareDir, "${s.dir.name}$tag.zip")
                runCatching {
                    withContext(Dispatchers.Default) {
                        try {
                            Fs.delete(staging)
                            rx.session.ShareExport.copySession(s.dir, staging, mode)
                            rx.session.ZipStore.zipDir(staging, zip)
                        } finally { Fs.delete(staging) }
                    }
                    repo.platform.share(zip, "application/zip", rideTitle(s.dir.name))
                }.onFailure { Toasts.show(it.message ?: "Share failed") }
            }
        }
        AlertDialog(
            onDismissRequest = { confirmShare = null },
            containerColor = RxColors.card,
            title = { Text(stringResource(R.string.rec_share_title)) },
            text = { Text(stringResource(R.string.rec_share_text)) },
            confirmButton = { TextButton(onClick = { share(rx.session.ShareExport.Mode.PUBLIC) }) { Text(stringResource(R.string.rec_share_public)) } },
            dismissButton = { TextButton(onClick = { share(rx.session.ShareExport.Mode.DIAGNOSTIC) }) { Text(stringResource(R.string.rec_share_full), color = RxColors.warn) } },
        )
    }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = RxColors.card,
            title = { Text(stringResource(R.string.rec_delete_title, rideTitle(s.dir.name))) },
            text = { Text(stringResource(R.string.rec_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    Fs.delete(s.dir); confirmDelete = null; refresh++
                    repo.healthHistory.value = repo.healthHistory.value.filter { it.dir != s.dir }
                }) { Text(stringResource(R.string.delete), color = RxColors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ---------------- 設定分頁 ----------------

@Composable
fun YzSettings(onEditConnection: () -> Unit, onStatus: () -> Unit) {
    var showLog by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    if (showLog) { YzLogPage { showLog = false }; return }
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val target by repo.target.collectAsState()
    val stopped by repo.userStopped.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 12.dp)) {
        YzSection(1, stringResource(R.string.yz_conn_title)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                YzStatusChip(onClick = onStatus)
                Spacer(Modifier.width(10.dp))
                Mono(repo.host, RxColors.muted)
            }
            Text(yzStateExplain(state, target != null, stopped), fontSize = 14.sp, color = RxColors.text, modifier = Modifier.padding(top = 10.dp, bottom = 10.dp))
            YzButton(stringResource(R.string.yz_conn_settings), onClick = onEditConnection)
        }
        YzMonitorSettings()
        YzOverlaySettings()
        YzAiSettings()
        YzSection(5, stringResource(R.string.set_look)) {
            val theme by repo.theme.collectAsState()
            val choices = listOf(ThemeChoice.SYSTEM, ThemeChoice.YOZAKURA, ThemeChoice.NIPPON).let { if (theme in it) it else it + theme }
            YzSegmented(choices.map { it to stringResource(it.label) }, theme, { repo.setTheme(it) })
        }
        YzSection(6, stringResource(R.string.set_about)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                YzDecal(scale = 0.8f)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("夜櫻 Yozakura", color = RxColors.text, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Text(repo.platform.appVersion, color = RxColors.muted, style = digits(13.sp, FontWeight.SemiBold))
                }
            }
            YzHint(stringResource(R.string.yz_about))
            YzHint(stringResource(R.string.yz_source))
            YzRow(stringResource(R.string.yz_conn_log), divider = true, onClick = { showLog = true }) { Text("›", color = RxColors.muted, fontSize = 20.sp) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun YzMonitorSettings() {
    val repo = LocalRepo.current
    val style by repo.monStyle.collectAsState()
    val red by repo.redline.collectAsState()
    val flash by repo.flashOn.collectAsState()
    YzSection(2, stringResource(R.string.set_monitor)) {
        Text(stringResource(R.string.set_style), color = RxColors.text, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        YzSegmented(MonStyle.entries.map { it to stringResource(it.label) }, style, { repo.setMonStyle(it) })
        Text(stringResource(R.string.set_redline), color = RxColors.text, fontSize = 16.sp, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        YzSegmented(listOf(9000, 9500, 10000, 10500, 11000).map { it to rx.protocol.Fmt.grouped(it.toLong()) }, red, { repo.setRedline(it) })
        Spacer(Modifier.height(8.dp))
        YzSwitchRow(stringResource(R.string.set_flash), flash, divider = true) { repo.setFlashOn(it) }
        YzHint(stringResource(R.string.set_tiles_hint))
    }
}

@Composable
private fun YzOverlaySettings() {
    val repo = LocalRepo.current
    if (!repo.platform.supportsOverlay) return
    val on by repo.overlayOn.collectAsState()
    val rows by repo.overlayRows.collectAsState()
    var permitted by remember { mutableStateOf(repo.platform.overlayPermitted()) }
    var picking by remember { mutableStateOf<Int?>(null) }
    // 從系統設定回來時重新檢查權限
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); permitted = repo.platform.overlayPermitted() } }
    YzSection(3, stringResource(R.string.set_overlay)) {
        YzSwitchRow(stringResource(R.string.set_overlay_on), on) { repo.setOverlayOn(it); if (it && !permitted) repo.platform.requestOverlayPermission() }
        if (on && !permitted) {
            Text(stringResource(R.string.set_overlay_perm), color = RxColors.warn, fontSize = 12.sp)
            YzButton(stringResource(R.string.set_overlay_grant), Modifier.padding(vertical = 6.dp)) { repo.platform.requestOverlayPermission() }
        }
        Text(stringResource(R.string.set_overlay_rows), color = RxColors.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        rows.forEachIndexed { i, f ->
            YzRow("${i + 1}.  ${stringResource(f.label)}", divider = i > 0, onClick = { picking = i }) {
                Text(stringResource(R.string.change) + " ›", color = Yz.rose, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        YzHint(stringResource(R.string.set_overlay_hint))
    }
    picking?.let { i -> FieldPicker(stringResource(R.string.pick_row_title, i + 1), rows.getOrNull(i), onPick = { repo.setOverlayRow(i, it); picking = null }, onDismiss = { picking = null }) }
}

@Composable
private fun YzAiSettings() {
    val repo = LocalRepo.current
    if (repo.platform.ai == null) return
    val provider by repo.aiProvider.collectAsState()
    val auto by repo.aiAuto.collectAsState()
    var key by remember(provider) { mutableStateOf(repo.aiKey(provider) ?: "") }
    var note by remember { mutableStateOf(repo.vehicleNote) }
    YzSection(4, stringResource(R.string.set_ai)) {
        YzSegmented(AiProvider.entries.map { it to it.label }, provider, { repo.setAiProvider(it) }, Modifier.padding(top = 4.dp, bottom = 8.dp), minWidth = 96.dp)
        OutlinedTextField(key, { key = it; repo.setAiKey(provider, it) }, label = { Text(stringResource(R.string.set_ai_key, provider.label)) }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it; repo.vehicleNote = it }, label = { Text(stringResource(R.string.set_vehicle_note)) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        Spacer(Modifier.height(6.dp))
        YzSwitchRow(stringResource(R.string.set_ai_auto), auto, divider = true) { repo.setAiAuto(it) }
        YzHint(stringResource(R.string.set_ai_hint))
    }
}

/** 連線紀錄：連不上時截圖給開發者看。 */
@Composable
private fun YzLogPage(onBack: () -> Unit) {
    val repo = LocalRepo.current
    val events by repo.events.collectAsState()
    val state by repo.state.collectAsState()
    YzBack(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        YzBackHeader(stringResource(R.string.yz_conn_log), onBack)
        YzHint(stringResource(R.string.yz_conn_log_hint))
        Spacer(Modifier.height(6.dp))
        Mono("${repo.platform.appVersion} · ${repo.host} · $state", RxColors.text, 11)
        LazyColumn(Modifier.fillMaxSize()) {
            items(events.takeLast(200).reversed()) { Mono(it, RxColors.muted, 11) }
        }
    }
}
