package rx.session

import kotlin.test.Test
import kotlin.test.assertEquals

class RevFlashRpmTest {
    /** 爆閃轉速跟紅線分開：紅線 10,000、爆閃 8,000 時，8,100 rpm 就該爆閃；沒設時 8,100 不閃。 */
    @Test fun flashRpmIsIndependentOfRedline() {
        val own = RevFlash(RevFlash.Settings(redline = 10_000, wot = RevFlash.Wot.OFF, flashRpm = 8000))
        var t = 0L
        repeat(20) { own.update(t, 8100.0, 50.0); t += 20 }
        assertEquals(RevFlash.State.REV, own.state)
        repeat(80) { own.update(t, 7600.0, 50.0); t += 20 }   // 掉到「爆閃−300」以下一陣子就停
        assertEquals(RevFlash.State.NORMAL, own.state)

        val byRedline = RevFlash(RevFlash.Settings(redline = 10_000, wot = RevFlash.Wot.OFF))
        t = 0L
        repeat(20) { byRedline.update(t, 8100.0, 50.0); t += 20 }
        assertEquals(RevFlash.State.NORMAL, byRedline.state)
        repeat(20) { byRedline.update(t, 9850.0, 50.0); t += 20 }
        assertEquals(RevFlash.State.REV, byRedline.state)
    }
}
