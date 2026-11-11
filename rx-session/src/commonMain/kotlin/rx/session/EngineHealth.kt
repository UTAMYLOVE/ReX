package rx.session

import rx.defs.Loga
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 引擎健檢：讀一份騎乘紀錄（loga），用固定規則檢查冷卻、供油、電系、怠速、點火與故障碼。
 *
 * 全部在手機上算，不連網。門檻是一般四行程機車引擎的通用經驗值，不是 aRacer 官方規格，
 * 也不認得個別車種（氣冷／水冷、改缸、增壓）；結果只當提示，不能取代實車檢查。
 * 紀錄裡沒有的通道就跳過那一項，不猜。
 */
object EngineHealth {
    enum class Level { OK, INFO, WARN, DANGER }
    enum class Area { COOLING, FUEL, ELECTRICAL, IDLE, IGNITION, FAULTS }

    /** 一條發現。[code] 對應介面字串 health_f_<code>；[args] 依序填進字串。 */
    data class Finding(val area: Area, val level: Level, val code: String, val args: List<String> = emptyList())

    /** 摘要數字（儀表格）。[key] 對應介面字串 health_m_<key>；[value] 為 null＝紀錄裡沒有。 */
    data class Metric(val key: String, val value: Double?, val unit: String, val decimals: Int, val level: Level = Level.OK)

    data class AreaScore(val area: Area, val score: Int, val level: Level, val checked: Boolean)

    data class Report(
        /** 0–100，越高越好。只反映這份紀錄裡看得到的東西。 */
        val score: Int,
        val level: Level,
        val durationSec: Double,
        val runningSec: Double,
        val samples: Int,
        val areas: List<AreaScore>,
        val findings: List<Finding>,
        val metrics: List<Metric>,
        /** 走勢小圖：通道名 → 等距取樣後的值（最多 [SPARK_POINTS] 點）。 */
        val series: Map<String, FloatArray>,
    )

    const val SPARK_POINTS = 120
    private const val WARN_PENALTY = 12
    private const val DANGER_PENALTY = 30

    // 門檻（通用經驗值）
    const val T_ENG_WARN = 115.0
    const val T_ENG_DANGER = 130.0
    const val T_ENG_WARM = 70.0
    const val VOLT_LOW_WARN = 13.0
    const val VOLT_LOW_DANGER = 12.3
    const val VOLT_HIGH_WARN = 15.0
    const val VOLT_HIGH_DANGER = 15.5
    const val AFR_DEV_WARN = 0.7
    const val AFR_DEV_DANGER = 1.5
    const val AFR_WOT_LEAN_WARN = 13.5
    const val AFR_WOT_LEAN_DANGER = 14.2
    const val FUEL_CL_WARN = 10.0
    const val FUEL_CL_DANGER = 20.0
    const val INJ_DUTY_WARN = 85.0
    const val INJ_DUTY_DANGER = 95.0
    const val IDLE_STD_WARN = 150.0
    const val IDLE_STD_DANGER = 300.0
    const val IDLE_OFFSET_WARN = 200.0
    const val MIN_RUNNING_SEC = 60.0

