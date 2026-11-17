package rx.session

import rx.defs.Loga
import rx.protocol.Fmt

/**
 * 給 AI 看的「引擎數據詳細摘要」（純文字、繁體中文），比 [EngineHealth.Report] 的十個數字多很多：
 * 各引擎數值的統計、各工況平均、溫度變化、轉速分布、怠速、曲軸訊號、油圖、故障碼。
 *
 * 只放引擎本身的數據：**不放**車速、GPS、時間戳以外的任何身分或位置資訊。
 * 長度控制在約 3,000 字以內（AI 計費按字數算）。
 */
object EngineDigest {
    /** 一個要統計的通道：紀錄欄名、中文名、單位、小數位、合理範圍（範圍外的讀數當成沒有）。 */
    private class Ch(val col: String, val label: String, val unit: String, val dec: Int, val lo: Double, val hi: Double)

    private val STATS = listOf(
        Ch("RPM", "轉速", "rpm", 0, 1.0, 20_000.0),
        Ch("TPS_Percent", "節氣門開度", "%", 0, 0.0, 100.0),
        Ch("T_Eng", "引擎溫度", "°C", 0, -30.0, 200.0),
        Ch("T_Air", "進氣溫度", "°C", 0, -30.0, 120.0),
        Ch("SA", "點火角度", "°", 1, -30.0, 70.0),
        Ch("Cyl1_Comn_FuelPW", "噴油時間", "ms", 2, 0.01, 50.0),
        Ch("Cyl1_Injector_Period_Rate", "噴油嘴開度", "%", 0, 0.0, 100.0),
        Ch("Cyl1_Eng_AP", "歧管壓力", "kPa", 0, 5.0, 150.0),
        Ch("Volt_Batt", "電瓶電壓", "V", 1, 5.0, 20.0),
        Ch("AFR_Target", "目標空燃比", "", 1, 9.0, 19.0),
        Ch("AFR_WBO2", "寬頻空燃比（AF2）", "", 1, 9.0, 19.0),
        Ch("NBO2_Volt", "原廠含氧感知器電壓", "mV", 0, 1.0, 1200.0),
        Ch("Fuel_CL", "燃油修正（閉迴路）", "%", 1, -50.0, 50.0),
        Ch("Acc_Fuel_Mult", "加速增量倍數", "", 2, 0.0, 20.0),
        Ch("Cyl1_Final_VM", "最終容積效率係數", "", 2, 0.0, 5.0),
        Ch("ISC_Air_Flow", "怠速進氣量", "%", 0, 0.0, 100.0),
        Ch("Idle_Desir_RPM", "目標怠速", "rpm", 0, 300.0, 5000.0),
        Ch("Horsepower", "估計馬力", "hp", 1, 0.0, 300.0),
        Ch("Torque", "估計扭力", "Nm", 1, 0.0, 500.0),
        Ch("Avg_Fuel_Consump", "平均油耗", "km/L", 1, 0.5, 150.0),
    )

    /** 分工況平均用的通道（用上面同一組範圍）。 */
    private val REGION_COLS = listOf("RPM", "T_Eng", "SA", "Cyl1_Comn_FuelPW", "Cyl1_Eng_AP", "NBO2_Volt", "Fuel_CL", "AFR_WBO2")

