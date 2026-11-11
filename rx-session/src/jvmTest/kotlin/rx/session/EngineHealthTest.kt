package rx.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rx.defs.Loga
import rx.session.EngineHealth.Area
import rx.session.EngineHealth.Level

/** Synthetic rides only: no owner logs required. */
class EngineHealthTest {
    private val names = listOf(
        "Time", "RPM", "T_Eng", "Volt_Batt", "TPS_Percent", "Vehicle_Speed", "AFR_WBO2", "AFR_Target", "Fuel_CL",
        "Cyl1_Injector_Period_Rate", "Miss_CRK_Cnt", "Idle_Desir_RPM", "Malf0.P0117", "Malf7.P0562", "Malf8.MILReq",
    )

    /** 10 Hz ride: first third idling, then cruising, last 10 s wide open. [edit] tweaks one sample. */
    private fun ride(seconds: Int = 300, edit: (i: Int, t: Double, row: MutableMap<String, Double>) -> Unit = { _, _, _ -> }): Loga {
        val sb = StringBuilder("<aRacer MX APP Log File>\nCreated Date:2026-01-01 00:00:00\n\n<VAR ID>\n")
        sb.append(names.indices.joinToString(",")).append("\n<VAR GROUP>\n").append(names.joinToString(",") { "Stage_1" })
        sb.append("\n<VAR NAME>\n").append(names.joinToString(",")).append("\n<DATA START>\n")
        val n = seconds * 10
        for (i in 0 until n) {
            val t = i / 10.0
            val idle = t < seconds / 3.0
            val wot = t >= seconds - 10
            val row = linkedMapOf(
                "Time" to t * 1000, "RPM" to if (idle) 1500.0 + (i % 5 - 2) * 10 else if (wot) 8000.0 else 5000.0,
                "T_Eng" to 90.0, "Volt_Batt" to 14.1, "TPS_Percent" to if (idle) 0.0 else if (wot) 100.0 else 30.0,
                "Vehicle_Speed" to if (idle) 0.0 else 60.0, "AFR_WBO2" to if (wot) 12.8 else 14.5, "AFR_Target" to if (wot) 12.8 else 14.5,
                "Fuel_CL" to 2.0, "Cyl1_Injector_Period_Rate" to if (wot) 60.0 else 20.0, "Miss_CRK_Cnt" to 0.0,
                "Idle_Desir_RPM" to 1500.0, "Malf0.P0117" to 0.0, "Malf7.P0562" to 0.0, "Malf8.MILReq" to 0.0,
            )
            edit(i, t, row)
            sb.append(names.joinToString(",") { row.getValue(it).toString() }).append('\n')
        }
        return Loga.parse(sb.toString().encodeToByteArray())
    }

    private fun codes(r: EngineHealth.Report) = r.findings.map { it.code }.toSet()

    @Test fun healthyRideScoresFullMarks() {
        val r = EngineHealth.analyze(ride())
        assertEquals(emptyList(), r.findings, r.findings.toString())
        assertEquals(100, r.score)
        assertEquals(Level.OK, r.level)
        assertTrue(r.areas.all { it.checked }, r.areas.toString())
        assertEquals(300.0, r.durationSec, 0.2)
        assertEquals(90.0, r.metrics.first { it.key == "t_eng_max" }.value)
        assertEquals(14.1, r.metrics.first { it.key == "volt_charge" }.value)
    }

    @Test fun overheatingIsFlaggedByLevel() {
        val warn = EngineHealth.analyze(ride { _, t, row -> if (t > 200) row["T_Eng"] = 120.0 })
        assertTrue("temp_warn" in codes(warn))
        assertEquals(Level.WARN, warn.level)
        val danger = EngineHealth.analyze(ride { _, t, row -> if (t > 200) row["T_Eng"] = 135.0 })
        assertTrue("temp_danger" in codes(danger))
        assertEquals(Level.DANGER, danger.level)
        assertTrue(danger.score < warn.score && warn.score < 100)
        assertEquals(Level.DANGER, danger.areas.first { it.area == Area.COOLING }.level)
    }

    @Test fun weakAndOverChargingAreSeparateFindings() {
        assertTrue("volt_low_warn" in codes(EngineHealth.analyze(ride { _, _, row -> row["Volt_Batt"] = 12.7 })))
        assertTrue("volt_low_danger" in codes(EngineHealth.analyze(ride { _, _, row -> row["Volt_Batt"] = 12.0 })))
        assertTrue("volt_high_danger" in codes(EngineHealth.analyze(ride { _, _, row -> row["Volt_Batt"] = 15.8 })))
    }

