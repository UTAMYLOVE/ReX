package rx.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rx.session.RevFlash.State

class RevFlashTest {
    /** Feeds a constant rpm/tps every 20 ms from [from] to [to] and returns the last output. */
    private fun RevFlash.run(from: Long, to: Long, rpm: Double, tps: Double): RevFlash.Output {
        var o = update(from, rpm, tps)
        var t = from + 20
        while (t <= to) { o = update(t, rpm, tps); t += 20 }
        return o
    }

    @Test fun cruiseNeverFlashes() {
        val f = RevFlash()
        val o = f.run(0, 10_000, 6_000.0, 40.0)
        assertEquals(State.NORMAL, o.state)
        assertTrue(o.on)
    }

    @Test fun revNeedsToHoldBeforeFlashing() {
        val f = RevFlash()
        assertEquals(State.NORMAL, f.run(0, 60, 9_850.0, 50.0).state)   // 60 ms: not yet
        assertEquals(State.REV, f.run(80, 200, 9_850.0, 50.0).state)
    }

    @Test fun revFlashesAtAbout2Point7HzAndNeverAboveThree() {
        val f = RevFlash()
        f.run(0, 120, 9_900.0, 50.0)
        val entry = 120L - (120L - 100L) // entered at the first sample >= 100 ms
        var transitions = 0
        var last = f.update(200, 9_900.0, 50.0).on
        var maxPerSecond = 0
        val window = ArrayDeque<Long>()
        var t = 201L
        while (t < 5_200) {
            val on = f.update(t, 9_900.0, 50.0).on
            if (on != last) {
                transitions++
                if (on) { window.addLast(t); while (window.first() <= t - 1000) window.removeFirst(); maxPerSecond = maxOf(maxPerSecond, window.size) }
            }
            last = on; t++
        }
        assertTrue(entry >= 0)
        assertTrue(maxPerSecond <= 3, "flashes per second: $maxPerSecond")
        assertTrue(transitions >= 25, "expected sustained flashing, transitions=$transitions")
    }

    @Test fun revKeepsFlashingThroughLimiterBounceAndExitsAfterHold() {
        val f = RevFlash()
        f.run(0, 200, 9_900.0, 100.0)
        // limiter bounce: dips to 9,600 for 200 ms must not stop the flash
        assertEquals(State.REV, f.run(220, 420, 9_600.0, 100.0).state)
        assertEquals(State.REV, f.run(440, 1_500, 9_900.0, 100.0).state)
        // off the throttle and down to 7,000 for 400 ms: leaves REV
        assertEquals(State.NORMAL, f.run(1_520, 2_100, 7_000.0, 10.0).state)
    }

    @Test fun fullThrottleFlashesThreeTimesThenStaysOn() {
        val f = RevFlash()
        assertEquals(State.WOT_BURST, f.run(0, 260, 8_600.0, 100.0).state)
        var ons = 0; var last = true
        var t = 280L
        while (t < 3_000) {
            val o = f.update(t, 8_600.0, 100.0)
            if (o.on && !last && o.state == State.WOT_BURST) ons++
            last = o.on; t += 10
        }
        assertEquals(2, ons, "the first ON phase starts at entry, so two more rising edges make three flashes")
        val o = f.update(3_000, 8_600.0, 100.0)
        assertEquals(State.WOT_LATCH, o.state)
        assertTrue(o.on)
        // stays latched while the CVT holds rpm at full throttle
        assertEquals(State.WOT_LATCH, f.run(3_020, 12_000, 8_700.0, 100.0).state)
        // throttle closed for 500 ms re-arms
        assertEquals(State.NORMAL, f.run(12_020, 12_700, 6_000.0, 20.0).state)
        assertEquals(State.WOT_BURST, f.run(12_720, 13_000, 8_600.0, 100.0).state)
    }

    @Test fun continuousModeKeepsFlashing() {
        val f = RevFlash(RevFlash.Settings(wot = RevFlash.Wot.CONTINUOUS))
        assertEquals(State.WOT_BURST, f.run(0, 8_000, 8_600.0, 100.0).state)
    }

    @Test fun wotOffAndFlashOffSettings() {
        val off = RevFlash(RevFlash.Settings(wot = RevFlash.Wot.OFF))
        assertEquals(State.NORMAL, off.run(0, 3_000, 8_600.0, 100.0).state)
        val steady = RevFlash(RevFlash.Settings(flash = false))
        val o = steady.run(0, 3_000, 9_950.0, 100.0)
        assertEquals(State.REV, o.state)
        assertTrue(o.on)
        assertFalse(o.dimmed)
    }

