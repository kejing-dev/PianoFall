package io.embodit.pianofall.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceTest {
    private val song = Song("t", "t", "t", "", "", 1, 60f, 4, Notation.song("C4 D4 E4 F4 | G4/4"), "test")

    private fun run(p: Performance, sec: Float, step: Float = 0.01f): List<PerfEvent> {
        val out = ArrayList<PerfEvent>()
        var t = 0f
        while (t < sec) {
            out += p.tick(step)
            t += step
        }
        return out
    }

    @Test
    fun allSongsParseAndFitA61KeyKeyboard() {
        assertEquals(12, Library.all.size)
        assertEquals(listOf(1, 2, 3, 4), Library.byLevel.keys.toList())
        for (s in Library.all) {
            assertTrue(s.id, s.notes.isNotEmpty())
            val shift = s.octaveShiftFor(KeyCount.K61)
            assertTrue("${s.id} ${s.lowest + shift}", s.lowest + shift >= KeyCount.K61.lowest)
            assertTrue("${s.id} ${s.highest + shift}", s.highest + shift <= KeyCount.K61.highest)
            assertTrue("${s.id} ${s.lengthSec}", s.lengthSec in 15f..120f)
        }
    }

    @Test
    fun halfSpeedTakesTwiceAsLong() {
        // acceptance: 50 % speed, the same bars take 2x (± 2 %)
        val full = Performance(song, PlayOptions(speed = 1f), canScore = false, lookAheadSec = 3f)
        val half = Performance(song, PlayOptions(speed = 0.5f), canScore = false, lookAheadSec = 3f)
        val a = timeTo(full, 2f)
        val b = timeTo(half, 2f)
        assertEquals(2f, b / a, 0.04f)
    }

    private fun timeTo(p: Performance, songT: Float): Float {
        var real = 0f
        while (p.t < songT) {
            p.tick(0.005f)
            real += 0.005f
        }
        return real
    }

    @Test
    fun followScoresOnTimeEarlyMissedWrong() {
        val p = Performance(song, PlayOptions(HandSel.RIGHT, PlayMode.FOLLOW, 1f), canScore = true, lookAheadSec = 3f)
        // first note (C4) lands at song t = 0, i.e. after 85 % of the 3 s look-ahead
        run(p, 3.0f * Performance.PREVIEW)
        assertEquals(Grade.PERFECT, (p.noteOn(60) as PerfEvent.Hit).grade)
        run(p, 0.8f) // t = 0.8: D4 lands at 1.0 -> 200 ms early
        assertEquals(Grade.EARLY, (p.noteOn(62) as PerfEvent.Hit).grade)
        assertTrue(p.noteOn(71) is PerfEvent.Wrong)
        // E4 (t = 2) and F4 (t = 3) are missed, G4 (t = 4) on time
        run(p, 3.2f)
        p.noteOn(67)
        val events = run(p, 10f)
        val fin = events.filterIsInstance<PerfEvent.Finished>().single()
        val s = fin.score!!
        assertEquals(5, s.total)
        assertEquals(2, s.perfect)
        assertEquals(1, s.earlyLate)
        assertEquals(2, s.missed)
        assertEquals(1, s.wrong)
        assertEquals((2 + 0.5f) / 5f, s.accuracy, 1e-6f)
        assertEquals(0, s.stars)
    }

    @Test
    fun waitModeStopsTheClockUntilTheGroupIsPlayed() {
        val chord = Song("c", "c", "c", "", "", 1, 60f, 4, Notation.song("C4+E4/2 G4/2", "C3/4"), "test")
        val p = Performance(chord, PlayOptions(HandSel.BOTH, PlayMode.WAIT, 1f), canScore = true, lookAheadSec = 2f)
        val ev = run(p, 3f)
        val w = ev.filterIsInstance<PerfEvent.Waiting>().first()
        assertTrue(w.on)
        assertEquals(setOf(60, 64, 48), w.keys)
        assertEquals(0f, p.t, 1e-4f)
        assertTrue(p.noteOn(60) is PerfEvent.Hit)
        assertTrue(p.noteOn(62) is PerfEvent.Wrong)
        assertTrue(p.noteOn(64) is PerfEvent.Hit)
        assertTrue(p.waiting)
        val resumed = p.noteOn(48)
        assertTrue(resumed is PerfEvent.Waiting && !resumed.on)
        run(p, 1.9f)
        // 200 ms before G4 lands: already counts (window 250 ms), the clock does not stop for it
        assertTrue(p.noteOn(67) is PerfEvent.Hit)
        val rest = run(p, 10f)
        assertTrue(rest.none { it is PerfEvent.Waiting && it.on })
        val s = rest.filterIsInstance<PerfEvent.Finished>().single().score
        assertNotNull(s)
        assertEquals(1, s!!.wrong)
    }

    @Test
    fun octaveHintAfterThreePressesAnOctaveOff() {
        val one = Song("o", "o", "o", "", "", 1, 60f, 4, Notation.song("C4/4"), "test")
        val p = Performance(one, PlayOptions(HandSel.RIGHT, PlayMode.WAIT, 1f), canScore = true, lookAheadSec = 1f)
        run(p, 1.1f * Performance.PREVIEW)
        assertTrue(p.noteOn(48) is PerfEvent.Wrong)
        assertTrue(p.noteOn(48) is PerfEvent.Wrong)
        assertEquals(PerfEvent.OctaveHint(12), p.noteOn(48))
    }

    @Test
    fun otherHandIsAutoPlayedQuieter() {
        val two = Song("h", "h", "h", "", "", 1, 60f, 4, Notation.song("C5/1", "C3/1"), "test")
        val p = Performance(two, PlayOptions(HandSel.RIGHT, PlayMode.FOLLOW, 1f), canScore = true, lookAheadSec = 1f)
        val ev = run(p, 1.05f * Performance.PREVIEW)
        val auto = ev.filterIsInstance<PerfEvent.AutoOn>()
        assertEquals(listOf(PerfEvent.AutoOn(48, Performance.OTHER_HAND_VOLUME)), auto)
    }

    @Test
    fun starsThresholds() {
        assertEquals(3, Score.stars(0.95f))
        assertEquals(2, Score.stars(0.8f))
        assertEquals(1, Score.stars(0.6f))
        assertEquals(0, Score.stars(0.59f))
    }
}
