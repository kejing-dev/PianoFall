package io.embodit.pianofall.domain

/**
 * Fingers on the virtual piano (design `input.virtualPress`, T23). The keys stand only 8 mm above the desk, so a
 * press is a fingertip coming down onto the desk: within [touchM] of the desk top, moving down faster than
 * 0.05 m/s, inside a key's footprint -> note on (black keys first); lifted more than 10 mm -> note off. One finger
 * retriggers at most every 80 ms. Without a real desk (virtual desk) there is nothing to touch: [touchM] is wider.
 *
 * Positions are keyboard-local (x along the keys, y up from the desk top, z towards the player).
 */
class FingerPress(private val layout: () -> KeyboardLayout?) {
    var touchM = TOUCH_REAL_M

    private class Finger(var y: Float = Float.NaN, var tMs: Long = 0L, var key: Int? = null, var lastOnMs: Long = -10_000L)

    private val fingers = HashMap<Int, Finger>()

    sealed interface Change {
        data class On(val finger: Int, val midi: Int, val velocity: Float) : Change

        data class Off(val finger: Int, val midi: Int) : Change
    }

    /** One fingertip sample; [finger] is a stable id (hand * 5 + finger). */
    fun sample(finger: Int, local: Vec3, nowMs: Long): Change? {
        val f = fingers.getOrPut(finger) { Finger() }
        val dt = (nowMs - f.tMs) / 1000f
        val vy = if (f.y.isNaN() || dt <= 0f || dt > 0.2f) 0f else (local.y - f.y) / dt
        f.y = local.y
        f.tMs = nowMs
        val down = f.key
        if (down != null) {
            if (local.y > RELEASE_M) {
                f.key = null
                return Change.Off(finger, down)
            }
            return null
        }
        if (local.y > touchM || vy > -MIN_DOWN_SPEED) return null
        if (nowMs - f.lastOnMs < DEBOUNCE_MS) return null
        val key = layout()?.keyAt(local.x, local.z) ?: return null
        f.key = key
        f.lastOnMs = nowMs
        return Change.On(finger, key, velocity(-vy))
    }

    /** A finger that is no longer tracked lets go of its key. */
    fun lost(finger: Int): Change? {
        val f = fingers.remove(finger) ?: return null
        return f.key?.let { Change.Off(finger, it) }
    }

    fun clear(): List<Change> {
        val out = fingers.entries.mapNotNull { (id, f) -> f.key?.let { Change.Off(id, it) } }
        fingers.clear()
        return out
    }

    companion object {
        const val TOUCH_REAL_M = 0.004f
        const val TOUCH_VIRTUAL_M = 0.006f
        const val RELEASE_M = 0.010f
        const val MIN_DOWN_SPEED = 0.05f
        const val DEBOUNCE_MS = 80L

        /** Three loudness steps by the speed coming down (design: 力度按下压速度分 3 档). */
        fun velocity(speed: Float): Float = when {
            speed > 0.45f -> 1f
            speed > 0.18f -> 0.78f
            else -> 0.58f
        }
    }
}

/** A ray (Stage metres, unit direction). */
data class Ray(val origin: Vec3, val dir: Vec3) {
    fun at(t: Float): Vec3 = origin + dir * t

    /** Distance along the ray to the horizontal plane at height [y], or null (behind / parallel / beyond [maxM]). */
    fun toLevel(y: Float, maxM: Float = 4f): Float? {
        if (kotlin.math.abs(dir.y) < 1e-4f) return null
        val t = (y - origin.y) / dir.y
        return if (t > 0.02f && t <= maxM) t else null
    }
}

/**
 * The desk under the virtual piano (design `spatialLayout.desk`, `virtualPiano`): a real horizontal plane or the
 * virtual desk (1.20 x 0.60 m, its top 0.45 m below the eyes, straight ahead of the head).
 */
data class Desk(val center: Vec3, val yaw: Float, val width: Float, val depth: Float, val virtual: Boolean, val polygon: List<P2> = emptyList()) {
    val top: Float
        get() = center.y

    /** The desk's own front direction on the floor (towards the user). */
    val front: P2
        get() = P2.dir(yaw)

