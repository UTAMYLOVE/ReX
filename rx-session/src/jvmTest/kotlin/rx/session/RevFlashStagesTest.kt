package rx.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RevFlashStagesTest {
    /** 數一段時間內亮→暗的次數。 */
    private fun blinks(f: RevFlash, rpm: Double, from: Long, ms: Long): Int {
        var n = 0; var prev = true; var t = from
        while (t < from + ms) { val on = f.update(t, rpm, 50.0).on; if (prev && !on) n++; prev = on; t += 10 }
        return n
    }

    @Test fun slowBeforeFlashRpmThenFast() {
        val f = RevFlash(RevFlash.Settings(redline = 10_000, wot = RevFlash.Wot.OFF, flashRpm = 8500, preRpm = 500))
        blinks(f, 7900.0, 0, 500)
        assertEquals(RevFlash.State.NORMAL, f.state)          // 還沒到慢閃的轉速
        val slow = blinks(f, 8100.0, 500, 3000)
        assertEquals(RevFlash.State.REV, f.state)             // 8,000 起慢閃
        val fast = blinks(f, 8600.0, 3500, 3000)
        assertTrue(slow in 3..5, "slow=$slow")                // 3 秒約 4–5 下
        assertTrue(fast in 10..13, "fast=$fast")              // 3 秒約 12 下
    }
}
