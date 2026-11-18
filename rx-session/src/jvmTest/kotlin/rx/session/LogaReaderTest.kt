package rx.session

import kotlinx.io.Buffer
import kotlinx.io.writeString
import rx.defs.Loga
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogaReaderTest {
    private fun sample(rows: Int): String = buildString {
        append("<aRacer MX APP Log File>\nCreated Date:2026-11-18 11:17:00\nECU SN:X\n\n<VAR ID>\n-1,1,2,3,4,5,6,7\n<VAR GROUP>\nStage_1,a,a,a,a,b,b,_\n")
        append("<VAR NAME>\nTime,RPM,T_Eng,Volt_Batt,TPS_Percent,Mode1.Unused_Flag,Malf1.P0115,Mark\n<DATA START>\n")
        for (i in 0 until rows) {
            val rpm = 1600 + (i % 400) * 15
            val fault = if (i == 777) 1 else 0          // 只出現一列的故障碼
            append("${i * 62.5},$rpm,${60 + i / 100.0},14.1,${i % 100},1,$fault,0\n")
        }
        append("garbage,row\n")
    }

    private fun lean(text: String, maxRows: Int = 60_000): Loga =
        LogaReader.read(Buffer().apply { writeString(text) }, text.length.toLong(), maxRows = maxRows)

    @Test fun sameReportAsFullParse() {
        val text = sample(4000)
        val full = Loga.parse(text.encodeToByteArray())
        val l = lean(text)
        assertEquals(full.rows.size, l.rows.size)
        assertEquals(1, l.rejectedRows)
        assertTrue("Mode1.Unused_Flag" !in l.names && "Malf1.P0115" in l.names && "RPM" in l.names)
        val a = EngineHealth.analyze(full); val b = EngineHealth.analyze(l)
        // Report 裡有陣列（走勢圖），不能整個比；比分數、各系統、發現和數值
        assertEquals(a.score, b.score); assertEquals(a.level, b.level); assertEquals(a.runningSec, b.runningSec)
        assertEquals(a.areas, b.areas); assertEquals(a.findings, b.findings); assertEquals(a.metrics, b.metrics)
        assertEquals(EngineDigest.build(full), EngineDigest.build(l))
    }

    @Test fun longRideIsThinnedButKeepsShortFaults() {
        val text = sample(4000)
        val l = lean(text, maxRows = 500)
        assertTrue(l.rows.size in 300..520, "rows=${l.rows.size}")
        val j = l.names.indexOf("Malf1.P0115")
        assertTrue(l.rows.any { it[j] > 0.5 })               // 只出現一列的故障碼沒有被抽掉
        val a = EngineHealth.analyze(Loga.parse(text.encodeToByteArray())); val b = EngineHealth.analyze(l)
        assertTrue(kotlin.math.abs(a.runningSec - b.runningSec) < 5.0, "${a.runningSec} vs ${b.runningSec}")
    }
}
