package rx.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WarmupTest {
    @Test fun coldLimitRisesWithTemperatureThenReleases() {
        val w = Warmup()
        assertEquals(6000, w.limit(25.0, 9000))      // 冷車：壓到 6,000
        assertEquals(6000, w.limit(40.0, 9000))
        assertEquals(7500, w.limit(55.0, 9000))      // 一半溫度＝一半
        assertNull(w.limit(70.0, 9000))              // 熱了：不限制
        assertNull(w.limit(60.0, 9000))              // 熱過以後掉一點不算冷車
        assertEquals(7250, w.limit(52.0, 9000))      // 掉到 55 以下才重新算
        assertNull(Warmup().limit(null, 9000))       // 沒有溫度就不管
        assertNull(Warmup().limit(20.0, 5500))       // 平常上限本來就低
    }
}
