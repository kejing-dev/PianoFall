package io.embodit.pianofall.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardTest {
    @Test
    fun whiteCountsMatchTheDesign() {
        assertEquals(52, KeyCount.K88.whites)
        assertEquals(45, KeyCount.K76.whites)
        assertEquals(43, KeyCount.K73.whites)
        assertEquals(36, KeyCount.K61.whites)
        assertEquals(29, KeyCount.K49.whites)
        for (k in KeyCount.entries) assertEquals(k.keys, k.highest - k.lowest + 1)
    }

    @Test
    fun noteNames() {
        assertEquals(60, Keys.parse("C4"))
        assertEquals(69, Keys.parse("A4"))
        assertEquals(61, Keys.parse("C#4"))
        assertEquals(70, Keys.parse("Bb4"))
        assertEquals(63, Keys.parse("Ds4"))
        assertEquals(60, Keys.parse("B#3"))
        assertEquals(440.0, Keys.hz(69), 1e-9)
        assertEquals(261.6256, Keys.hz(60), 1e-3)
    }

    @Test
    fun middleCLiesOnItsWhiteKeyAndBlackKeysSitBetweenWhites() {
        // acceptance: C4 highlight on the C4 visible part for 88 / 73 / 61 / 49
        for (count in listOf(KeyCount.K88, KeyCount.K73, KeyCount.K61, KeyCount.K49)) {
            val l = KeyboardLayout(count, count.stdLength)
            val c4 = l.key(60)!!
            val whitesBelow = (count.lowest until 60).count { !Keys.isBlack(it) }
            assertEquals(whitesBelow * l.whiteW, c4.x0, 1e-4f)
            assertFalse(c4.black)
            for (k in l.all.filter { it.black }) {
                val below = l.key(k.midi - 1)!!
                val above = l.key(k.midi + 1)!!
                assertTrue("black ${k.midi} overlaps ${below.midi}", k.x0 > below.x0)
                assertTrue("black ${k.midi} overlaps ${above.midi}", k.x1 < above.x1)
                assertTrue(k.cx > below.x1 - l.whiteW * 0.5f && k.cx < above.x0 + l.whiteW * 0.5f)
            }
        }
    }

    @Test
    fun keyAtPrefersBlackKeysAtTheBack() {
        val l = KeyboardLayout(KeyCount.K61, KeyCount.K61.stdLength)
        val cs = l.key(61)!!
        assertEquals(61, l.keyAt(cs.cx, -0.10f))
        // the front part of the same spot is the white key C or D
        val front = l.keyAt(cs.cx, -0.02f)
        assertTrue(front == 60 || front == 62)
        assertEquals(null, l.keyAt(-0.01f, -0.02f))
    }

    @Test
    fun lengthSnapFollowsT2() {
        // select 61, 0.81 m -> confirmed; 76 with 1.005 m -> confirmed 76 (not 73); 61 with 1.0 m -> S06
        assertEquals(LengthSnap.Result.Confirmed(KeyCount.K61), LengthSnap.classify(0.81f, KeyCount.K61))
        assertEquals(LengthSnap.Result.Confirmed(KeyCount.K76), LengthSnap.classify(1.005f, KeyCount.K76))
        assertTrue(LengthSnap.classify(1.0f, KeyCount.K61) is LengthSnap.Result.Mismatch)
        val m = LengthSnap.classify(0.74f, KeyCount.K61) as LengthSnap.Result.Mismatch
        assertEquals(KeyCount.K49, m.a)
        assertEquals(KeyCount.K61, m.b)
    }

    @Test
    fun midiRangeT17() {
        // 61 keys: 48 .. 108 -> -12 offset; 38 low -> transposed
        val r = MidiRange.measure(48, 108, KeyCount.K61)
        assertEquals(MidiRange.Result.Offset(-12, KeyCount.K61), r)
        assertTrue(MidiRange.measure(38, 98, KeyCount.K61) is MidiRange.Result.Transposed)
        assertEquals(MidiRange.Result.Offset(0, KeyCount.K88), MidiRange.measure(21, 108, KeyCount.K88))
    }

    @Test
    fun frameFromEndsIsLevelAndRightHanded() {
        val left = Vec3(0.2f, 0.75f, -0.5f)
        val right = Vec3(1.1f, 0.755f, -0.5f)
        val f = KeyboardFrame.fromEnds(left, right, KeyCount.K76)!!
        assertEquals(0.9f, f.length, 1e-5f)
        assertEquals(0.7525f, f.origin.y, 1e-5f)
        // +Z points towards the player (who stands at +Z, looking at -Z)
        assertEquals(1f, f.zAxis.z, 1e-5f)
        val p = f.toWorld(Vec3(0.3f, 0.02f, -0.1f))
        val back = f.toLocal(p)
        assertEquals(0.3f, back.x, 1e-5f)
        assertEquals(-0.1f, back.z, 1e-5f)
        // turning keeps the centre and the length
        val t = f.turned(0.1f)
        assertEquals(f.center.x, t.center.x, 1e-5f)
        assertEquals(f.center.z, t.center.z, 1e-5f)
        assertNotNull(KeyboardFrame.fromEnds(left, right, KeyCount.K76))
    }

    @Test
    fun plateUvTopIsBitmapTop() {
        assertEquals(0f, PlateUv.bitmapRow(PlateUv.V_TOP), 0f)
        assertEquals(1f, PlateUv.bitmapRow(PlateUv.V_BOTTOM), 0f)
    }
}
