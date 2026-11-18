package rx.session

/**
 * 轉速條閃爍狀態機（全畫面只有轉速條會閃，背景、文字、數值都不動）。
 *
 * - REV：轉速到紅線前 200 rpm 並維持 100 ms，已亮的段全部轉紅同相位閃；
 *   轉速掉到紅線前 500 rpm 以下並維持 400 ms 才離開，而且每次至少閃滿 3 個週期，避免在斷油點附近忽閃忽停。
 * - WOT_BURST：節氣門 ≥95% 維持 200 ms 且轉速在紅線前 2,000 rpm 以上，已亮的段轉白閃 3 下（約 1.1 s），之後進 WOT_LATCH 恆亮。
 *   無段變速在全油門時轉速會停在同一區好幾秒，照汽車換檔燈的邏輯會一直閃，所以預設只閃 3 下。
 * - WOT_LATCH：恆亮，節氣門 <80% 維持 500 ms 後解除，下次全油門再觸發。
 * - REV 優先於 WOT。
 *
 * 波形：方波 ON 183 ms／OFF 183 ms（約 2.73 Hz），低於 WCAG 2.3.1 每秒 3 次的上限並留餘量。
 * 相位由呼叫端傳入的時間計算（Android 用畫面幀時間），不靠動畫器：省電模式會停用動畫器。
 */
class RevFlash(var settings: Settings = Settings()) {
    enum class State { NORMAL, REV, WOT_BURST, WOT_LATCH }
    enum class Wot { BURST3, CONTINUOUS, OFF }

    data class Settings(
        /** 紅線（rpm）。 */
        val redline: Int = 10_000,
        val wot: Wot = Wot.BURST3,
        /** false＝完全不閃（REV／WOT 改成恆亮）。 */
        val flash: Boolean = true,
        /** 爆閃轉速（rpm）：到這裡就整圈爆閃；null＝跟著紅線（紅線前 200）。跟紅線分開設，CVT 車全油門到不了紅線也能閃。 */
        val flashRpm: Int? = null,
        /** 先慢閃：比爆閃轉速低這麼多轉就開始慢閃，到爆閃轉速變快閃；0＝不分段（到了才閃）。 */
        val preRpm: Int = 0,
    )

    data class Output(val state: State, val on: Boolean) {
        /** 這一幀轉速條是否處於「暗相位」（OFF 相位只降到 20%，不全黑）。 */
        val dimmed: Boolean get() = (state == State.REV || state == State.WOT_BURST) && !on
    }

    var state = State.NORMAL; private set
    private var entryMs = 0L
    private var revAboveSince = -1L
    private var revBelowSince = -1L
    private var wotSince = -1L
    private var tpsLowSince = -1L
    private var fast = false

    fun reset() {
        state = State.NORMAL; entryMs = 0; revAboveSince = -1; revBelowSince = -1; wotSince = -1; tpsLowSince = -1
    }

