package io.embodit.pianofall.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PressTest {
    private val layout = KeyboardLayout(KeyCount.K61, PianoPlacement.WHITE_W * 36)
    private val c4 = layout.key(60)!!

    @Test
    fun aFingertipComingDownOntoTheDeskPressesTheKey() {
        val f = FingerPress({ layout })
        assertNull(f.sample(1, Vec3(c4.cx, 0.03f, -0.02f), 0))
        assertNull(f.sample(1, Vec3(c4.cx, 0.015f, -0.02f), 50))
        val on = f.sample(1, Vec3(c4.cx, 0.002f, -0.02f), 100)
        assertTrue(on is FingerPress.Change.On)
        assertEquals(60, (on as FingerPress.Change.On).midi)
        // resting on the desk: nothing more
        assertNull(f.sample(1, Vec3(c4.cx, 0.002f, -0.02f), 150))
        // lifted 12 mm: off
        assertEquals(FingerPress.Change.Off(1, 60), f.sample(1, Vec3(c4.cx, 0.012f, -0.02f), 200))
    }

    @Test
    fun aRestingFingerDoesNotPress() {
        val f = FingerPress({ layout })
        assertNull(f.sample(2, Vec3(c4.cx, 0.003f, -0.02f), 0))
        assertNull(f.sample(2, Vec3(c4.cx, 0.003f, -0.02f), 50))
    }

    @Test
    fun theVirtualPianoSnapsToTheDeskEdge() {
        // a desk 1.2 x 0.6 m at the origin, its front (towards the user, +Z) edge at z = 0.3
        val desk = Desk(Vec3(0f, 0.72f, 0f), 0f, 1.2f, 0.6f, virtual = true)
        val near = PianoPlacement.place(Vec3(0.05f, 0.72f, 0.27f), P2(0.08f, 1f).normalized(), KeyCount.K61, desk)
        assertTrue(near.snapped)
        assertEquals(1f, near.frame.zAxis.z, 1e-4f)
        assertEquals(0.3f - PianoPlacement.INSET_M, near.frame.origin.z, 1e-4f)
        assertEquals(0.72f + PianoPlacement.KEY_TOP_M, near.frame.origin.y, 1e-5f)
        val far = PianoPlacement.place(Vec3(0.05f, 0.72f, 0.1f), P2(0f, 1f), KeyCount.K61, desk)
        assertTrue(!far.snapped)
        // the keys' front edge middle is where the ray met the desk
        assertEquals(0.05f, far.frame.center.x, 1e-4f)
        assertEquals(KeyCount.K61.whites * PianoPlacement.WHITE_W, far.frame.length, 1e-5f)
    }
}
