package io.github.xeno_hf.rex.ui

import io.github.xeno_hf.rex.res.R
import rx.protocol.hex

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rx.protocol.RxRaw
import rx.protocol.toHex

private enum class Sub(val label: Int) { Malf(R.string.more_malf), Diag(R.string.more_diag), Settings(R.string.more_settings) }

@Composable
fun MoreScreen() {
    var sub by rememberSaveable { mutableStateOf(Sub.Malf) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        androidx.compose.foundation.layout.FlowRow {
            Sub.entries.forEach { s -> FilterChip(selected = sub == s, onClick = { sub = s }, label = { Text(stringResource(s.label)) }, modifier = Modifier.padding(end = 6.dp)) }
        }
        when (sub) {
            Sub.Malf -> MalfPane()
            Sub.Diag -> DiagPane()
            Sub.Settings -> SettingsPane()
        }
    }
}

@Composable
private fun MalfPane() {
    val repo = LocalRepo.current
    val live by repo.live.collectAsState()
    val seen by repo.malfSeen.collectAsState()
    val l = live
    val active = l?.layout?.activeMalfunctions(l.raw).orEmpty()
    val flags = l?.layout?.activeMalfFlags(l.raw).orEmpty()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        SectionTitle(stringResource(R.string.malf_now))
        if (l == null) Text(stringResource(R.string.not_connected), color = RxColors.muted)
        else if (active.isEmpty()) Text(stringResource(R.string.malf_none), color = RxColors.accent)
        else active.forEach { d -> MalfRow(d.name, RxColors.danger) }
        Spacer(Modifier.height(12.dp))
        SectionTitle(stringResource(R.string.malf_seen, seen.size))
        if (seen.isEmpty()) Text("—", color = RxColors.muted)
        seen.entries.sortedBy { it.value }.forEach { (n, t) -> MalfRow(stringResource(R.string.malf_first, n, rx.session.WallClock.format(t, "HH:mm:ss")), RxColors.warn) }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.malf_hint), color = RxColors.muted, fontSize = 12.sp)
        MapLog()
        // 名字裡有 Malf 但不是故障碼的位元（Malf_On、MILReq、…KeyOn_old、沒名字的 bitN）：照實列出，不當故障
        if (flags.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.malf_flags, flags.size))
            flags.forEach { d -> Text(d.name, color = RxColors.muted, modifier = Modifier.padding(vertical = 2.dp)) }
            Text(stringResource(R.string.malf_flags_hint), color = RxColors.muted, fontSize = 12.sp)
        }
    }
}

/** 這次連線的油圖變動紀錄（時間、從幾號到幾號、是不是這支手機切的、當時轉速車速）。 */
@Composable
private fun MapLog() {
    val repo = LocalRepo.current
    val events by repo.mapEvents.collectAsState()
    val now by repo.mapNow.collectAsState()
    Spacer(Modifier.height(12.dp))
    SectionTitle(stringResource(R.string.map_log_title, now?.toString() ?: "—"))
    if (events.isEmpty()) Text(stringResource(R.string.map_log_empty), color = RxColors.muted, fontSize = 13.sp)
    events.reversed().forEach { e ->
        MalfRow(stringResource(R.string.map_log_row, rx.session.WallClock.format(e.wallMs, "HH:mm:ss"), e.from, e.to,
            stringResource(if (e.local) R.string.map_log_local else R.string.map_log_remote), e.rpm?.toInt() ?: 0, e.speed?.toInt() ?: 0),
            if (e.local) RxColors.muted else RxColors.danger)
    }
}

@Composable
private fun MalfRow(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Badge("●", color); Spacer(Modifier.padding(4.dp)); Text(text)
    }
}

