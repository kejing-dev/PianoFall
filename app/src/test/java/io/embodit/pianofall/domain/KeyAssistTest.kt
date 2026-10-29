package io.embodit.pianofall.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.1.2 virtual-piano assist: snapping to the wanted notes, press thresholds, strays, chords. */
class KeyAssistTest {
    private val layout = KeyboardLayout(KeyCount.K61, PianoPlacement.WHITE_W * 36)
    private val w = layout.whiteW
    private val c4 = layout.key(60)!!
    private val e4 = layout.key(64)!!

    @Test
    fun aPressBesideTheWantedKeyLandsOnIt() {
        // D4 is under the finger, C4 is wanted: easy (1 white) and standard (0.5 white, finger 0.3 into D) snap to C4
        val x = c4.x1 + 0.3f * w
        val easy = KeyAssist.resolve(x, -0.02f, layout, setOf(60), AssistLevel.EASY.snapWhites)!!
        assertEquals(60, easy.midi)
        assertTrue(easy.snapped)
        assertEquals(60, KeyAssist.resolve(x, -0.02f, layout, setOf(60), AssistLevel.STANDARD.snapWhites)!!.midi)
        // precise: no snapping, the key under the finger, and it is a stray press
        val precise = KeyAssist.resolve(x, -0.02f, layout, setOf(60), AssistLevel.PRECISE.snapWhites)!!
        assertEquals(62, precise.midi)
        assertTrue(precise.stray)
    }

    @Test
    fun farFromEveryWantedKeyIsAStrayOnTheKeyUnderTheFinger() {
        val r = KeyAssist.resolve(e4.cx + 3 * w, -0.02f, layout, setOf(60), AssistLevel.EASY.snapWhites)!!
        assertFalse(r.snapped)
        assertTrue(r.stray)
        assertEquals(layout.keyAt(e4.cx + 3 * w, -0.02f), r.midi)
    }

    @Test
    fun theNearestWantedKeyWinsAndTheKeyUnderTheFingerFirst() {
        // C4 and E4 wanted, finger on D4's middle: equally far -> either; a bit towards E -> E4
        val r = KeyAssist.resolve(layout.key(62)!!.cx + 0.2f * w, -0.02f, layout, setOf(60, 64), 1f)!!
        assertEquals(64, r.midi)
        val on = KeyAssist.resolve(c4.cx, -0.02f, layout, setOf(60, 62), 1f)!!
        assertEquals(60, on.midi)
        assertFalse(on.snapped)
    }

    @Test
    fun withoutTargetsTheKeyUnderTheFingerOrTheNearestOne() {
        val r = KeyAssist.resolve(c4.cx, -0.02f, layout, emptySet(), 1f)!!
        assertEquals(60, r.midi)
        assertFalse(r.stray)
        // just in front of the keys still counts, far in front does not
        assertNotNull(KeyAssist.resolve(c4.cx, 0.015f, layout, emptySet(), 1f))
        assertNull(KeyAssist.resolve(c4.cx, 0.05f, layout, emptySet(), 1f))
    }

    @Test
    fun hoveringRestingAndSlowSlidingDoNotPress() {
        val f = FingerPress({ layout })
        // a finger that came down slowly and rests: no press
        var t = 0L
        for (y in listOf(0.030f, 0.020f, 0.012f, 0.008f, 0.005f, 0.004f, 0.004f, 0.004f)) {
            assertNull(f.sample(1, Vec3(c4.cx, y, -0.02f), t))
            t += 200
        }
        // resting on the desk, then sliding sideways at desk height: still nothing (never came from above)
        assertNull(f.sample(1, Vec3(c4.cx + w, 0.002f, -0.02f), t + 50))
    }

    @Test
    fun aStrikeFromAbovePressesAndTheSameFingerIsDebounced() {
        val f = FingerPress({ layout })
        assertNull(f.sample(1, Vec3(c4.cx, 0.03f, -0.02f), 0))
        assertTrue(f.sample(1, Vec3(c4.cx, 0.003f, -0.02f), 60) is FingerPress.Change.On)
        assertTrue(f.sample(1, Vec3(c4.cx, 0.02f, -0.02f), 120) is FingerPress.Change.Off)
        // down again 80 ms after the first press: inside the 120 ms debounce of 轻松
        assertNull(f.sample(1, Vec3(c4.cx, 0.002f, -0.02f), 140))
    }

    @Test
    fun aFingerPushedThroughTheVirtualDeskPressesEvenSlowly() {
        val f = FingerPress({ layout })
        f.touchM = FingerPress.TOUCH_VIRTUAL_M
        assertNull(f.sample(2, Vec3(c4.cx, 0.02f, -0.02f), 0))
        assertNull(f.sample(2, Vec3(c4.cx, 0.007f, -0.02f), 150))
        assertTrue(f.sample(2, Vec3(c4.cx, -0.005f, -0.02f), 250) is FingerPress.Change.On)
    }

    @Test
    fun theFingerSnapsThroughItsTargets() {
        val f = FingerPress({ layout }, { setOf(60) })
        assertNull(f.sample(3, Vec3(c4.x1 + 0.4f * w, 0.03f, -0.02f), 0))
        val on = f.sample(3, Vec3(c4.x1 + 0.4f * w, 0.002f, -0.02f), 50) as FingerPress.Change.On
        assertEquals(60, on.midi)
        assertTrue(on.snapped)
    }

    private val song = Song("c", "c", "c", "", "", 1, 60f, 4, Notation.song("C4+E4+G4/2 D4/2"), "test")

    private fun run(p: Performance, sec: Float) {
        var t = 0f
        while (t < sec) {
            p.tick(0.01f)
            t += 0.01f
        }
    }

    @Test
    fun aRolledChordCountsAsOneHitAndStraysAreFree() {
        val a = AssistLevel.EASY
        val p = Performance(song, PlayOptions(HandSel.RIGHT, PlayMode.FOLLOW, 1f), canScore = true, lookAheadSec = 1f, perfectMs = a.perfectMs, lateMs = a.lateMs)
        run(p, 1f * Performance.PREVIEW)
        assertEquals(setOf(60, 64, 67), p.targets())
        assertEquals(Grade.PERFECT, (p.noteOn(60) as PerfEvent.Hit).grade)
        run(p, 0.17f)
        // 170 ms later the third finger lands: graded on the chord's first hit, still perfect
        assertEquals(Grade.PERFECT, (p.noteOn(67) as PerfEvent.Hit).grade)
        // a stray press is free (no wrong, combo kept)
        assertNull(p.noteOn(80, strayIsFree = true))
        val h = p.noteOn(64) as PerfEvent.Hit
        assertEquals(Grade.PERFECT, h.grade)
        assertEquals(3, h.combo)
        // counted when not free
        assertTrue(p.noteOn(80) is PerfEvent.Wrong)
        assertEquals(0, p.combo)
    }

    @Test
    fun easyWindowsAreWider() {
        assertTrue(AssistLevel.EASY.perfectMs > AssistLevel.STANDARD.perfectMs)
        assertTrue(AssistLevel.EASY.snapWhites >= 1f && AssistLevel.STANDARD.snapWhites >= 0.5f && AssistLevel.PRECISE.snapWhites == 0f)
        assertEquals(AssistLevel.EASY, AssistLevel.of(null))
        // a real piano keeps the design windows (STANDARD = 150 / 300)
        assertEquals(Performance.PERFECT_MS, AssistLevel.STANDARD.perfectMs)
        assertEquals(Performance.LATE_MS, AssistLevel.STANDARD.lateMs)
    }
}
