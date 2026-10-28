package io.embodit.pianofall.ui.piano.scene

import io.embodit.pianofall.domain.HeadPose
import io.embodit.pianofall.domain.P2
import io.embodit.pianofall.domain.Vec3
import io.embodit.pianofall.platform.PianoLog
import java.util.Locale
import kotlin.math.acos

/**
 * Lazy follow (Spatial Ruler T11): the panel rests where it is; once its centre has been more than
 * [windowDeg] off the view direction, or nearer than [nearM] or farther than [farM], for 0.4 s, it
 * slides (0.4 s, ease-in-out cubic) to the rest pose of that moment.
 */
internal class LazyFollow(private val name: String, private val rest: (HeadPose) -> Vec3, private val windowDeg: Float, private val nearM: Float, private val farM: Float) {
    private var at: Vec3? = null
    private var from = Vec3.ZERO
    private var to = Vec3.ZERO
    private var slideStartMs = NEVER
    private var outSinceMs = NEVER
    private var lastWritten: Vec3? = null

    fun reset() {
        at = null
        slideStartMs = NEVER
        outSinceMs = NEVER
        lastWritten = null
    }

    fun step(head: HeadPose, nowMs: Long, hold: Boolean): Vec3? {
        val current = at
        if (current == null) {
            val p = rest(head)
            at = p
            PianoLog.i("FOLLOW $name park pos=${PianoLog.pos(p)}")
            return write(p)
        }
        if (slideStartMs != NEVER) {
            val q = ((nowMs - slideStartMs) / SLIDE_MS).coerceIn(0f, 1f)
            val e = if (q < 0.5f) 4f * q * q * q else 1f - (-2f * q + 2f).let { it * it * it } / 2f
            val p = from.lerp(to, e)
            if (q >= 1f) {
                slideStartMs = NEVER
                at = to
            }
            return write(p)
        }
        // yaw only: the angle is measured in the horizontal plane
        val toPanel = current - head.position
        val dist = toPanel.length
        val flat = P2.of(toPanel).normalized()
        val away = Math.toDegrees(acos(flat.dot(head.level()).coerceIn(-1f, 1f).toDouble())).toFloat()
        val out = away > windowDeg || dist < nearM || dist > farM
        if (!out || hold) {
            outSinceMs = NEVER
        } else if (outSinceMs == NEVER) {
            outSinceMs = nowMs
        } else if (nowMs - outSinceMs >= OUT_MS) {
            from = current
            to = rest(head)
            slideStartMs = nowMs
            outSinceMs = NEVER
            PianoLog.i(String.format(Locale.US, "FOLLOW %s slide angle=%.1f dist=%.2f to=%s", name, away, dist, PianoLog.pos(to)))
        }
        return write(current)
    }

    private fun write(p: Vec3): Vec3? {
        if (p == lastWritten) return null
        lastWritten = p
        return p
    }

    companion object {
        private const val NEVER = -1L
        private const val OUT_MS = 400L
        private const val SLIDE_MS = 400f
    }
}
