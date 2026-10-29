package io.embodit.pianofall.domain

import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the free side panel stands (profile D21, v0.1.2): put once in front of the eyes, then fixed in the room
 * and turned to the user only at that moment. It moves only by its handle ([drag] / [drop]) or 「拉到眼前」
 * ([recall]); turning the head does nothing.
 */
class PanelStation(private val distM: Float, private val downDeg: Float) {
    var position: Vec3? = null
        private set
    var facing: PanelFacing = PanelFacing(0f, 0f)
        private set

    private var dragFrom: Vec3? = null

    val placed: Boolean
        get() = position != null

    val dragging: Boolean
        get() = dragFrom != null

    /** Places the panel once (first frame with a head pose); later calls change nothing. */
    fun ensure(head: HeadPose) {
        if (position == null) recall(head)
    }

    /** 「拉到眼前」: [distM] ahead of the level gaze, [downDeg] below it, facing the eyes. */
    fun recall(head: HeadPose) {
        val f = head.level()
        val d = Math.toRadians(downDeg.toDouble()).toFloat()
        val at = head.position + Vec3(f.x, 0f, f.z) * (distM * cos(d)) - Vec3(0f, distM * sin(d), 0f)
        position = at
        facing = FacePanel.facing(at, head.position, null)
        dragFrom = null
    }

    fun startDrag() {
        dragFrom = position
    }

    /** [handMoved] = the hand's travel since the drag started, Stage metres; the panel moves [gain] times that. */
    fun drag(handMoved: Vec3, gain: Float = DRAG_GAIN) {
        val from = dragFrom ?: return
        position = from + handMoved * gain
    }

    /** Let go: stays where it is and turns to face [viewer] once. */
    fun drop(viewer: Vec3) {
        if (dragFrom == null) return
        dragFrom = null
        position?.let { facing = FacePanel.facing(it, viewer, facing.yaw) }
    }

    fun cancelDrag() {
        dragFrom?.let { position = it }
        dragFrom = null
    }

    companion object {
        /** A hand's travel at arm's length moves a panel at 0.55 m by about 1.6 x. */
        const val DRAG_GAIN = 1.6f
    }
}
