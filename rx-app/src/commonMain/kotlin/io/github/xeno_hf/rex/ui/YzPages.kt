package io.github.xeno_hf.rex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** 一個設定區塊：標題＋卡片。 */
@Composable
internal fun YzSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, color = RxColors.brandInk, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp))
    Column(
        Modifier.fillMaxWidth().background(RxColors.card, RoundedCornerShape(14.dp)).border(1.dp, RxColors.line, RoundedCornerShape(14.dp)).padding(14.dp),
        content = content,
    )
}

@Composable
internal fun YzSwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
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

    // 點健檢通知、或監控頁的「上趟健檢」：直接打開最新一趟的報告
    LaunchedEffect(open) {
        if (open == Repo.OPEN_HEALTH) {
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

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "top") { Spacer(Modifier.height(4.dp)) }
        item(key = "trend") { HealthTrend { d -> report = d } }
        item(key = "title") { Text(stringResource(R.string.yz_rides, list.size), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, start = 4.dp)) }
        items(list, key = { it.dir.name }) { s ->
            val live = rec?.dir == s.dir
            val entry = history.firstOrNull { it.dir == s.dir }
            Column(Modifier.fillMaxWidth().background(RxColors.card, RoundedCornerShape(14.dp)).border(1.dp, RxColors.line, RoundedCornerShape(14.dp)).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rideTitle(s.dir.name), style = digits(18.sp, FontWeight.Bold))
                        Text(
                            if (live) stringResource(R.string.yz_recording_now)
                            else if (s.rounds != null) "${rx.protocol.Fmt.fixed(s.sizeBytes / 1e6, 1)} MB"
                            else stringResource(R.string.rec_unfinished, s.sizeBytes / 1e6),
                            color = if (live) RxColors.danger else if (s.rounds != null) RxColors.muted else RxColors.warn, fontSize = 12.sp,
                        )
                    }
                    entry?.let { e ->
                        Text("${e.report.score}", style = digits(30.sp, FontWeight.ExtraBold), color = levelColor(e.report.level))
                        Text(" 分", color = RxColors.muted, fontSize = 12.sp)
                    }
                }
                entry?.ai?.let { ai ->
                    Text(ai.replace('\n', ' '), fontSize = 13.sp, color = RxColors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
                if (!live) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (Fs.size(Path(s.dir, "decoded.loga")) > 0) {
                        OutlinedButton(onClick = { report = s.dir }) { Text(stringResource(R.string.health_title), color = RxColors.brandInk) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { confirmShare = s }) { Text(stringResource(R.string.rec_share_public)) }
                    TextButton(onClick = { confirmDelete = s }) { Text(stringResource(R.string.delete), color = RxColors.danger) }
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
    if (showLog) { YzLogPage { showLog = false }; return }
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val target by repo.target.collectAsState()
    val stopped by repo.userStopped.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        YzSection(stringResource(R.string.yz_conn_title)) {
            YzStatusChip(onClick = onStatus)
            Text(yzStateExplain(state, target != null, stopped), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Mono("${stringResource(R.string.conn_host)}  ${repo.host}", RxColors.muted)
            OutlinedButton(onClick = onEditConnection, modifier = Modifier.padding(top = 6.dp)) { Text(stringResource(R.string.yz_conn_settings), color = RxColors.text) }
        }
        YzMonitorSettings()
        YzOverlaySettings()
        YzAiSettings()
        YzSection(stringResource(R.string.set_look)) {
            val theme by repo.theme.collectAsState()
            val choices = listOf(ThemeChoice.SYSTEM, ThemeChoice.YOZAKURA, ThemeChoice.NIPPON).let { if (theme in it) it else it + theme }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                choices.forEach { t -> FilterChip(selected = theme == t, onClick = { repo.setTheme(t) }, label = { Text(stringResource(t.label)) }) }
            }
        }
        YzSection(stringResource(R.string.set_about)) {
            Text("${stringResource(R.string.app_name)} ${repo.platform.appVersion}", fontWeight = FontWeight.Bold)
            YzHint(stringResource(R.string.yz_about))
            YzHint(stringResource(R.string.yz_source))
            Row(Modifier.fillMaxWidth().clickable { showLog = true }.padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.yz_conn_log), modifier = Modifier.weight(1f))
                Text("›", color = RxColors.muted, fontSize = 18.sp)
            }
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
    YzSection(stringResource(R.string.set_monitor)) {
        Text(stringResource(R.string.set_style))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MonStyle.entries.forEach { st -> FilterChip(selected = style == st, onClick = { repo.setMonStyle(st) }, label = { Text(stringResource(st.label)) }) }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.set_redline))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(9000, 9500, 10000, 10500, 11000).forEach { r -> FilterChip(selected = red == r, onClick = { repo.setRedline(r) }, label = { Text(rx.protocol.Fmt.grouped(r.toLong())) }) }
        }
        YzSwitchRow(stringResource(R.string.set_flash), flash) { repo.setFlashOn(it) }
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
    YzSection(stringResource(R.string.set_overlay)) {
        YzSwitchRow(stringResource(R.string.set_overlay_on), on) { repo.setOverlayOn(it); if (it && !permitted) repo.platform.requestOverlayPermission() }
        if (on && !permitted) {
            Text(stringResource(R.string.set_overlay_perm), color = RxColors.warn, fontSize = 12.sp)
            OutlinedButton(onClick = { repo.platform.requestOverlayPermission() }) { Text(stringResource(R.string.set_overlay_grant), color = RxColors.text) }
        }
        Text(stringResource(R.string.set_overlay_rows), modifier = Modifier.padding(top = 4.dp))
        rows.forEachIndexed { i, f ->
            Row(Modifier.fillMaxWidth().clickable { picking = i }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}.", color = RxColors.muted, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(f.label), modifier = Modifier.weight(1f))
                Text(stringResource(R.string.change), color = RxColors.brandInk, fontSize = 13.sp)
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
    YzSection(stringResource(R.string.set_ai)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AiProvider.entries.forEach { p -> FilterChip(selected = provider == p, onClick = { repo.setAiProvider(p) }, label = { Text(p.label) }) }
        }
        OutlinedTextField(key, { key = it; repo.setAiKey(provider, it) }, label = { Text(stringResource(R.string.set_ai_key, provider.label)) }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it; repo.vehicleNote = it }, label = { Text(stringResource(R.string.set_vehicle_note)) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        YzSwitchRow(stringResource(R.string.set_ai_auto), auto) { repo.setAiAuto(it) }
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
