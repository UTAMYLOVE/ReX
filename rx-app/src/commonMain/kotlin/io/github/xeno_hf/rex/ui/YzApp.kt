package io.github.xeno_hf.rex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xeno_hf.rex.Repo
import io.github.xeno_hf.rex.Target
import io.github.xeno_hf.rex.res.R
import kotlinx.coroutines.launch
import rx.session.EcuFinder
import rx.session.Presets
import rx.session.SessionState

/*
 * 夜櫻的外殼：頂列（字標＋連線狀態）、三個分頁（監控／健檢／設定）、連線狀態視窗、連線設定。
 * ReX 原本的畫面（儀表、示波、通道、更多）都不放；它們的程式還在，只是夜櫻不用。
 */

internal enum class YzTab(val label: Int, val icon: ImageVector) {
    Monitor(R.string.tab_monitor, Icons.Filled.Home),
    Health(R.string.tab_health, Icons.Filled.Favorite),
    Settings(R.string.tab_settings, Icons.Filled.Settings),
}

/** 夜櫻進入點：主題＋Repo＋畫面。[startTab]＝開啟時的頁籤名稱（預覽截圖用，平常是 null）。 */
@Composable
fun YozakuraApp(repo: Repo, startTab: String? = null) {
    CompositionLocalProvider(LocalRepo provides repo) {
        val theme by repo.theme.collectAsState()
        RxTheme(theme) {
            Box(Modifier.fillMaxSize()) {
                YzRoot(startTab)
                YzToastHost(Modifier.align(Alignment.BottomCenter))
                val control by repo.lastControl.collectAsState()
                LaunchedEffect(control) { control?.let { Toasts.show(yzControlText(it)) } }
            }
        }
    }
}

private fun yzControlText(m: Repo.ControlMsg): String = when (m.outcome) {
    Repo.ControlMsg.Outcome.ON -> Strings.get(R.string.ctl_on, m.label)
    Repo.ControlMsg.Outcome.OFF -> Strings.get(R.string.ctl_off, m.label)
    Repo.ControlMsg.Outcome.MAP_OK -> Strings.get(R.string.ctl_map_ok, m.label)
    Repo.ControlMsg.Outcome.NEEDS_LOGIN -> Strings.get(R.string.ctl_needs_login, m.label)
    Repo.ControlMsg.Outcome.NOT_LICENSED -> Strings.get(R.string.ctl_not_licensed, m.label)
    Repo.ControlMsg.Outcome.REFUSED -> Strings.get(R.string.ctl_refused, m.label, m.detail ?: "")
    Repo.ControlMsg.Outcome.ERROR -> Strings.get(R.string.ctl_error, m.label, m.detail ?: "")
    Repo.ControlMsg.Outcome.NOT_CONNECTED -> Strings.get(R.string.ctl_not_connected, m.label)
    Repo.ControlMsg.Outcome.DEMO -> Strings.get(R.string.ctl_demo, m.label)
    Repo.ControlMsg.Outcome.UNVERIFIED -> Strings.get(R.string.ctl_unverified, m.label, m.detail ?: "")
    Repo.ControlMsg.Outcome.LOCKED -> Strings.get(R.string.ctl_locked, m.label)
}