    fun build(loga: Loga): String {
        val n = loga.rows.size
        if (n < 2) return "（紀錄太短，沒有引擎數據）"
        fun col(name: String): DoubleArray? = loga.names.indexOf(name).takeIf { it >= 0 }?.let { j -> DoubleArray(n) { loga.rows[it][j] } }
        val time = DoubleArray(n) { loga.rows[it][0] }
        val dt = DoubleArray(n) { i -> if (i + 1 < n) ((time[i + 1] - time[i]) / 1000.0).coerceIn(0.0, 1.0) else 0.0 }
        val rpm = col("RPM") ?: return "（紀錄裡沒有轉速，沒有引擎數據）"
        val tps = col("TPS_Percent")
        val running = BooleanArray(n) { rpm[it] > 500.0 }
        val runSec = (0 until n).sumOf { if (running[it]) dt[it] else 0.0 }
        if (runSec < 1.0) return "（這趟引擎沒有發動）"
        val chs = STATS.associateBy { it.col }
        val cols = HashMap<String, DoubleArray>()
        STATS.forEach { c -> col(c.col)?.let { cols[c.col] = it } }

        fun ok(c: Ch, v: Double) = v.isFinite() && v >= c.lo && v <= c.hi
        fun f(v: Double, d: Int) = Fmt.fixed(v, d)
        fun pct(s: Double) = "${f(100 * s / runSec, 0)}%"

        val sb = StringBuilder()
        sb.appendLine("【引擎數據詳細摘要】（只算引擎運轉中的資料；運轉 ${f(runSec / 60, 1)} 分鐘）")

        // ── 各數值統計 ──
        sb.appendLine("各數值（最低／平均／最高／95% 時在這以下）：")
        val missing = ArrayList<String>()
        for (c in STATS) {
            val a = cols[c.col]
            val xs = if (a == null) null else (0 until n).filter { running[it] && ok(c, a[it]) }.map { a[it] }
            if (xs == null || xs.size < 10) { missing += c.label; continue }
            val sorted = xs.sorted()
            val p95 = sorted[((sorted.size - 1) * 0.95).toInt()]
            sb.appendLine("- ${c.label}：${f(sorted.first(), c.dec)}／${f(xs.average(), c.dec)}／${f(sorted.last(), c.dec)}／${f(p95, c.dec)} ${c.unit}".trimEnd())
        }
        if (missing.isNotEmpty()) sb.appendLine("- 紀錄裡沒有或讀數無效：${missing.joinToString("、")}")
        col("P_Atm")?.let { a -> val xs = (0 until n).map { a[it] }.filter { it in 50.0..110.0 }; if (xs.isNotEmpty()) sb.appendLine("- 大氣壓力：平均 ${f(xs.average(), 0)} kPa") }

        // ── 工況分布 ──
        if (tps != null) {
            fun region(i: Int) = when {
                !running[i] -> -1
                tps[i] < 3 && rpm[i] < 2500 -> 0
                tps[i] < 60 -> 1
                tps[i] < 85 -> 2
                else -> 3
            }
            val names = listOf("怠速", "一般巡航（節氣門 3–60%）", "大油門（60–85%）", "全油門（85% 以上）")
            val reg = IntArray(n) { region(it) }
            sb.appendLine("各工況（時間占比；平均值）：")
            for (r in 0..3) {
                val idx = (0 until n).filter { reg[it] == r }
                val sec = idx.sumOf { dt[it] }
                if (sec < 1.0) { sb.appendLine("- ${names[r]}：0%"); continue }
                val parts = REGION_COLS.mapNotNull { name ->
                    val c = chs[name] ?: return@mapNotNull null
                    val a = cols[name] ?: return@mapNotNull null
                    val xs = idx.filter { ok(c, a[it]) }.map { a[it] }
                    if (xs.size < 5) null else "${c.label} ${f(xs.average(), c.dec)}${c.unit.let { u -> if (u.isEmpty()) "" else " $u" }}"
                }
                sb.appendLine("- ${names[r]}：${pct(sec)}；${parts.joinToString("，")}")
            }
        }

        // ── 轉速分布 ──
        val bins = listOf(0.0 to 3000.0, 3000.0 to 5000.0, 5000.0 to 7000.0, 7000.0 to 9000.0, 9000.0 to 1e9)
        sb.appendLine("轉速分布：" + bins.joinToString("，") { (a, b) ->
            val s = (0 until n).sumOf { if (running[it] && rpm[it] >= a && rpm[it] < b) dt[it] else 0.0 }
            val label = if (b > 1e8) "${f(a / 1000, 0)}k 以上" else "${f(a / 1000, 0)}–${f(b / 1000, 0)}k"
            "$label ${pct(s)}"
        })

        // ── 溫度變化 ──
        cols["T_Eng"]?.let { t ->
            val c = chs.getValue("T_Eng")
            val first = (0 until n).firstOrNull { running[it] && ok(c, t[it]) }
            if (first != null) {
                val t0 = time[first]
                val warm = (first until n).firstOrNull { ok(c, t[it]) && t[it] >= 60.0 }
                if (t[first] < 50.0) sb.appendLine("暖車：發動時 ${f(t[first], 0)}°C，" +
                    (warm?.let { "${f((time[it] - t0) / 60_000, 1)} 分鐘到 60°C" } ?: "整趟沒到 60°C"))
                // 每 2 分鐘的最高溫（最多 30 段）
                val bucketMs = maxOf(120_000.0, (time[n - 1] - t0) / 30)
                val maxes = LinkedHashMap<Int, Double>()
                for (i in first until n) if (ok(c, t[i])) {
                    val b = ((time[i] - t0) / bucketMs).toInt()
                    maxes[b] = maxOf(maxes[b] ?: -999.0, t[i])
                }
                sb.appendLine("引擎溫度每 ${f(bucketMs / 60_000, 0)} 分鐘的最高：${maxes.values.joinToString(", ") { f(it, 0) }} °C")
            }
        }

        // ── 怠速 ──
        val idleDesired = cols["Idle_Desir_RPM"]
        if (tps != null) {
            val idle = (0 until n).filter { running[it] && tps[it] < 3 && rpm[it] < 2500 }
            if (idle.size >= 20) {
                val r = idle.map { rpm[it] }
                val mean = r.average()
                val sd = kotlin.math.sqrt(r.sumOf { (it - mean) * (it - mean) } / r.size)
                val want = idleDesired?.let { d -> idle.map { d[it] }.filter { it in 300.0..5000.0 }.takeIf { it.isNotEmpty() }?.average() }
                sb.appendLine("怠速：平均 ${f(mean, 0)} rpm，波動（標準差）${f(sd, 0)} rpm，最低 ${f(r.min(), 0)}" + (want?.let { "，目標 ${f(it, 0)}" } ?: ""))
            }
        }

        // ── 曲軸訊號、油圖、故障碼 ──
        col("Miss_CRK_Cnt")?.let { m ->
            var inc = 0.0
            for (i in 1 until n) { val d = m[i] - m[i - 1]; if (d > 0 && d < 1000) inc += d }
            sb.appendLine("曲軸訊號遺失：這趟增加 ${f(inc, 0)} 次")
        }
        col("MapNum")?.let { m ->
            val used = (0 until n).map { m[it].toInt() }.filter { it in 1..3 }
            if (used.isNotEmpty()) {
                var changes = 0
                for (i in 1 until used.size) if (used[i] != used[i - 1]) changes++
                sb.appendLine("油圖：用過 MAP ${used.distinct().sorted().joinToString("、")}，切換 $changes 次")
            }
        }
        val faults = loga.names.withIndex().filter { (_, name) -> DecodedLayout.isFaultCode(name) }.mapNotNull { (j, name) ->
            val first = (0 until n).firstOrNull { loga.rows[it][j] > 0.5 } ?: return@mapNotNull null
            "${name.substringAfter('.')}（第 ${f((time[first] - time[0]) / 60_000, 1)} 分鐘起）"
        }
        sb.append("故障碼：" + if (faults.isEmpty()) "這趟沒有出現" else faults.joinToString("、"))
        return sb.toString()
    }
}