@Composable
private fun DiagPane() {
    val repo = LocalRepo.current
    val tick by repo.tick.collectAsState()
    val state by repo.state.collectAsState()
    val events by repo.events.collectAsState()
    val s = repo.session
    val frames = remember(tick) { repo.recentFrames() }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        SectionTitle(stringResource(R.string.diag_connection))
        Mono("State  $state")
        if (s != null) {
            val st = s.stats
            val plat by s.platform.collectAsState()
            Mono("ECU  ${plat?.label ?: "unknown"} (node 0x${s.node.hex(2)})")
            Mono("Frames  RX ${st.rxFrames} / TX ${st.txFrames}")
            Mono("Parser  ${st.parser}")
            Mono("Rounds  ok ${st.rounds} / partial ${st.incompleteRounds} | ${rx.protocol.Fmt.fixed(st.roundHz, 2)} Hz")
            Mono("Table  ${s.table.entries.size} ch ${s.table.payloadLength} B ${s.table.fragmentsPerRound} frag/round")
            Mono("Rearm ${st.rearms} | Reconnect ${st.reconnects} | No ACK ${st.ackMisses}")
            Mono("Login  ${st.lastLogin ?: "-"}")
            Mono("Write gate refused  ${s.gate.refusals}", if (s.gate.refusals > 0) RxColors.danger else RxColors.text)
        }
        if (s != null) OdReadPanel(s)
        val uplink by repo.uplink.collectAsState()
        uplink?.let { u ->
            SectionTitle(stringResource(R.string.set_sync))
            Mono("${stringResource(if (u.connected) R.string.diag_sync_on else R.string.diag_sync_off)}  ${repo.uplinkAddr}", if (u.connected) RxColors.accent else RxColors.warn)
            Mono("Sent ${u.sent} | Dropped ${u.dropped} | Reconnect ${u.reconnects}", if (u.dropped > 0) RxColors.warn else RxColors.text)
            u.lastError?.let { Mono("Error  $it", RxColors.muted, 11) }
        }
        SectionTitle(stringResource(R.string.diag_frames))
        frames.takeLast(30).reversed().forEach { (_, dir, f) ->
            Mono("${if (dir == RxRaw.DIR_TX) "→" else "←"} ${f.canId.hex(3)}  ${f.data.toHex()}", if (dir == RxRaw.DIR_TX) RxColors.warn else RxColors.muted, 11)
        }
        SectionTitle(stringResource(R.string.diag_events))
        events.takeLast(80).reversed().forEach { Mono(it, RxColors.muted, 11) }
        Spacer(Modifier.height(16.dp))
    }
}

