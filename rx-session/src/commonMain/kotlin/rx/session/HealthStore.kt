package rx.session

import rx.session.EngineHealth.Area
import rx.session.EngineHealth.Level

/**
 * 引擎健檢報告存檔格式（每趟騎乘的資料夾裡一份 health.tsv）。
 *
 * 一行一筆、Tab 分隔，舊版 App 讀不到新欄位也不會壞：
 * ```
 * rexhealth\t1
 * score\t92
 * level\tOK
 * dur\t612.4
 * run\t580.0
 * samples\t9800
 * area\tCOOLING\t100\tOK\t1
 * metric\tt_eng_max\t96\t°C\t0\tOK        （值空白＝紀錄裡沒有）
 * finding\tFUEL\tWARN\tafr_lean\t0.82      （其餘欄位都是參數）
 * ```
 * 走勢小圖（series）不存，要看時重新分析原始紀錄。
 */
object HealthStore {
    const val FILE = "health.tsv"
    private const val MAGIC = "rexhealth"

    fun encode(r: EngineHealth.Report): String = buildString {
        fun line(vararg f: Any?) { f.joinTo(this, "\t") { clean(it?.toString() ?: "") }; append('\n') }
        line(MAGIC, 1)
        line("score", r.score)
        line("level", r.level.name)
        line("dur", rx.protocol.Fmt.fixed(r.durationSec, 1))
        line("run", rx.protocol.Fmt.fixed(r.runningSec, 1))
        line("samples", r.samples)
        r.areas.forEach { line("area", it.area.name, it.score, it.level.name, if (it.checked) 1 else 0) }
        r.metrics.forEach { line("metric", it.key, it.value?.let { v -> rx.protocol.Fmt.fixed(v, 4) } ?: "", it.unit, it.decimals, it.level.name) }
        r.findings.forEach { f -> line("finding", f.area.name, f.level.name, f.code, *f.args.toTypedArray()) }
    }

    fun decode(text: String): EngineHealth.Report? {
        val lines = text.lines().filter { it.isNotEmpty() }.map { it.split('\t') }
        if (lines.firstOrNull()?.firstOrNull() != MAGIC) return null
        var score = 0; var level = Level.OK; var dur = 0.0; var run = 0.0; var samples = 0
        val areas = ArrayList<EngineHealth.AreaScore>()
        val metrics = ArrayList<EngineHealth.Metric>()
        val findings = ArrayList<EngineHealth.Finding>()
        for (f in lines.drop(1)) runCatching {
            when (f[0]) {
                "score" -> score = f[1].toInt()
                "level" -> level = Level.valueOf(f[1])
                "dur" -> dur = f[1].toDouble()
                "run" -> run = f[1].toDouble()
                "samples" -> samples = f[1].toInt()
                "area" -> areas += EngineHealth.AreaScore(Area.valueOf(f[1]), f[2].toInt(), Level.valueOf(f[3]), f[4] == "1")
                "metric" -> metrics += EngineHealth.Metric(f[1], f[2].toDoubleOrNull(), f[3], f[4].toInt(), Level.valueOf(f[5]))
                "finding" -> findings += EngineHealth.Finding(Area.valueOf(f[1]), Level.valueOf(f[2]), f[3], f.drop(4))
            }
        }
        return EngineHealth.Report(score, level, dur, run, samples, areas, findings, metrics, emptyMap())
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
}