    @Test fun revBeatsWotAndHandsBackToLatch() {
        val f = RevFlash()
        f.run(0, 260, 8_600.0, 100.0)
        assertEquals(State.REV, f.run(280, 600, 9_900.0, 100.0).state)
        assertEquals(State.WOT_LATCH, f.run(620, 2_500, 9_300.0, 100.0).state)
    }

    @Test fun customRedlineMovesThresholds() {
        val f = RevFlash(RevFlash.Settings(redline = 9_000))
        assertEquals(State.REV, f.run(0, 200, 8_850.0, 30.0).state)
    }

    @Test fun missingDataResets() {
        val f = RevFlash()
        f.run(0, 200, 9_900.0, 100.0)
        assertEquals(State.NORMAL, f.update(220, null, null).state)
    }
}

class MapWatchTest {
    @Test fun firstValueIsNotAChange() {
        val w = MapWatch()
        assertNull(w.onSample(0, 2))
        assertEquals(2, w.current)
    }

    @Test fun unexpectedChangeIsReportedAfterItSettles() {
        val w = MapWatch()
        w.onSample(0, 2)
        assertNull(w.onSample(100, 3))
        assertNull(w.onSample(300, 3))
        val c = assertNotNull(w.onSample(420, 3))
        assertEquals(2, c.from); assertEquals(3, c.to); assertFalse(c.local)
        assertNull(w.onSample(500, 3))
    }

    @Test fun glitchIsIgnored() {
        val w = MapWatch()
        w.onSample(0, 2)
        assertNull(w.onSample(100, 3))
        assertNull(w.onSample(150, 2))
        assertNull(w.onSample(1_000, 2))
        assertEquals(2, w.current)
    }

    @Test fun changeRequestedHereIsLocal() {
        val w = MapWatch()
        w.onSample(0, 1)
        w.expect(3, 1_000)
        w.onSample(1_200, 3)
        val c = assertNotNull(w.onSample(1_600, 3))
        assertTrue(c.local)
        // a later change back is not local any more
        w.onSample(9_000, 1)
        assertFalse(assertNotNull(w.onSample(9_400, 1)).local)
    }

    @Test fun staleExpectationIsNotLocal() {
        val w = MapWatch()
        w.onSample(0, 1)
        w.expect(2, 0)
        w.onSample(7_000, 2)
        assertFalse(assertNotNull(w.onSample(7_400, 2)).local)
    }

    @Test fun outOfRangeValuesIgnored() {
        val w = MapWatch()
        assertNull(w.onSample(0, 0)); assertNull(w.onSample(0, null)); assertNull(w.current)
    }
}

class HealthStoreTest {
    @Test fun roundTrip() {
        val r = EngineHealth.Report(
            72, EngineHealth.Level.WARN, 612.4, 580.0, 9_800,
            listOf(EngineHealth.AreaScore(EngineHealth.Area.COOLING, 76, EngineHealth.Level.WARN, true), EngineHealth.AreaScore(EngineHealth.Area.IDLE, 100, EngineHealth.Level.OK, false)),
            listOf(EngineHealth.Finding(EngineHealth.Area.COOLING, EngineHealth.Level.WARN, "temp_warn", listOf("120", "203")), EngineHealth.Finding(EngineHealth.Area.FAULTS, EngineHealth.Level.WARN, "fault_codes", listOf("P0562, P0117"))),
            listOf(EngineHealth.Metric("t_eng_max", 120.0, "°C", 0, EngineHealth.Level.WARN), EngineHealth.Metric("afr_dev", null, "AFR", 2)),
            mapOf("RPM" to floatArrayOf(1f, 2f)),
        )
        val d = assertNotNull(HealthStore.decode(HealthStore.encode(r)))
        assertEquals(72, d.score); assertEquals(EngineHealth.Level.WARN, d.level)
        assertEquals(612.4, d.durationSec); assertEquals(9_800, d.samples)
        assertEquals(r.areas, d.areas); assertEquals(r.findings, d.findings); assertEquals(r.metrics, d.metrics)
        assertTrue(d.series.isEmpty())
    }

    @Test fun rejectsOtherText() { assertNull(HealthStore.decode("hello\tworld")) }
}