/** ECU 物件字典讀取（SDO upload，唯讀；只在按下時送）。 */
@Composable
private fun OdReadPanel(s: rx.session.Session) {
    val scope = rememberCoroutineScope()
    var idx by rememberSaveable { mutableStateOf("1008") }
    var sub by rememberSaveable { mutableStateOf("0") }
    var block by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<Pair<String, androidx.compose.ui.graphics.Color>>() }
    fun read(i: Int, sb: Int, blk: Boolean = block) {
        if (busy) return
        busy = true
        scope.launch {
            val r = s.readObject(i, sb, blk)
            val head = "0x${i.hex(4)}/$sb"
            results.add(0, r.fold(
                { (d, note) ->
                    val ascii = CharArray(d.size) { k -> d[k].toInt().let { c -> if (c in 32..126) c.toChar() else '·' } }.concatToString()
                    "$head  ${d.size}B $note\n${d.take(64).toByteArray().toHex()}${if (d.size > 64) "…" else ""}\n> ${ascii.take(64)}" to RxColors.accent
                },
                { e -> "$head  ${e.message}" to RxColors.warn },
            ))
            busy = false
        }
    }
    SectionTitle(stringResource(R.string.diag_od_read))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(idx, { idx = it.trim() }, label = { Text("index（hex）") }, singleLine = true, modifier = Modifier.weight(1f))
        Spacer(Modifier.padding(3.dp))
        OutlinedTextField(sub, { sub = it.trim() }, label = { Text("sub") }, singleLine = true, modifier = Modifier.weight(0.5f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = block, onClick = { block = !block }, label = { Text(stringResource(if (block) R.string.diag_block else R.string.diag_normal)) })
        Spacer(Modifier.padding(4.dp))
        OutlinedButton(enabled = !busy, onClick = {
            val i = idx.removePrefix("0x").toIntOrNull(16); val sb = sub.toIntOrNull()
            if (i != null && sb != null && i in 0..0xFFFF && sb in 0..255) read(i, sb)
        }) { Text(stringResource(if (busy) R.string.diag_reading else R.string.diag_read)) }
    }
    androidx.compose.foundation.layout.FlowRow {
        listOf(stringResource(R.string.diag_dev_name) to 0x1008, stringResource(R.string.diag_hw) to 0x1009, stringResource(R.string.diag_sw) to 0x100A).forEach { (n, i) ->
            OutlinedButton(enabled = !busy, onClick = { read(i, 0) }, modifier = Modifier.padding(end = 4.dp)) { Text(n, fontSize = 12.sp) }
        }
    }
    results.take(12).forEach { (t, c) -> Mono(t, c, 11) }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun MonitorSettings() {
    val repo = LocalRepo.current
    val style by repo.monStyle.collectAsState()
    val red by repo.redline.collectAsState()
    val wot by repo.wotMode.collectAsState()
    val flash by repo.flashOn.collectAsState()
    val locked by repo.controlsLocked.collectAsState()
    var auto by remember { mutableStateOf(repo.autoConnect) }
    SectionTitle(stringResource(R.string.set_monitor))
    Text(stringResource(R.string.set_style))
    androidx.compose.foundation.layout.FlowRow {
        MonStyle.entries.forEach { st -> FilterChip(selected = style == st, onClick = { repo.setMonStyle(st) }, label = { Text(stringResource(st.label)) }, modifier = Modifier.padding(end = 6.dp)) }
    }
    Text(stringResource(R.string.set_tiles_hint), color = RxColors.muted, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.set_redline))
    androidx.compose.foundation.layout.FlowRow {
        listOf(9000, 9500, 10000, 10500, 11000).forEach { r -> FilterChip(selected = red == r, onClick = { repo.setRedline(r) }, label = { Text(rx.protocol.Fmt.grouped(r.toLong())) }, modifier = Modifier.padding(end = 6.dp)) }
    }
    Text(stringResource(R.string.set_wot))
    androidx.compose.foundation.layout.FlowRow {
        listOf(rx.session.RevFlash.Wot.BURST3 to R.string.set_wot_burst, rx.session.RevFlash.Wot.CONTINUOUS to R.string.set_wot_cont, rx.session.RevFlash.Wot.OFF to R.string.set_wot_off).forEach { (m, l) ->
            FilterChip(selected = wot == m, onClick = { repo.setWotMode(m) }, label = { Text(stringResource(l)) }, modifier = Modifier.padding(end = 6.dp))
        }
    }
    SwitchRow(stringResource(R.string.set_flash), flash) { repo.setFlashOn(it) }
    Text(stringResource(R.string.set_flash_hint), color = RxColors.muted, fontSize = 12.sp)
    SwitchRow(stringResource(R.string.set_lock), locked) { repo.setControlsLocked(it) }
    SwitchRow(stringResource(R.string.set_auto_connect), auto) { auto = it; repo.autoConnect = it }
}