    fun analyze(loga: Loga): Report {
        val n = loga.rows.size
        fun col(name: String): DoubleArray? = loga.names.indexOf(name).takeIf { it >= 0 }?.let { j -> DoubleArray(n) { loga.rows[it][j] } }

        val time = if (n > 0) DoubleArray(n) { loga.rows[it][0] } else DoubleArray(0)
        // 每筆樣本代表的秒數（跟下一筆的間隔；斷線造成的大空檔不算進去）
        val dt = DoubleArray(n) { i -> if (i + 1 < n) ((time[i + 1] - time[i]) / 1000.0).coerceIn(0.0, 1.0) else 0.0 }
        val duration = if (n > 1) (time[n - 1] - time[0]) / 1000.0 else 0.0

        val rpm = col("RPM")
        val tEng = col("T_Eng")
        val volt = col("Volt_Batt")
        val tps = col("TPS_Percent")
        val speed = col("Vehicle_Speed")
        val afr = col("AFR_WBO2")
        val afrTarget = col("AFR_Target")
        val fuelCl = col("Fuel_CL")
        val injDuty = col("Cyl1_Injector_Period_Rate")
        val missCrk = col("Miss_CRK_Cnt")
        val idleDesired = col("Idle_Desir_RPM")
        val wbReady = col("Fuel5.WBO2_Ready")
        val decCut = col("Fuel1.Dec_FC_En")
        val overHeat = col("Mode1.Eng_Over_Heat")

        val running = BooleanArray(n) { rpm != null && rpm[it] > 500.0 }
        fun seconds(pred: (Int) -> Boolean): Double { var s = 0.0; for (i in 0 until n) if (pred(i)) s += dt[i]; return s }
        val runningSec = seconds { running[it] }

        val findings = ArrayList<Finding>()
        val metrics = ArrayList<Metric>()
        val checked = HashSet<Area>()
        fun f1(v: Double) = rx.protocol.Fmt.fixed(v, 1)
        fun f0(v: Double) = rx.protocol.Fmt.fixed(v, 0)
        fun f2(v: Double) = rx.protocol.Fmt.fixed(v, 2)

        // ── 冷卻 ──
        if (tEng != null && rpm != null && runningSec > 0) {
            checked += Area.COOLING
            var maxT = -1e9
            for (i in 0 until n) if (running[i]) maxT = max(maxT, tEng[i])
            val hotSec = seconds { running[it] && tEng[it] >= T_ENG_WARN }
            val level = when {
                maxT >= T_ENG_DANGER -> Level.DANGER
                maxT >= T_ENG_WARN -> Level.WARN
                else -> Level.OK
            }
            metrics += Metric("t_eng_max", maxT, "°C", 0, level)
            if (level == Level.DANGER) findings += Finding(Area.COOLING, Level.DANGER, "temp_danger", listOf(f0(maxT), f0(hotSec)))
            else if (level == Level.WARN) findings += Finding(Area.COOLING, Level.WARN, "temp_warn", listOf(f0(maxT), f0(hotSec)))
            if (runningSec >= 600 && maxT < T_ENG_WARM) findings += Finding(Area.COOLING, Level.INFO, "temp_cold", listOf(f0(maxT)))
            if (overHeat != null) {
                val s = seconds { overHeat[it] > 0.5 }
                if (s > 0) findings += Finding(Area.COOLING, Level.DANGER, "overheat_flag", listOf(f0(s)))
            }
        } else metrics += Metric("t_eng_max", null, "°C", 0)

        // ── 電系 ──
        if (volt != null && rpm != null) {
            val charging = (0 until n).filter { rpm[it] > 2000.0 }.map { volt[it] }.sorted()
            if (charging.size >= 20) {
                checked += Area.ELECTRICAL
                val median = charging[charging.size / 2]
                // 用 1%／99% 分位，不讓單筆雜訊觸發
                val lo = charging[(charging.size * 0.01).toInt()]
                val hi = charging[min(charging.size - 1, (charging.size * 0.99).toInt())]
                val level = when {
                    median < VOLT_LOW_DANGER || hi > VOLT_HIGH_DANGER -> Level.DANGER
                    median < VOLT_LOW_WARN || hi > VOLT_HIGH_WARN -> Level.WARN
                    else -> Level.OK
                }
                metrics += Metric("volt_charge", median, "V", 1, level)
                when {
                    median < VOLT_LOW_DANGER -> findings += Finding(Area.ELECTRICAL, Level.DANGER, "volt_low_danger", listOf(f1(median)))
                    median < VOLT_LOW_WARN -> findings += Finding(Area.ELECTRICAL, Level.WARN, "volt_low_warn", listOf(f1(median)))
                }
                when {
                    hi > VOLT_HIGH_DANGER -> findings += Finding(Area.ELECTRICAL, Level.DANGER, "volt_high_danger", listOf(f1(hi)))
                    hi > VOLT_HIGH_WARN -> findings += Finding(Area.ELECTRICAL, Level.WARN, "volt_high_warn", listOf(f1(hi)))
                }
                if (median - lo > 1.5) findings += Finding(Area.ELECTRICAL, Level.INFO, "volt_dip", listOf(f1(lo), f1(median)))
            } else metrics += Metric("volt_charge", null, "V", 1)
        } else metrics += Metric("volt_charge", null, "V", 1)

        // ── 供油 ──
        // 寬域含氧：感知器就緒、數值在合理範圍、不是減速斷油才算
        fun afrValid(i: Int) = afr != null && running[i] && afr[i] in 9.0..19.0 &&
            (wbReady == null || wbReady[i] > 0.5) && (decCut == null || decCut[i] < 0.5) && (tEng == null || tEng[i] >= T_ENG_WARM)
        if (afr != null && afrTarget != null && tps != null) {
            var sum = 0.0; var w = 0.0
            for (i in 0 until n) if (afrValid(i) && afrTarget[i] in 9.0..19.0 && tps[i] in 3.0..80.0) { sum += (afr[i] - afrTarget[i]) * dt[i]; w += dt[i] }
            if (w >= 20.0) {
                checked += Area.FUEL
                val dev = sum / w
                val level = when {
                    abs(dev) >= AFR_DEV_DANGER -> Level.DANGER
                    abs(dev) >= AFR_DEV_WARN -> Level.WARN
                    else -> Level.OK
                }
                metrics += Metric("afr_dev", dev, "AFR", 2, level)
                if (level != Level.OK) findings += Finding(Area.FUEL, level, if (dev > 0) "afr_lean" else "afr_rich", listOf(f2(abs(dev))))
            } else metrics += Metric("afr_dev", null, "AFR", 2)
            // 全油門偏稀最傷引擎，單獨看
            var wotSum = 0.0; var wotW = 0.0
            for (i in 0 until n) if (afrValid(i) && tps[i] >= 85.0 && rpm != null && rpm[i] >= 4000.0) { wotSum += afr[i] * dt[i]; wotW += dt[i] }
            if (wotW >= 3.0) {
                checked += Area.FUEL
                val wot = wotSum / wotW
                val level = when {
                    wot >= AFR_WOT_LEAN_DANGER -> Level.DANGER
                    wot >= AFR_WOT_LEAN_WARN -> Level.WARN
                    else -> Level.OK
                }
                metrics += Metric("afr_wot", wot, "AFR", 1, level)
                if (level != Level.OK) findings += Finding(Area.FUEL, level, "wot_lean", listOf(f1(wot), f0(wotW)))
            } else metrics += Metric("afr_wot", null, "AFR", 1)
        } else { metrics += Metric("afr_dev", null, "AFR", 2); metrics += Metric("afr_wot", null, "AFR", 1) }
        if (fuelCl != null && runningSec > 0) {
            var sum = 0.0; var w = 0.0
            for (i in 0 until n) if (running[i] && (tEng == null || tEng[i] >= T_ENG_WARM) && fuelCl[i] != 0.0) { sum += fuelCl[i] * dt[i]; w += dt[i] }
            if (w >= 20.0) {
                checked += Area.FUEL
                val mean = sum / w
                val level = when {
                    abs(mean) >= FUEL_CL_DANGER -> Level.DANGER
                    abs(mean) >= FUEL_CL_WARN -> Level.WARN
                    else -> Level.OK
                }
                metrics += Metric("fuel_cl", mean, "%", 1, level)
                if (level != Level.OK) findings += Finding(Area.FUEL, level, if (mean > 0) "trim_add" else "trim_cut", listOf(f1(abs(mean))))
            } else metrics += Metric("fuel_cl", null, "%", 1)
        } else metrics += Metric("fuel_cl", null, "%", 1)
        if (injDuty != null && runningSec > 0) {
            checked += Area.FUEL
            var mx = 0.0
            for (i in 0 until n) if (running[i]) mx = max(mx, injDuty[i])
            val level = when {
                mx >= INJ_DUTY_DANGER -> Level.DANGER
                mx >= INJ_DUTY_WARN -> Level.WARN
                else -> Level.OK
            }
            metrics += Metric("inj_duty", mx, "%", 0, level)
            if (level != Level.OK) findings += Finding(Area.FUEL, level, "inj_duty", listOf(f0(mx)))
        } else metrics += Metric("inj_duty", null, "%", 0)

        // ── 怠速 ──
        if (rpm != null && tps != null) {
            val idle = ArrayList<Double>()
            var desiredSum = 0.0; var desiredN = 0
            for (i in 0 until n) {
                val warm = tEng == null || tEng[i] >= T_ENG_WARM
                val stopped = speed == null || speed[i] < 3.0
                if (warm && stopped && tps[i] < 1.5 && rpm[i] in 700.0..3500.0) {
                    idle += rpm[i]
                    if (idleDesired != null && idleDesired[i] > 0) { desiredSum += idleDesired[i]; desiredN++ }
                }
            }
            if (idle.size >= 80) {
                checked += Area.IDLE
                val mean = idle.average()
                val std = sqrt(idle.sumOf { (it - mean) * (it - mean) } / idle.size)
                val level = when {
                    std >= IDLE_STD_DANGER -> Level.DANGER
                    std >= IDLE_STD_WARN -> Level.WARN
                    else -> Level.OK
                }
                metrics += Metric("idle_rpm", mean, "rpm", 0)
                metrics += Metric("idle_std", std, "rpm", 0, level)
                if (level != Level.OK) findings += Finding(Area.IDLE, level, "idle_unstable", listOf(f0(std), f0(mean)))
                if (desiredN > 0) {
                    val desired = desiredSum / desiredN
                    if (abs(mean - desired) >= IDLE_OFFSET_WARN) findings += Finding(Area.IDLE, Level.WARN, "idle_offset", listOf(f0(mean), f0(desired)))
                }
            } else { metrics += Metric("idle_rpm", null, "rpm", 0); metrics += Metric("idle_std", null, "rpm", 0) }
        }

        // ── 點火／曲軸訊號 ──
        if (missCrk != null && n > 1) {
            checked += Area.IGNITION
            // 8 位元計數器：往上跳就累加；從接近 255 掉回小數字算繞回，其他的往下掉當作 ECU 重開（歸零），不計
            var misses = 0.0
            for (i in 1 until n) {
                val d = missCrk[i] - missCrk[i - 1]
                if (d > 0) misses += d else if (d < 0 && missCrk[i - 1] >= 200.0) misses += d + 256.0
            }
            metrics += Metric("miss_crk", misses, "", 0, if (misses > 0) Level.WARN else Level.OK)
            if (misses > 0) findings += Finding(Area.IGNITION, if (misses >= 10) Level.DANGER else Level.WARN, "miss_crk", listOf(f0(misses)))
        }

        // ── 故障碼 ──
        val malfCols = loga.names.withIndex().filter { (_, name) ->
            name.contains("Malf") && name.contains('.') && !name.startsWith("Malf8.") && !name.contains(".bit", ignoreCase = true)
        }
        if (malfCols.isNotEmpty()) {
            checked += Area.FAULTS
            val seen = malfCols.filter { (j, _) -> loga.rows.any { it[j] > 0.5 } }.map { it.value.substringAfter('.') }.distinct()
            metrics += Metric("fault_count", seen.size.toDouble(), "", 0, if (seen.isEmpty()) Level.OK else Level.WARN)
            if (seen.isNotEmpty()) findings += Finding(Area.FAULTS, if (seen.size >= 3) Level.DANGER else Level.WARN, "fault_codes", listOf(seen.joinToString(", ")))
        }

        if (runningSec < MIN_RUNNING_SEC) findings += Finding(Area.FAULTS, Level.INFO, "short_log", listOf(f0(runningSec)))

        val areas = Area.entries.map { a ->
            val fs = findings.filter { it.area == a }
            val penalty = fs.sumOf { when (it.level) { Level.DANGER -> DANGER_PENALTY; Level.WARN -> WARN_PENALTY; else -> 0 }.toInt() }
            AreaScore(a, (100 - penalty * 2).coerceIn(0, 100), fs.maxOfOrNull { it.level } ?.takeIf { it != Level.INFO } ?: Level.OK, a in checked)
        }
        val total = findings.sumOf { when (it.level) { Level.DANGER -> DANGER_PENALTY; Level.WARN -> WARN_PENALTY; else -> 0 }.toInt() }
        val score = (100 - total).coerceIn(0, 100)
        val worst = findings.map { it.level }.filter { it != Level.INFO }.maxOrNull() ?: Level.OK

        val series = LinkedHashMap<String, FloatArray>()
        for ((name, c) in listOf("RPM" to rpm, "T_Eng" to tEng, "Volt_Batt" to volt, "AFR_WBO2" to afr)) if (c != null && n >= 2) series[name] = downsample(c)

        return Report(score, worst, duration, runningSec, n, areas, findings.sortedByDescending { it.level }, metrics, series)
    }

    /** 等距分桶取平均（走勢圖用）。 */
    internal fun downsample(c: DoubleArray, points: Int = SPARK_POINTS): FloatArray {
        if (c.size <= points) return FloatArray(c.size) { c[it].toFloat() }
        return FloatArray(points) { k ->
            val a = (k.toLong() * c.size / points).toInt()
            val b = max(a + 1, ((k + 1).toLong() * c.size / points).toInt())
            var s = 0.0
            for (i in a until b) s += c[i]
            (s / (b - a)).toFloat()
        }
    }
}
