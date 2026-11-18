package io.github.xeno_hf.rex.ui

import io.github.xeno_hf.rex.Live
import io.github.xeno_hf.rex.Repo
import io.github.xeno_hf.rex.res.R
import rx.protocol.Fmt
import rx.session.EngineHealth

/** 監控數值的警示等級（dark cockpit：平常不顯示，有事才亮）。 */
enum class Lv { OK, CAUTION, FAULT }

/** 騎乘中可累計的數字（本次里程、最高轉速）。 */
data class RideStats(val km: Double = 0.0, val maxRpm: Double = 0.0, val startMs: Long = 0L)

/**
 * 監控畫面與懸浮小儀表可以自選的欄位。[id] 存在偏好設定裡，改名不影響舊設定。
 * 門檻和引擎健檢（[EngineHealth]）用同一組通用值。
 */
enum class MonField(val id: String, val label: Int, val unit: String, val decimals: Int, val watch: Boolean = false) {
    TEMP("temp", R.string.f_temp, "°C", 0, watch = true),
    VOLT("volt", R.string.f_volt, "V", 1, watch = true),
    AFR("afr", R.string.f_afr, "", 1, watch = true),
    TRIM("trim", R.string.f_trim, "%", 1, watch = true),
    FAULTS("faults", R.string.f_faults, "", 0, watch = true),
    TPS("tps", R.string.f_tps, "%", 0),
    SA("sa", R.string.f_sa, "°", 1),
    IAT("iat", R.string.f_iat, "°C", 0),
    KPA("kpa", R.string.f_kpa, "kPa", 0),
    PW("pw", R.string.f_pw, "ms", 2),
    KML("kml", R.string.f_kml, "km/L", 1),
    TRIP("trip", R.string.f_trip, "km", 1),
    MAX_RPM("maxrpm", R.string.f_maxrpm, "rpm", 0),
    NBO2("nbo2", R.string.f_nbo2, "mV", 0),
    DUTY("duty", R.string.f_duty, "%", 0),
    HP("hp", R.string.f_hp, "hp", 1),
    TQ("tq", R.string.f_tq, "Nm", 1),
    KML_NOW("kmlnow", R.string.f_kml_now, "km/L", 1);

    fun value(l: Live?, ride: RideStats): Double? = when (this) {
        TEMP -> l?.v(Ch.T_ENG)
        VOLT -> l?.v(Ch.VBATT)
        AFR -> l?.v(Ch.AFR_WBO2)?.takeIf { it > 0.5 }
        TRIM -> l?.v(22)
        FAULTS -> l?.let { it.layout.activeMalfunctions(it.raw).size.toDouble() }
        TPS -> l?.v(Ch.TPS)
        SA -> l?.v(25)
        IAT -> l?.v(24)
        KPA -> l?.v(45)
        PW -> l?.v(35)
        KML -> l?.v(53)
        TRIP -> ride.km
        MAX_RPM -> ride.maxRpm.takeIf { it > 0 }
        NBO2 -> l?.v(46)
        DUTY -> l?.v(82)
        HP -> l?.v(1420)
        TQ -> l?.v(1427)
        KML_NOW -> l?.v(52)
    }

    /** 細量表的範圍（最小、最大）；[redFrom] 之後畫成紅區（沒有就不畫）。 */
    val range: Pair<Double, Double> get() = when (this) {
        TEMP -> 40.0 to 130.0; VOLT -> 10.0 to 16.0; AFR -> 10.0 to 18.0; TRIM -> -25.0 to 25.0; FAULTS -> 0.0 to 5.0
        TPS, DUTY -> 0.0 to 100.0; SA -> 0.0 to 50.0; IAT -> 0.0 to 80.0; KPA -> 0.0 to 105.0; PW -> 0.0 to 15.0
        KML -> 0.0 to 60.0; KML_NOW -> 0.0 to 80.0; TRIP -> 0.0 to 100.0; MAX_RPM -> 0.0 to 12_000.0; NBO2 -> 0.0 to 1000.0
        HP, TQ -> 0.0 to 30.0
    }
    val redFrom: Double? get() = when (this) { TEMP -> EngineHealth.T_ENG_WARN; DUTY -> 85.0; else -> null }
    fun frac(v: Double) = ((v - range.first) / (range.second - range.first)).coerceIn(0.0, 1.0).toFloat()