@Composable
private fun OverlaySettings() {
    val repo = LocalRepo.current
    if (!repo.platform.supportsOverlay) return
    val on by repo.overlayOn.collectAsState()
    val rows by repo.overlayRows.collectAsState()
    var permitted by remember { mutableStateOf(repo.platform.overlayPermitted()) }
    var picking by remember { mutableStateOf<Int?>(null) }
    // 從系統設定回來時重新檢查權限
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); permitted = repo.platform.overlayPermitted() } }
    SectionTitle(stringResource(R.string.set_overlay))
    SwitchRow(stringResource(R.string.set_overlay_on), on) { repo.setOverlayOn(it); if (it && !permitted) repo.platform.requestOverlayPermission() }
    if (on && !permitted) {
        Text(stringResource(R.string.set_overlay_perm), color = RxColors.warn, fontSize = 12.sp)
        OutlinedButton(onClick = { repo.platform.requestOverlayPermission() }) { Text(stringResource(R.string.set_overlay_grant)) }
    }
    Text(stringResource(R.string.set_overlay_rows))
    rows.forEachIndexed { i, f ->
        Row(Modifier.fillMaxWidth().clickable { picking = i }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}.", color = RxColors.muted, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(f.label), modifier = Modifier.weight(1f))
            Text(stringResource(R.string.change), color = RxColors.brandInk, fontSize = 13.sp)
        }
    }
    Text(stringResource(R.string.set_overlay_hint), color = RxColors.muted, fontSize = 12.sp)
    picking?.let { i -> FieldPicker(stringResource(R.string.pick_row_title, i + 1), rows.getOrNull(i), onPick = { repo.setOverlayRow(i, it); picking = null }, onDismiss = { picking = null }) }
}

