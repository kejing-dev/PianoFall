package io.embodit.pianofall.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D21: the free side panel is fixed in the room, moved only by its handle or 「拉到眼前」. */
class PanelStationTest {
    private val eyes = Vec3(0f, 1.2f, 0f)
    private val ahead = HeadPose(eyes, Quat4.IDENTITY)
    private val turned = HeadPose(eyes, Quat4.axisAngle(Vec3.UP, (Math.PI / 2).toFloat()))

    private fun near(a: Vec3, b: Vec3, eps: Float = 1e-4f) = assertTrue("$a != $b", (a - b).length < eps)

    @Test
    fun turningTheHeadNinetyDegreesLeavesThePanelWhereItIs() {
        val s = PanelStation(0.55f, 5f)
        s.ensure(ahead)
        val at = s.position!!
        val face = s.facing
        assertEquals(0.55f, (at - eyes).length, 1e-3f)
        s.ensure(turned)
        near(at, s.position!!)
        assertEquals(face, s.facing)
    }

    @Test
    fun aDraggedPanelStaysWhereItIsLetGoAndFacesTheUser() {
        val s = PanelStation(0.55f, 5f)
        s.ensure(ahead)
        val at = s.position!!
        s.startDrag()
        s.drag(Vec3(0.1f, 0f, 0f))
        s.drag(Vec3(0.2f, 0.05f, 0f))
        s.drop(eyes)
        val dropped = at + Vec3(0.2f, 0.05f, 0f) * PanelStation.DRAG_GAIN
        near(dropped, s.position!!)
        // its face points back at the eyes
        val toEyes = (eyes - dropped).normalized()
        assertTrue(s.facing.normal.dot(toEyes) > 0.999f)
        s.ensure(turned)
        near(dropped, s.position!!)
    }

    @Test
    fun recallBringsItInFrontOfTheCurrentGaze() {
        val s = PanelStation(0.55f, 5f)
        s.ensure(ahead)
        s.recall(turned)
        val p = s.position!!
        val f = turned.level()
        assertTrue(P2.of(p - eyes).normalized().dot(f) > 0.999f)
        assertTrue(p.y < eyes.y)
        // a cancelled drag goes back
        s.startDrag()
        s.drag(Vec3(0.3f, 0f, 0f))
        s.cancelDrag()
        near(p, s.position!!)
    }
}
