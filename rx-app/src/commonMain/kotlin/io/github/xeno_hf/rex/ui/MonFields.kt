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
    MAX_RPM("maxrpm", R.string.f_maxrpm, "rpm", 0);

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
    }

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

        val DEFAULT_TILES = listOf(TEMP, AFR, VOLT, TRIM)
        val DEFAULT_ROWS = listOf(TEMP, VOLT, AFR)

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
    A(R.string.style_a), B(R.string.style_b), C(R.string.style_c);

    companion object { fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: C }
}

/** 欄位的資料夾鍵（每種風格各自記住）。 */
fun Repo.tilesKey(style: MonStyle) = "monFields.${style.name}"