@Composable
private fun AiSettings() {
    val repo = LocalRepo.current
    if (repo.platform.ai == null) return
    val provider by repo.aiProvider.collectAsState()
    val auto by repo.aiAuto.collectAsState()
    var key by remember(provider) { mutableStateOf(repo.aiKey(provider) ?: "") }
    var model by remember(provider) { mutableStateOf(repo.aiModel(provider)) }
    var note by remember { mutableStateOf(repo.vehicleNote) }
    SectionTitle(stringResource(R.string.set_ai))
    androidx.compose.foundation.layout.FlowRow {
        io.github.xeno_hf.rex.AiProvider.entries.forEach { p -> FilterChip(selected = provider == p, onClick = { repo.setAiProvider(p) }, label = { Text(p.label) }, modifier = Modifier.padding(end = 6.dp)) }
    }
    OutlinedTextField(key, { key = it; repo.setAiKey(provider, it) }, label = { Text(stringResource(R.string.set_ai_key, provider.label)) }, singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(model, { model = it; repo.setAiModel(provider, it) }, label = { Text(stringResource(R.string.set_ai_model)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(note, { note = it; repo.vehicleNote = it }, label = { Text(stringResource(R.string.set_vehicle_note)) }, modifier = Modifier.fillMaxWidth())
    SwitchRow(stringResource(R.string.set_ai_auto), auto) { repo.setAiAuto(it) }
    Text(stringResource(R.string.set_ai_hint), color = RxColors.muted, fontSize = 12.sp)
}

@Composable
private fun SettingsPane() {
    val repo = LocalRepo.current
    val reg by repo.registry.collectAsState()
    var keep by remember { mutableStateOf(repo.keepScreenOn) }
    val theme by repo.theme.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        SectionTitle(stringResource(R.string.set_look))
        androidx.compose.foundation.layout.FlowRow {
            ThemeChoice.entries.forEach { t ->
                FilterChip(selected = theme == t, onClick = { repo.setTheme(t) }, label = { Text(stringResource(t.label)) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        Text(stringResource(R.string.set_look_hint), color = RxColors.muted, fontSize = 12.sp)
        MonitorSettings()
        OverlaySettings()
        AiSettings()
        SectionTitle(stringResource(R.string.set_dash))
        val rpmMax by repo.rpmMax.collectAsState()
        val showGear by repo.showGear.collectAsState()
        Text(stringResource(R.string.set_rpm_max))
        androidx.compose.foundation.layout.FlowRow {
            io.github.xeno_hf.rex.Repo.RPM_MAXES.forEach { k ->
                FilterChip(selected = rpmMax == k, onClick = { repo.setRpmMax(k) }, label = { Text("${k}K") }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        SwitchRow(stringResource(R.string.set_show_gear), showGear) { repo.setShowGear(it) }
        SwitchRow(stringResource(R.string.set_keep_screen), keep) { keep = it; repo.keepScreenOn = it }
        SectionTitle(stringResource(R.string.set_gps))
        val gpsOn by repo.gpsEnabled.collectAsState()
        val gpsSpeed by repo.speedFromGps.collectAsState()
        val askLoc = rememberLocationPermission { repo.setGpsEnabled(repo.gps.hasPermission()) }
        SwitchRow(stringResource(R.string.set_gps_enable), gpsOn) { on ->
            if (on && !repo.gps.hasPermission()) askLoc() else repo.setGpsEnabled(on)
        }
        Text(stringResource(R.string.set_gps_hint), color = RxColors.muted, fontSize = 12.sp)
        if (gpsOn) SwitchRow(stringResource(R.string.set_gps_speed), gpsSpeed) { repo.setSpeedFromGps(it) }
        SectionTitle(stringResource(R.string.set_background))
        val autoRec by repo.autoRecord.collectAsState()
        SwitchRow(stringResource(R.string.set_auto_record), autoRec) { repo.setAutoRecord(it) }
        var afterClose by remember { mutableStateOf(repo.runAfterClose) }
        var idle by remember { mutableStateOf(repo.idleStopMinutes) }
        if (repo.platform.supportsRunAfterClose) {
            SwitchRow(stringResource(R.string.set_run_after_close), afterClose) { afterClose = it; repo.runAfterClose = it }
            Text(stringResource(R.string.set_run_after_close_hint), color = RxColors.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.set_idle_stop))
        androidx.compose.foundation.layout.FlowRow {
            listOf(5, 10, 30, 0).map { it to if (it == 0) stringResource(R.string.set_never) else stringResource(R.string.set_minutes, it) }.forEach { (m, l) ->
                FilterChip(selected = idle == m, onClick = { idle = m; repo.idleStopMinutes = m }, label = { Text(l) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        if (repo.platform.supportsUplink) {
            SectionTitle(stringResource(R.string.set_sync))
            var link by remember { mutableStateOf(repo.uplinkEnabled) }
            var addr by remember { mutableStateOf(repo.uplinkAddr) }
            SwitchRow(stringResource(R.string.set_sync_enable), link) { link = it; repo.uplinkEnabled = it }
            if (link) OutlinedTextField(
                addr, { addr = it; repo.uplinkAddr = it }, label = { Text(stringResource(R.string.set_sync_addr)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        SectionTitle(stringResource(R.string.set_vehicle))
        val builtin by repo.builtinKey.collectAsState()
        val importErr by repo.registryError.collectAsState()
        val scope = rememberCoroutineScope()
        var importing by remember { mutableStateOf(false) }
        val pick = rememberFilePicker { _, bytes -> scope.launch { importing = true; repo.importControlFile(bytes); importing = false } }
        val n = reg?.channels?.size ?: 0
        Text(
            builtin?.let { k -> stringResource(R.string.set_vehicle_builtin, rx.protocol.EcuPlatform.entries.filter { it.controlFile == k }.joinToString(" / ") { it.label }, n) }
                ?: stringResource(R.string.set_vehicle_count, n),
            color = RxColors.muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !importing, onClick = { pick() }) {
                Text(stringResource(if (builtin != null) R.string.set_vehicle_import else R.string.set_reimport))
            }
            if (builtin == null) OutlinedButton(onClick = { repo.clearRegistry() }) { Text(stringResource(R.string.set_vehicle_use_builtin)) }
        }
        importErr?.let { Text(it, color = RxColors.danger, fontSize = 12.sp) }
        SectionTitle(stringResource(R.string.set_about))
        Text("ReX ${repo.platform.appVersion}", color = RxColors.muted)
        Text(stringResource(R.string.set_about_readonly), color = RxColors.muted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
    }
}