    /** 每一幀（或每筆 ECU 資料）呼叫一次。[rpm]、[tps] 為 null＝沒資料，回到 NORMAL。 */
    fun update(tMs: Long, rpm: Double?, tps: Double?): Output {
        if (rpm == null) { reset(); return Output(State.NORMAL, true) }
        val s = settings
        val r = s.redline
        val t = tps ?: 0.0

        // 計時器
        val fastAt = s.flashRpm ?: (r - 200)
        val enter = fastAt - s.preRpm.coerceAtLeast(0)
        // 快閃／慢閃：到爆閃轉速變快，掉回 150 轉以下才變慢（免得在邊界上一直換）
        if (rpm >= fastAt) fast = true else if (rpm < fastAt - 150) fast = false
        if (rpm >= enter) { if (revAboveSince < 0) revAboveSince = tMs } else revAboveSince = -1
        if (rpm < enter - 300) { if (revBelowSince < 0) revBelowSince = tMs } else revBelowSince = -1
        if (t >= 95.0 && rpm >= enter - 1800) { if (wotSince < 0) wotSince = tMs } else wotSince = -1
        if (t < 80.0) { if (tpsLowSince < 0) tpsLowSince = tMs } else tpsLowSince = -1

        val revHit = revAboveSince >= 0 && tMs - revAboveSince >= REV_ENTER_MS
        val wotHit = s.wot != Wot.OFF && wotSince >= 0 && tMs - wotSince >= WOT_ENTER_MS

        when (state) {
            State.NORMAL -> when {
                revHit -> enter(State.REV, tMs)
                wotHit -> enter(State.WOT_BURST, tMs)
            }
            State.REV -> {
                val minDone = tMs - entryMs >= MIN_CYCLES * PERIOD_MS
                val below = revBelowSince >= 0 && tMs - revBelowSince >= REV_EXIT_MS
                if (minDone && below) {
                    // 還在全油門就直接恆亮，不重新閃 3 下
                    if (s.wot != Wot.OFF && t >= 95.0) enter(State.WOT_LATCH, tMs) else enter(State.NORMAL, tMs)
                }
            }
            State.WOT_BURST -> when {
                revHit -> enter(State.REV, tMs)
                s.wot == Wot.CONTINUOUS -> if (tpsLowSince >= 0 && tMs - tpsLowSince >= WOT_EXIT_MS) enter(State.NORMAL, tMs)
                tMs - entryMs >= MIN_CYCLES * PERIOD_MS -> enter(State.WOT_LATCH, tMs)
            }
            State.WOT_LATCH -> when {
                revHit -> enter(State.REV, tMs)
                tpsLowSince >= 0 && tMs - tpsLowSince >= WOT_EXIT_MS -> enter(State.NORMAL, tMs)
            }
        }

        val flashing = state == State.REV || state == State.WOT_BURST
        val period = if (state != State.REV || s.preRpm <= 0) PERIOD_MS else if (fast) FAST_MS else SLOW_MS
        val on = !flashing || !s.flash || ((tMs - entryMs) % period) < period / 2
        return Output(state, on)
    }

    private fun enter(st: State, tMs: Long) { state = st; entryMs = tMs }

    companion object {
        const val ON_MS = 183L
        const val PERIOD_MS = 366L
        /** 分段爆閃：慢閃約每秒 1.5 下，快閃約每秒 4 下。 */
        const val SLOW_MS = 660L
        const val FAST_MS = 250L
        const val MIN_CYCLES = 3
        const val REV_ENTER_MS = 100L
        const val REV_EXIT_MS = 400L
        const val WOT_ENTER_MS = 200L
        const val WOT_EXIT_MS = 500L
    }
}

/**
 * 油圖（MAP 1–3）變動偵測。
 *
 * 新值要穩定 [STABLE_MS] 才算數（濾掉單筆雜訊）。這支手機剛送出切換指令、[EXPECT_MS] 內變成指定的油圖，算「本機切換」；
 * 其他情況都算「不是從這支手機切的」，交給上層提醒。
 */
class MapWatch {
    data class Change(val from: Int, val to: Int, val tMs: Long, val local: Boolean)

    var current: Int? = null; private set
    private var pending: Int? = null
    private var pendingSince = 0L
    private var expectTo: Int? = null
    private var expectAt = 0L

    fun reset() { current = null; pending = null; expectTo = null }

    /** 這支手機送出切換到 [to] 的指令。 */
    fun expect(to: Int, tMs: Long) { expectTo = to; expectAt = tMs }

    /** 每筆資料呼叫；有確定的變動才回傳。 */
    fun onSample(tMs: Long, map: Int?): Change? {
        if (map == null || map !in 1..3) return null
        val cur = current
        if (cur == null) { current = map; pending = null; return null }
        if (map == cur) { pending = null; return null }
        if (pending != map) { pending = map; pendingSince = tMs }
        if (tMs - pendingSince < STABLE_MS) return null
        val local = expectTo == map && tMs - expectAt <= EXPECT_MS
        if (local) expectTo = null
        current = map; pending = null
        return Change(cur, map, tMs, local)
    }

    companion object {
        const val STABLE_MS = 300L
        const val EXPECT_MS = 5_000L
    }
}