    /** 顯示用字串（不含單位）。 */
    fun text(v: Double?): String = when {
        v == null -> "—"
        this == TRIM -> (if (v > 0.05) "+" else "") + Fmt.fixed(v, decimals)
        this == MAX_RPM -> Fmt.grouped((kotlin.math.round(v / 50) * 50).toLong())
        else -> Fmt.fixed(v, decimals)
    }

    fun level(v: Double?, l: Live?): Lv {
        if (v == null || l == null) return Lv.OK
        val rpm = l.v(Ch.RPM) ?: 0.0
        val tps = l.v(Ch.TPS) ?: 0.0
        return when (this) {
            TEMP -> if (v >= EngineHealth.T_ENG_DANGER) Lv.FAULT else if (v >= EngineHealth.T_ENG_WARN) Lv.CAUTION else Lv.OK
            VOLT -> if (rpm > 2000) when {
                v < EngineHealth.VOLT_LOW_DANGER || v > EngineHealth.VOLT_HIGH_DANGER -> Lv.FAULT
                v < EngineHealth.VOLT_LOW_WARN || v > EngineHealth.VOLT_HIGH_WARN -> Lv.CAUTION
                else -> Lv.OK
            } else if (v < 11.5) Lv.CAUTION else Lv.OK
            AFR -> if (tps >= 85 && rpm >= 4000) when {
                v >= EngineHealth.AFR_WOT_LEAN_DANGER -> Lv.FAULT
                v >= EngineHealth.AFR_WOT_LEAN_WARN -> Lv.CAUTION
                else -> Lv.OK
            } else {
                val t = l.v(20)
                if (t != null && t in 9.0..19.0 && kotlin.math.abs(v - t) >= EngineHealth.AFR_DEV_DANGER) Lv.CAUTION else Lv.OK
            }
            TRIM -> kotlin.math.abs(v).let { if (it >= EngineHealth.FUEL_CL_DANGER) Lv.FAULT else if (it >= EngineHealth.FUEL_CL_WARN) Lv.CAUTION else Lv.OK }
            FAULTS -> if (v >= 3) Lv.FAULT else if (v >= 1) Lv.CAUTION else Lv.OK
            else -> Lv.OK
        }
    }

    companion object {
        fun of(id: String): MonField? = entries.firstOrNull { it.id == id }
        fun parse(s: String?, def: List<MonField>): List<MonField> =
            s?.split(',')?.mapNotNull(::of)?.takeIf { it.isNotEmpty() } ?: def

        // 預設不放空燃比：多數車沒裝 AF2，會一直顯示「—」
        val DEFAULT_TILES = listOf(TEMP, VOLT, TPS, TRIM)
        val DEFAULT_ROWS = listOf(TEMP, VOLT, IAT)
        /** D 風格主畫面的六格。 */
        val DEFAULT_SIX = listOf(TEMP, VOLT, TRIM, SA, NBO2, IAT)
        /** 主畫面錶盤下面的八列數據（左右各四）。 */
        val DEFAULT_EIGHT = listOf(TEMP, VOLT, IAT, TRIM, TPS, NBO2, SA, KPA)
        /** 錶盤裡的三個小數值：內圈左、內圈右、左下角。 */
        val DEFAULT_DIAL = listOf(KPA, PW, KML)

        /**
         * 使用者選的欄位，加上「有警示就暫時頂替最後一格」：沒放在畫面上的監看項目（水溫、電壓、空燃比、燃油修正、故障碼）
         * 一旦進入注意或危險，就換掉最後一格，條件解除後自動換回。
         */
        fun withOverride(chosen: List<MonField>, l: Live?, ride: RideStats): List<MonField> {
            if (l == null || chosen.isEmpty()) return chosen
            val worst = entries.filter { it.watch && it !in chosen }
                .map { it to it.level(it.value(l, ride), l) }
                .filter { it.second != Lv.OK }
                .maxByOrNull { it.second.ordinal } ?: return chosen
            return chosen.dropLast(1) + worst.first
        }
    }
}

/** 監控主畫面的三種風格。 */
enum class MonStyle(val label: Int) {
    A(R.string.style_a), B(R.string.style_b), C(R.string.style_c), D(R.string.style_d);

    companion object { fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: C }
}

/** 欄位的資料夾鍵（每種風格各自記住）。 */
fun Repo.tilesKey(style: MonStyle) = "monFields.${style.name}"
