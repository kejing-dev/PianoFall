package io.embodit.pianofall.domain

/**
 * How much the virtual piano helps (v0.1.2, user on the headset: 「按键误触有点多，也不好控制，要不加个模糊逻辑，就是手指在
 * 周围就算按到了，重点是让用户能爽起来」). Settings: 轻松 (default) / 标准 / 精确.
 *
 * - [snapWhites]: a press within this many white-key widths left / right of a key the song wants now lands on that key.
 * - [minDownSpeed] / [pushDepthM]: a press is a fingertip coming down at least this fast, or pushed this far below the
 *   key bed (a virtual desk has nothing to stop the finger); hovering, resting and brushing do nothing.
 * - [debounceMs]: one finger triggers at most once in this time.
 * - [perfectMs] / [lateMs]: timing windows of play-along scoring.
 * - [strayVolume] / [strayIsWrong]: a press far from every wanted note sounds quieter and, below 精确, is not counted wrong.
 */
enum class AssistLevel(
    val tag: String,
    val snapWhites: Float,
    val minDownSpeed: Float,
    val pushDepthM: Float,
    val debounceMs: Long,
    val perfectMs: Float,
    val lateMs: Float,
    val strayVolume: Float,
    val strayIsWrong: Boolean,
) {
    EASY("easy", 1.0f, 0.04f, 0.004f, 120L, 180f, 350f, 0.35f, false),
    STANDARD("standard", 0.5f, 0.06f, 0.005f, 100L, 150f, 300f, 0.6f, false),
    PRECISE("precise", 0f, 0.08f, 0.006f, 80L, 120f, 250f, 1f, true);

    companion object {
        fun of(tag: String?): AssistLevel = entries.firstOrNull { it.tag == tag } ?: EASY
    }
}

/**
 * Which key a press on the virtual piano means (pure; `KeyAssistTest`). [targets] are the keys the song wants now
 * (the notes about to land or waiting). A fingertip within [AssistLevel.snapWhites] white widths of a target key, left
 * or right, takes that key (the nearest target wins, the key under the finger first). With no target near, the key
 * under the finger, or the nearest key when the finger is just in front of / behind the keys.
 */
object KeyAssist {
    data class Resolved(val midi: Int, val snapped: Boolean, val stray: Boolean)

    /** How far in front of the keys (towards the player) and behind them a press still counts, metres. */
    const val FRONT_SLACK_M = 0.02f
    const val BACK_SLACK_M = 0.02f

    fun resolve(x: Float, z: Float, layout: KeyboardLayout, targets: Set<Int>, snapWhites: Float): Resolved? {
        if (z > FRONT_SLACK_M || z < -KeyboardLayout.WHITE_DEPTH - BACK_SLACK_M) return null
        val reach = snapWhites * layout.whiteW
        if (x < -reach - 0.01f || x > layout.length + reach + 0.01f) return null
        val under = layout.keyAt(x.coerceIn(0f, layout.length), z.coerceIn(-KeyboardLayout.WHITE_DEPTH, 0f))
        if (targets.isNotEmpty()) {
            if (under != null && under in targets) return Resolved(under, snapped = false, stray = false)
            val near =
                targets.mapNotNull { m -> layout.key(m)?.let { k -> m to gap(x, k.x0, k.x1) } }
                    .filter { (_, d) -> d <= reach }
                    .minByOrNull { (_, d) -> d }
            if (near != null) return Resolved(near.first, snapped = true, stray = false)
        }
        val key = under ?: nearest(x, layout) ?: return null
        return Resolved(key, snapped = false, stray = targets.isNotEmpty())
    }

    private fun gap(x: Float, x0: Float, x1: Float): Float = if (x < x0) x0 - x else if (x > x1) x - x1 else 0f

    private fun nearest(x: Float, layout: KeyboardLayout): Int? = layout.all.filter { !it.black }.minByOrNull { gap(x, it.x0, it.x1) }?.midi
}

/**
 * Fingers on the virtual piano (design `input.virtualPress`, T23, v0.1.2 assist). The keys stand only 8 mm above the
 * desk, so a press is a fingertip coming down onto the desk: within [touchM] of the desk top and moving down at least
 * [AssistLevel.minDownSpeed], or pushed [AssistLevel.pushDepthM] below it; and it must have come from above (it was
 * higher than the touch height within the last 300 ms): a finger resting or sliding on the desk, or a palm, does
 * nothing. Lifted more than 10 mm -> note off. One finger triggers at most once per [AssistLevel.debounceMs]. Which
 * key: [KeyAssist]. Without a real desk (virtual desk) there is nothing to touch: [touchM] is wider.
 *
 * Positions are keyboard-local (x along the keys, y up from the desk top, z towards the player).
 */
class FingerPress(private val layout: () -> KeyboardLayout?, private val targets: () -> Set<Int> = { emptySet() }) {
    var touchM = TOUCH_REAL_M
    var assist = AssistLevel.EASY

    private class Finger(var y: Float = Float.NaN, var tMs: Long = 0L, var key: Int? = null, var lastOnMs: Long = -10_000L, var aboveMs: Long = -10_000L)

    private val fingers = HashMap<Int, Finger>()

    sealed interface Change {
        data class On(val finger: Int, val midi: Int, val velocity: Float, val snapped: Boolean = false, val stray: Boolean = false) : Change

        data class Off(val finger: Int, val midi: Int) : Change
    }

    /** One fingertip sample; [finger] is a stable id (hand * 5 + finger). */
    fun sample(finger: Int, local: Vec3, nowMs: Long): Change? {
        val f = fingers.getOrPut(finger) { Finger() }
        val dt = (nowMs - f.tMs) / 1000f
        val vy = if (f.y.isNaN() || dt <= 0f || dt > 0.2f) 0f else (local.y - f.y) / dt
        f.y = local.y
        f.tMs = nowMs
        if (local.y > touchM + ABOVE_M) f.aboveMs = nowMs
        val down = f.key
        if (down != null) {
            if (local.y > RELEASE_M) {
                f.key = null
                return Change.Off(finger, down)
            }
            return null
        }
        if (local.y > touchM) return null
        val struck = -vy >= assist.minDownSpeed || local.y <= -assist.pushDepthM
        if (!struck || nowMs - f.aboveMs > CAME_FROM_ABOVE_MS) return null
        if (nowMs - f.lastOnMs < assist.debounceMs) return null
        val l = layout() ?: return null
        val r = KeyAssist.resolve(local.x, local.z, l, targets(), assist.snapWhites) ?: return null
        f.key = r.midi
        f.lastOnMs = nowMs
        return Change.On(finger, r.midi, velocity(-vy), r.snapped, r.stray)
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
        const val ABOVE_M = 0.003f
        const val CAME_FROM_ABOVE_MS = 300L

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
