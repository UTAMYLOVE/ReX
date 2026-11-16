package io.github.xeno_hf.rex

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.xeno_hf.rex.ui.WING_H
import io.github.xeno_hf.rex.ui.WING_W
import io.github.xeno_hf.rex.ui.WingOverlayContent
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 懸浮小儀表：App 退到背景（例如切到 Google 地圖導航）、而且正在連線時顯示；回到 App 或斷線就收起來。
 *
 * - 預設貼右緣 8 dp，圓心大約在螢幕高 56%，避開地圖頂部的轉向卡和底部的抵達時間卡。
 * - 拖曳移動，放手吸到最近的左右邊。
 * - 點一下：變透明 4 秒，期間點擊會穿過去，按得到底下的地圖按鈕。
 * - 點兩下：打開夜櫻。
 * - 拖曳時畫面下方出現 ✕，拖過去放開就關掉；這次連線不再出現，下次連線再回來。
 * - 真的收到車子資料才出現（在家打開 App、連不到車時不會一直蓋在別的 App 上）。
 */
class OverlayController(private val app: Context, private val repo: Repo) {
    private val wm = app.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null
    @Volatile private var wanted = false
    /** 這次連線（以 Target 物件區分）是否收到過資料、是否被使用者拖到 ✕ 關掉。 */
    @Volatile private var streamedTarget: Any? = null
    @Volatile private var dismissedTarget: Any? = null
    private var closeTarget: android.widget.TextView? = null
    /** App 畫面在前景時不顯示懸浮窗。 */
    var appVisible = true
        set(v) { field = v; refresh() }

    fun start() {
        repo.scope.launch {
            combine(repo.target, repo.overlayOn, repo.state) { t, on, st -> Triple(t, on, st) }.collect { (t, on, st) ->
                if (t != null && st == rx.session.SessionState.Streaming) streamedTarget = t
                wanted = t != null && on && streamedTarget === t && dismissedTarget !== t
                main.post { refresh() }
            }
        }
    }

    fun permitted() = Settings.canDrawOverlays(app)

    fun requestPermission() {
        app.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refresh() }; return }
        val show = wanted && !appVisible && permitted()
        if (show && view == null) show() else if (!show && view != null) hide()
    }

    private fun dp(v: Float) = v * app.resources.displayMetrics.density

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        val o = OverlayOwner().also { it.start() }
        val v = ComposeView(app).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { WingOverlayContent(repo) }
        }
        val dm = app.resources.displayMetrics
        val w = dp(WING_W).roundToInt(); val h = dp(WING_H).roundToInt()
        val lp = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val sx = repo.platform.kv.getInt("ov.x", -1); val sy = repo.platform.kv.getInt("ov.y", -1)
            x = if (sx >= 0) sx.coerceAtMost(dm.widthPixels - w) else dm.widthPixels - w - dp(8f).roundToInt()
            y = if (sy >= 0) sy.coerceAtMost(dm.heightPixels - h) else (dm.heightPixels * 0.56f - h / 2f).roundToInt()
        }
        attachTouch(v, lp, w)
        runCatching { wm.addView(v, lp) }.onFailure { o.stop(); return }
        view = v; owner = o; params = lp
        // 第一次出現時說一下怎麼關
        if (!repo.platform.kv.getBoolean("ov.hint", false)) {
            repo.platform.kv.putBoolean("ov.hint", true)
            android.widget.Toast.makeText(app, app.getString(R.string.ov_first_hint), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** 使用者拖到 ✕：這次連線不再顯示。 */
    private fun dismiss() {
        dismissedTarget = repo.target.value
        wanted = false
        hide()
        android.widget.Toast.makeText(app, app.getString(R.string.ov_closed), android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 拖曳時畫面下方的 ✕。 */
    private fun showCloseTarget() {
        if (closeTarget != null) return
        val size = dp(64f).roundToInt()
        val tv = android.widget.TextView(app).apply {
            text = "✕"; textSize = 24f; gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xD9241C1F.toInt()); setStroke(dp(2f).roundToInt(), 0xFFDC9FB4.toInt())
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(64f).roundToInt() }
        runCatching { wm.addView(tv, lp) }.onSuccess { closeTarget = tv }
    }

    private fun hideCloseTarget() {
        closeTarget?.let { runCatching { wm.removeView(it) } }
        closeTarget = null
    }

    /** 小儀表中心是否落在 ✕ 附近。 */
    private fun overClose(v: android.view.View): Boolean {
        val t = closeTarget ?: return false
        if (!t.isAttachedToWindow || t.width == 0) return false
        val a = IntArray(2); v.getLocationOnScreen(a)
        val b = IntArray(2); t.getLocationOnScreen(b)
        val dx = (a[0] + v.width / 2f) - (b[0] + t.width / 2f)
        val dy = (a[1] + v.height / 2f) - (b[1] + t.height / 2f)
        return kotlin.math.hypot(dx, dy) < dp(96f)
    }

    private fun hide() {
        hideCloseTarget()
        view?.let { runCatching { wm.removeView(it) } }
        owner?.stop()
        view = null; owner = null; params = null
        main.removeCallbacksAndMessages(null)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(v: ComposeView, lp: WindowManager.LayoutParams, w: Int) {
        val slop = ViewConfiguration.get(app).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        val gd = GestureDetector(app, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { ghost(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                return true
            }
        })
        v.setOnTouchListener { _, ev ->
            if (!dragging) gd.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; showCloseTarget() }
                    if (dragging) {
                        lp.x = (startX + dx).roundToInt(); lp.y = (startY + dy).roundToInt(); runCatching { wm.updateViewLayout(v, lp) }
                        val near = overClose(v)
                        closeTarget?.let { it.scaleX = if (near) 1.3f else 1f; it.scaleY = it.scaleX }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging && ev.actionMasked == MotionEvent.ACTION_UP && overClose(v)) {
                    dragging = false
                    dismiss()
                } else if (dragging) {
                    hideCloseTarget()
                    val dm = app.resources.displayMetrics
                    val margin = dp(8f).roundToInt()
                    lp.x = if (lp.x + w / 2 < dm.widthPixels / 2) margin else dm.widthPixels - w - margin
                    lp.y = lp.y.coerceIn(0, dm.heightPixels - lp.height)
                    runCatching { wm.updateViewLayout(v, lp) }
                    repo.platform.kv.putInt("ov.x", lp.x); repo.platform.kv.putInt("ov.y", lp.y)
                    dragging = false
                }
            }
            true
        }
    }

    /** 變透明 4 秒、點擊穿透（Android 12 起，透明度 ≤0.8 的視窗才能讓觸控穿過去）。 */
    private fun ghost() {
        val v = view ?: return; val lp = params ?: return
        lp.alpha = 0.15f
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, lp) }
        main.postDelayed({
            if (view === v) {
                lp.alpha = 1f
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm.updateViewLayout(v, lp) }
            }
        }, 4_000)
    }
}

/** ComposeView 放在 Activity 以外的視窗時需要自己提供生命週期。 */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    fun start() {
        saved.performAttach(); saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }
    fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
}
