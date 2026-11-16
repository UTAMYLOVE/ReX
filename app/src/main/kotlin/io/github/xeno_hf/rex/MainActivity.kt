package io.github.xeno_hf.rex

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rx.session.readImport
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import io.github.xeno_hf.rex.ui.YozakuraApp
import io.github.xeno_hf.rex.ui.Strings

class MainActivity : ComponentActivity() {
    // 第一次開就一起問：通知（油圖被切換、健檢結果）＋定位（GPS 時速）。拒絕也能用，時速改用車上電腦的數值。
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val loc = r[Manifest.permission.ACCESS_FINE_LOCATION] == true || r[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        repo().let { if (loc && it.gpsEnabled.value) it.setGpsEnabled(true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val perms = buildList {
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                if (repo().gpsEnabled.value && !repo().gps.hasPermission()) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
            }
            if (perms.isNotEmpty()) askPerms.launch(perms.toTypedArray())
            handleShare(intent)
            handleOpen(intent)
        }
        // 語言跟隨系統（含 Android 13+ 的「App 語言」）；換語言時 Activity 會重建
        val locales = resources.configuration.locales
        Strings.setLanguage((0 until locales.size()).map { locales[it].toLanguageTag() })
        setContent { YozakuraApp(repo()) }
    }

    override fun onStart() {
        super.onStart()
        (application as RxApp).platform.overlay?.appVisible = true
        repo().appVisible = true
        // 回到 App：沒連線、使用者也沒自己停掉，就自動連
        repo().autoConnectIfIdle()
    }

    override fun onStop() {
        super.onStop()
        // 離開 App（例如切到導航）：連線中就顯示懸浮小儀表
        (application as RxApp).platform.overlay?.appVisible = false
        repo().appVisible = false
    }

    override fun onResume() {
        super.onResume()
        (application as io.github.xeno_hf.rex.RxApp).platform.activity = java.lang.ref.WeakReference(this)
        repo().let { r -> r.platform.keepScreenOn(r.target.value != null && r.keepScreenOn) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
        handleOpen(intent)
    }

    /** 點通知進來：打開對應的畫面（油圖視窗、最新一趟健檢）。 */
    private fun handleOpen(intent: android.content.Intent?) {
        intent ?: return
        // 從最近使用清單重開時，系統會把當初的 intent 再給一次：不要再跳一次
        if (intent.flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        intent.getStringExtra(EXTRA_OPEN)?.let { repo().openRequest.value = it }
        intent.removeExtra(EXTRA_OPEN)
    }

    companion object { const val EXTRA_OPEN = "yozakura.open" }

    /** 從 XTune（或任何 App）分享進來的騎乘紀錄 → 加入體驗模式。 */
    private fun handleShare(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
        uri ?: return
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if(c.moveToFirst()) c.getString(0) else null }
                    name to (contentResolver.openInputStream(uri)?.use { it.readImport() } ?: error("Cannot read file"))
                }
            }.onSuccess { (name, bytes) -> repo().importDemoLog(name, bytes) { msg -> runOnUiThread { android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_LONG).show() } } }
             .onFailure { android.widget.Toast.makeText(this@MainActivity, it.message ?: "Import failed", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
}