@Composable
private fun YzToastHost(modifier: Modifier) {
    val msg = Toasts.message ?: return
    val serial = Toasts.serial
    var visible by remember(serial) { mutableStateOf(true) }
    LaunchedEffect(serial) { kotlinx.coroutines.delay(2500); visible = false }
    if (visible) Text(
        msg, color = RxColors.text, fontSize = 14.sp,
        modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 84.dp, start = 24.dp, end = 24.dp)
            .background(RxColors.cardHi, RoundedCornerShape(12.dp)).border(1.dp, RxColors.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun YzRoot(startTab: String?) {
    val repo = LocalRepo.current
    val registry by repo.registry.collectAsState()
    val target by repo.target.collectAsState()
    val open by repo.openRequest.collectAsState()
    var tab by rememberSaveable { mutableStateOf(YzTab.entries.firstOrNull { it.name.equals(startTab, ignoreCase = true) } ?: YzTab.Monitor) }
    var showConnect by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var confirmEdit by remember { mutableStateOf(false) }
    // 改連線設定會重新連線：錄製中先確認（狀態視窗、設定頁、沒連線提示都走這裡）
    val editConnection = { if (repo.target.value != null && repo.recording.value != null) confirmEdit = true else showConnect = true }

    LaunchedEffect(target) { repo.platform.keepScreenOn(target != null && repo.keepScreenOn) }
    // 開好就自動連上次的車；騎乘健檢清單也在這時讀進來
    LaunchedEffect(registry != null) { if (registry != null) { repo.autoConnectIfIdle(); repo.loadHealthHistory() } }
    // 通知或監控頁要求打開某個畫面
    LaunchedEffect(open) {
        when (open) {
            Repo.OPEN_MAP -> tab = YzTab.Monitor
            Repo.OPEN_HEALTH -> tab = YzTab.Health
        }
    }

    val loadingDefs by repo.registryLoading.collectAsState()
    if (registry == null) {
        Box(Modifier.fillMaxSize().background(RxColors.bg)) {
            if (loadingDefs) Text(stringResource(R.string.loading), color = RxColors.muted, modifier = Modifier.align(Alignment.Center)) else ImportScreen()
        }
        return
    }

    val content: @Composable () -> Unit = {
        when (tab) {
            YzTab.Monitor -> Box(Modifier.fillMaxSize()) {
                MonitorScreen()
                if (target == null) YzIdleHint(onSettings = editConnection)
            }
            YzTab.Health -> YzHealthTab()
            YzTab.Settings -> YzSettings(onEditConnection = editConnection, onStatus = { showSheet = true })
        }
    }

    if (isLandscape()) {
        Row(Modifier.fillMaxSize().background(RxColors.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(
                Modifier.width(76.dp).fillMaxHeight().background(Yz.band).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                YzWordmark(compact = true)
                Spacer(Modifier.height(8.dp))
                YzStatusChip(compact = true) { showSheet = true }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
                    YzTab.entries.forEach { t -> YzRailItem(t, tab == t) { tab = t } }
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) { content() }
        }
    } else {
        Column(Modifier.fillMaxSize().background(RxColors.bg)) {
            YzTopBar(onStatus = { showSheet = true })
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            YzTabBar(tab) { tab = it }
        }
    }
    if (showSheet) YzConnSheet(onDismiss = { showSheet = false }, onEdit = { showSheet = false; editConnection() })
    if (showConnect) YzConnectDialog(onDismiss = { showConnect = false })
    if (confirmEdit) AlertDialog(
        onDismissRequest = { confirmEdit = false },
        containerColor = RxColors.card,
        title = { Text(stringResource(R.string.yz_conn_settings)) },
        text = { Text(stringResource(R.string.yz_conn_edit_text)) },
        confirmButton = { TextButton(onClick = { confirmEdit = false; showConnect = true }) { Text(stringResource(R.string.yz_confirm), color = RxColors.brandInk) } },
        dismissButton = { TextButton(onClick = { confirmEdit = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---------------- 頂列、頁籤 ----------------

@Composable
internal fun YzWordmark(compact: Boolean = false) {
    if (compact) YzStickerWordmark(size = 18) else YzStickerWordmark()
}

/** 頂列：貼紙字標＋名牌＋斜條，右邊是連線狀態（點它開連線視窗；沒連線時在視窗裡按連線）。 */
@Composable
private fun YzTopBar(onStatus: () -> Unit) {
    YzBanner(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        YzStatusChip(onClick = onStatus)
    }
}

/** 底部頁籤：上緣細線；選中的頁籤上方一段 10° 斜的櫻粉條、字變粗白。 */
@Composable
private fun YzTabBar(tab: YzTab, onSelect: (YzTab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Yz.band)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1F0E16)))
        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            YzTab.entries.forEach { t ->
                val sel = tab == t
                val tint = if (sel) Yz.pastel else if (RxColors.palette.light) Color(0xFFFEDFE1) else Color(0xFF8A8A90)
                Box(Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = null, indication = null) { onSelect(t) }) {
                    if (sel) {
                        // 選中：10° 斜板由上往下淡出的玫瑰色＋上緣一條櫻粉
                        Box(Modifier.align(Alignment.Center).fillMaxHeight().width(120.dp).background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Yz.rose.copy(alpha = .30f), Color.Transparent)), SlantShape))
                        Box(Modifier.align(Alignment.TopCenter).size(120.dp, 3.dp).background(Yz.pastel, SlantShape))
                    }
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                        Icon(t.icon, null, tint = tint, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(t.label), color = tint, fontSize = 16.sp, fontWeight = if (sel) FontWeight.Black else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun YzRailItem(t: YzTab, selected: Boolean, onClick: () -> Unit) {
    val c = if (selected) Yz.pastel else if (RxColors.palette.light) Color(0xFFFEDFE1) else Color(0xFF8A8A90)
    Box(Modifier.width(64.dp).clickable(onClick = onClick).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.align(Alignment.CenterStart).size(4.dp, 36.dp).background(Yz.pastel, SlantShape))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(t.icon, null, tint = c, modifier = Modifier.size(22.dp))
            Text(stringResource(t.label), fontSize = 12.sp, lineHeight = 15.sp, color = c, fontWeight = if (selected) FontWeight.Black else FontWeight.Medium)
        }
    }
}

// ---------------- 連線狀態 ----------------

/** 狀態的顏色、短字、要不要斜線紋（有問題才有）。注意＝玫瑰粉，斷線＝紅，不用黃色。 */
@Composable
private fun yzState(state: SessionState, stopped: Boolean): Triple<Color, String, Boolean> = when (state) {
    SessionState.Streaming -> Triple(Yz.aqua, stringResource(R.string.st_connected), false)
    is SessionState.Arming -> Triple(Yz.caution, stringResource(R.string.st_connecting), true)
    is SessionState.NoBroadcast -> Triple(Yz.caution, stringResource(R.string.st_waiting), true)
    is SessionState.Connecting -> Triple(Yz.caution, stringResource(R.string.st_connecting), true)
    is SessionState.Reconnecting -> Triple(Yz.danger, stringResource(R.string.st_reconnecting), true)
    else -> Triple(Yz.mocha, stringResource(if (stopped) R.string.yz_idle_stopped else R.string.not_connected), false)
}

/** 點一下打開連線狀態視窗。 */
@Composable
internal fun YzStatusChip(compact: Boolean = false, onClick: (() -> Unit)?) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val stopped by repo.userStopped.collectAsState()
    val (color, text, hatch) = yzState(state, stopped)
    YzStateChip(text, color, dot = !hatch, hatch = hatch, compact = compact,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
}

/** 一句話說明現在的狀態，以及使用者該做什麼。 */
@Composable
internal fun yzStateExplain(state: SessionState, connected: Boolean, stopped: Boolean): String = stringResource(
    when {
        !connected -> if (stopped) R.string.yz_state_stopped else R.string.yz_state_idle
        state == SessionState.Streaming -> R.string.yz_state_streaming
        state is SessionState.NoBroadcast -> R.string.yz_state_waiting
        state is SessionState.Reconnecting -> R.string.yz_state_reconnecting
        else -> R.string.yz_state_connecting
    },
)

/**
 * 用存好的設定連線（需要時先要定位權限，給不給都會連）。[onDone] 在真的送出連線之後才呼叫：
 * 要權限時視窗要等回答回來才能關，不然權限結果回來時接收的元件已經不在，連線就不會送出。
 */
@Composable
internal fun rememberYzConnect(onDone: () -> Unit = {}): () -> Unit {
    val repo = LocalRepo.current
    val ask = rememberLocationPermission { repo.connect(Target.Ecu(repo.host.trim())); onDone() }
    return {
        val needLocation = (repo.gpsEnabled.value || repo.platform.backgroundNeedsLocation) && !repo.gps.hasPermission()
        if (needLocation) ask() else { repo.connect(Target.Ecu(repo.host.trim())); onDone() }
    }
}

/** 點狀態 chip 跳出來的小視窗：現在怎樣、連線設定、中斷連線（會先確認）。 */
@Composable
private fun YzConnSheet(onDismiss: () -> Unit, onEdit: () -> Unit) {
    val repo = LocalRepo.current
    val state by repo.state.collectAsState()
    val target by repo.target.collectAsState()
    val stopped by repo.userStopped.collectAsState()
    val ap by repo.ecuAp.collectAsState()
    val connect = rememberYzConnect(onDone = onDismiss)
    var confirmStop by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RxColors.card,
        title = { Text(stringResource(R.string.yz_conn_title)) },
        text = {
            Column {
                YzStatusChip(onClick = null)
                Spacer(Modifier.height(10.dp))
                Text(yzStateExplain(state, target != null, stopped), fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Mono("${stringResource(R.string.conn_host)}  ${repo.host}", RxColors.muted)
                ap?.let { Mono("WiFi  $it", RxColors.muted) }
                Spacer(Modifier.height(14.dp))
                if (target == null) {
                    YzButton(stringResource(R.string.connect), Modifier.fillMaxWidth(), primary = true) { connect() }
                } else {
                    YzButton(stringResource(R.string.yz_conn_stop), Modifier.fillMaxWidth(), danger = true) { confirmStop = true }
                }
                Spacer(Modifier.height(8.dp))
                YzButton(stringResource(R.string.yz_conn_settings), Modifier.fillMaxWidth(), onClick = onEdit)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
    if (confirmStop) AlertDialog(
        onDismissRequest = { confirmStop = false },
        containerColor = RxColors.card,
        title = { Text(stringResource(R.string.yz_conn_stop_title)) },
        text = { Text(stringResource(R.string.yz_conn_stop_text)) },
        confirmButton = { TextButton(onClick = { confirmStop = false; repo.stopByUser(); onDismiss() }) { Text(stringResource(R.string.yz_confirm), color = RxColors.danger) } },
        dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 監控頁沒連線時蓋在上面的提示。 */
@Composable
private fun YzIdleHint(onSettings: () -> Unit) {
    val repo = LocalRepo.current
    val stopped by repo.userStopped.collectAsState()
    val connect = rememberYzConnect()
    Box(Modifier.fillMaxSize().background(RxColors.bg.copy(alpha = 0.94f)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(if (stopped) R.string.yz_idle_stopped else R.string.yz_idle_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(if (stopped) R.string.yz_state_stopped else R.string.yz_idle_hint), color = RxColors.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                YzButton(stringResource(R.string.connect), primary = true, onClick = connect)
                YzButton(stringResource(R.string.yz_conn_settings), onClick = onSettings)
            }
        }
    }
}

/** 連線設定：只放騎士用得到的（車子位址、ECU 密碼、車子的 WiFi）。通道固定全部，網路模式固定自動。 */
@Composable
private fun YzConnectDialog(onDismiss: () -> Unit) {
    val repo = LocalRepo.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(repo.host) }
    var pw by remember { mutableStateOf(repo.secrets.get() ?: "") }
    var ssid by remember { mutableStateOf(repo.ecuSsid) }
    var wpw by remember { mutableStateOf(repo.ecuWifiPw) }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<String>?>(null) }
    val connect = rememberYzConnect(onDone = onDismiss)
    fun changed() = host.trim() != repo.host || pw.trim() != (repo.secrets.get() ?: "") || ssid.trim() != repo.ecuSsid || wpw != repo.ecuWifiPw
    fun save() {
        repo.host = host.trim(); repo.secrets.put(pw.trim().ifEmpty { null })
        repo.ecuSsid = ssid; repo.ecuWifiPw = wpw; repo.presetId = Presets.ALL_CHANNELS.id
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RxColors.card,
        title = { Text(stringResource(R.string.yz_conn_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.conn_host)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !scanning, onClick = {
                        scanning = true; found = null
                        scope.launch {
                            val finder = repo.platform.ecuFinder()
                            found = runCatching { finder.find(listOf(EcuFinder.KnownHost(host.trim()))).map { it.probe.host } }
                                .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; emptyList() }
                            found?.singleOrNull()?.let { host = it }
                            scanning = false
                        }
                    }) { Text(stringResource(if (scanning) R.string.conn_finding else R.string.conn_find)) }
                    if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                found?.let { list ->
                    if (list.isEmpty()) Text(stringResource(R.string.conn_not_found), color = RxColors.warn, fontSize = 12.sp)
                    if (list.size > 1) list.forEach { h -> Text("● $h", color = RxColors.accent, modifier = Modifier.clickable { host = h }.padding(4.dp)) }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(pw, { pw = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.conn_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth())
                if (repo.platform.supportsNetworkBinding) {
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.conn_bike_wifi), fontWeight = FontWeight.Bold)
                    OutlinedTextField(ssid, { ssid = it }, label = { Text(stringResource(R.string.conn_wifi_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (ssid.isNotBlank()) OutlinedTextField(wpw, { wpw = it }, label = { Text(stringResource(R.string.conn_wifi_password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.conn_wifi_hint), color = RxColors.muted, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            YzButton(stringResource(R.string.yz_conn_save), primary = true, enabled = host.isNotBlank()) {
                // 已經連著、設定也沒改：不用重連（重連會把這趟錄製切斷）
                if (repo.target.value != null && !changed()) onDismiss() else { save(); connect() }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