    @Test fun leanAtWideOpenThrottleIsDanger() {
        val r = EngineHealth.analyze(ride { _, t, row -> if (t >= 290) row["AFR_WBO2"] = 14.6 })
        val f = r.findings.first { it.code == "wot_lean" }
        assertEquals(Level.DANGER, f.level)
        assertEquals("14.6", f.args[0])
    }

    @Test fun cruiseMixtureDeviationPicksLeanOrRich() {
        assertTrue("afr_lean" in codes(EngineHealth.analyze(ride { _, t, row -> if (t in 100.0..290.0) row["AFR_WBO2"] = 15.5 })))
        assertTrue("afr_rich" in codes(EngineHealth.analyze(ride { _, t, row -> if (t in 100.0..290.0) row["AFR_WBO2"] = 13.0 })))
    }

    @Test fun fuelTrimInjectorDutyAndCrankMisses() {
        assertTrue("trim_add" in codes(EngineHealth.analyze(ride { _, _, row -> row["Fuel_CL"] = 15.0 })))
        assertTrue("trim_cut" in codes(EngineHealth.analyze(ride { _, _, row -> row["Fuel_CL"] = -25.0 })))
        assertTrue("inj_duty" in codes(EngineHealth.analyze(ride { _, t, row -> if (t >= 290) row["Cyl1_Injector_Period_Rate"] = 97.0 })))
        // 8-bit counter: 254 -> 255 is one miss, 255 -> 1 wraps for two more
        val r = EngineHealth.analyze(ride { i, _, row -> row["Miss_CRK_Cnt"] = if (i < 1000) 254.0 else if (i < 2000) 255.0 else 1.0 })
        assertEquals(listOf("3"), r.findings.first { it.code == "miss_crk" }.args)
        // a drop from a low value is an ECU restart, not misses
        val reset = EngineHealth.analyze(ride { i, _, row -> row["Miss_CRK_Cnt"] = if (i < 1000) 5.0 else 0.0 })
        assertFalse("miss_crk" in codes(reset))
    }

    @Test fun unstableIdleAndIdleOffset() {
        val shaky = EngineHealth.analyze(ride { i, t, row -> if (t < 100) row["RPM"] = if (i % 2 == 0) 1200.0 else 1800.0 })
        assertTrue("idle_unstable" in codes(shaky))
        val high = EngineHealth.analyze(ride { _, t, row -> if (t < 100) row["RPM"] = 1900.0 })
        assertTrue("idle_offset" in codes(high))
        assertFalse("idle_unstable" in codes(high))
    }

    @Test fun faultBitsAreListedByCodeAndStatusFlagsIgnored() {
        val r = EngineHealth.analyze(ride { i, _, row -> if (i == 500) { row["Malf7.P0562"] = 1.0 }; row["Malf8.MILReq"] = 1.0 })
        assertEquals(listOf("P0562"), r.findings.first { it.code == "fault_codes" }.args)
    }

    @Test fun missingChannelsAreSkippedNotGuessed() {
        val text = "<VAR ID>\n-1,26\n<VAR GROUP>\nStage_1,Stage_1\n<VAR NAME>\nTime,RPM\n<DATA START>\n" +
            (0 until 2000).joinToString("\n") { "${it * 100.0},3000" } + "\n"
        val r = EngineHealth.analyze(Loga.parse(text.encodeToByteArray()))
        assertEquals(100, r.score)
        assertTrue(r.areas.none { it.checked }, r.areas.toString())
        assertNull(r.metrics.first { it.key == "t_eng_max" }.value)
        assertEquals(setOf("RPM"), r.series.keys)
    }

    @Test fun shortOrEmptyLogsDoNotCrash() {
        assertTrue("short_log" in codes(EngineHealth.analyze(ride(seconds = 30))))
        val empty = EngineHealth.analyze(Loga.parse("<VAR NAME>\nTime,RPM\n<DATA START>\n".encodeToByteArray()))
        assertEquals(0, empty.samples)
        assertTrue("short_log" in codes(empty))
    }

    @Test fun downsampleKeepsLengthAndAverages() {
        val out = EngineHealth.downsample(DoubleArray(1200) { (it / 10).toDouble() })
        assertEquals(EngineHealth.SPARK_POINTS, out.size)
        assertEquals(0f, out.first()); assertEquals(119f, out.last())
    }
}
