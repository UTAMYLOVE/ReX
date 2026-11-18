package rx.session

/**
 * 熱車模式（像汽車冷車時紅線會降低的那種）：引擎還沒熱時，把「建議不要超過的轉速」壓低，
 * 隨溫度慢慢升回正常。只是提示（錶上的紅區和爆閃提早），不會也不能限制引擎。
 *
 * 冷（[COLD_C] 以下）＝ [COLD_RPM]；到 [WARM_C] 線性升到平常的爆閃轉速；熱了以後要掉回 [REARM_C] 以下才再算冷車。
 */
class Warmup {
    private var warm = false

    /** 回傳熱車中的建議上限（rpm，250 一格）；已經熱了、沒有溫度、或平常上限本來就不高時回 null。 */
    fun limit(tempC: Double?, normalRpm: Int): Int? {
        if (tempC == null || !tempC.isFinite() || tempC < -30 || tempC > 200) return null
        if (tempC >= WARM_C) warm = true else if (tempC < REARM_C) warm = false
        if (warm || normalRpm <= COLD_RPM) return null
        val k = ((tempC - COLD_C) / (WARM_C - COLD_C)).coerceIn(0.0, 1.0)
        val v = COLD_RPM + (normalRpm - COLD_RPM) * k
        return (kotlin.math.round(v / 250) * 250).toInt()
    }

    companion object {
        const val COLD_C = 40.0
        const val WARM_C = 70.0
        const val REARM_C = 55.0
        const val COLD_RPM = 6000
    }
}