    val right: P2
        get() = P2(front.z, -front.x)

    /** Its four corners (or the detected polygon) on the floor. */
    val outline: List<P2>
        get() =
            polygon.ifEmpty {
                val c = P2.of(center)
                val r = right * (width / 2f)
                val f = front * (depth / 2f)
                listOf(c - r + f, c + r + f, c + r - f, c - r - f)
            }

    fun contains(p: P2): Boolean = Geo2.inside(p, outline)

    companion object {
        const val VIRTUAL_W = 1.20f
        const val VIRTUAL_D = 0.60f
        const val THICK = 0.02f
        const val BELOW_EYES = 0.45f

        /** The virtual desk in front of the head (D9): its near edge 0.22 m ahead of the eyes. */
        fun virtualAhead(head: HeadPose): Desk {
            val f = head.level()
            val c = P2.of(head.position) + f * (0.22f + VIRTUAL_D / 2f)
            return Desk(c.at(head.position.y - BELOW_EYES), yawOf(f * -1f), VIRTUAL_W, VIRTUAL_D, virtual = true)
        }
    }
}

/**
 * Placing the virtual piano on the desk (T22): where the controller ray meets the desk top, turned so that its
 * front edge faces the user, plus the thumbstick turn. Near a desk edge (front edge within 5 cm, angle < 10°) it
 * snaps: front edge parallel to the edge, 3 cm in from it.
 */
object PianoPlacement {
    const val SNAP_DIST_M = 0.05f
    const val SNAP_DEG = 10f
    const val INSET_M = 0.03f

    /** Body depth from the front edge to the back board (keys 0.15 + back board). */
    const val BODY_DEPTH = 0.22f

    data class Placed(val frame: KeyboardFrame, val snapped: Boolean)

    /**
     * [center] is where the ray met the desk (the middle of the keys' front edge goes there), [facing] is the
     * horizontal direction from the piano towards the user.
     */
    fun place(center: Vec3, facing: P2, count: KeyCount, desk: Desk?): Placed {
        val length = count.whites * WHITE_W
        var front = facing.normalized()
        var c = P2.of(center)
        var snapped = false
        if (desk != null) {
            val edge = nearestEdge(desk.outline, c)
            if (edge != null) {
                val (a, b) = edge
                val along = (b - a).normalized()
                var normal = P2(-along.z, along.x)
                // the edge's outward normal points away from the desk's middle
                val mid = (a + b) * 0.5f
                if ((mid - centroid(desk.outline)).dot(normal) < 0f) normal = normal * -1f
                val angle = Math.toDegrees(kotlin.math.acos(front.dot(normal).coerceIn(-1f, 1f).toDouble())).toFloat()
                val dist = distanceToLine(c, a, b)
                if (angle < SNAP_DEG && dist < SNAP_DIST_M) {
                    front = normal
                    val t = (c - a).dot(along)
                    c = a + along * t - normal * INSET_M
                    snapped = true
                }
            }
        }
        val xAxis = P2(front.z, -front.x) // the player's right, facing the keys
        val origin = c - xAxis * (length / 2f)
        return Placed(KeyboardFrame(origin.at(center.y + KEY_TOP_M), xAxis.at(0f), length, count), snapped)
    }

    /** White key width of the virtual piano (design: standard 23.5 mm). */
    const val WHITE_W = 0.0235f

    /** Key tops stand 8 mm above the desk. */
    const val KEY_TOP_M = 0.008f

    private fun centroid(p: List<P2>): P2 = p.fold(P2.ZERO) { a, v -> a + v } * (1f / p.size)

    private fun nearestEdge(poly: List<P2>, p: P2): Pair<P2, P2>? {
        if (poly.size < 3) return null
        return poly.indices.map { poly[it] to poly[(it + 1) % poly.size] }.minByOrNull { (a, b) -> distanceToLine(p, a, b) }
    }

    private fun distanceToLine(p: P2, a: P2, b: P2): Float {
        val ab = b - a
        val l2 = ab.dot(ab)
        val t = if (l2 < 1e-9f) 0f else ((p - a).dot(ab) / l2).coerceIn(0f, 1f)
        return (p - (a + ab * t)).length
    }
}
