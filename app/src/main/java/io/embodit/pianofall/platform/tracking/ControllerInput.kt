package io.embodit.pianofall.platform.tracking

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerAction
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import io.embodit.pianofall.domain.Quat4
import io.embodit.pianofall.domain.Ray
import io.embodit.pianofall.domain.Vec3
import io.embodit.pianofall.platform.PianoLog
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * The controllers (`ControllerTrackingProvider`, Full Space only; SDK doc "Obtain controller input actions"):
 * pose from `latestData` (read once per frame on the main thread), trigger, buttons and thumbstick from the
 * action listener. That listener runs on the data source's thread: it only counts edges and copies values;
 * the ECS system reads them once per frame.
 *
 * The ray leaves the pose along its -Z; the tool tip of the contact channel is [TIP_M] ahead on that ray
 * (Spatial Ruler's calibrated constant), where the tip dot is drawn, so what you see is what a trigger takes.
 */
class ControllerInput {
    private var provider: ControllerTrackingProvider? = null

    /** One controller this frame (Stage space). */
    data class Hand(val right: Boolean, val position: Vec3, val rotation: Quat4) {
        val forward: Vec3
            get() = rotation.rotate(Vec3.AHEAD).normalized()

        val ray: Ray
            get() = Ray(position, forward)

        val tip: Vec3
            get() = position + forward * TIP_M
    }

    @Volatile private var stickL = 0f
    @Volatile private var stickR = 0f
    @Volatile private var trigL = 0f
    @Volatile private var trigR = 0f
    @Volatile private var atMs = 0L
    @Volatile private var lastTriggerRight = true

    // rising edges, counted on the data thread and consumed per frame
    private val triggerDownL = AtomicInteger()
    private val triggerDownR = AtomicInteger()
    private val triggerUpL = AtomicInteger()
    private val triggerUpR = AtomicInteger()
    private val buttons = AtomicInteger()
    private var seenDownL = 0
    private var seenDownR = 0
    private var seenUpL = 0
    private var seenUpR = 0
    private var seenButtons = 0
    @Volatile private var downL = false
    @Volatile private var downR = false
    @Volatile private var buttonsDown = 0

    /** Debug: a stick value that wins over the controllers until [debugUntilMs] (emulator QA). */
    @Volatile private var debugX = 0f
    @Volatile private var debugUntilMs = 0L

    private val listener =
        ControllerTrackingProvider.ControllerActionListener { a ->
            stickL = a.left.thumbstickValue.x
            stickR = a.right.thumbstickValue.x
            trigL = a.left.triggerValue
            trigR = a.right.triggerValue
            if (a.left.triggerPressed && !downL) {
                triggerDownL.incrementAndGet()
                lastTriggerRight = false
            }
            if (!a.left.triggerPressed && downL) triggerUpL.incrementAndGet()
            if (a.right.triggerPressed && !downR) {
                triggerDownR.incrementAndGet()
                lastTriggerRight = true
            }
            if (!a.right.triggerPressed && downR) triggerUpR.incrementAndGet()
            downL = a.left.triggerPressed
            downR = a.right.triggerPressed
            val b = mask(a.left) or (mask(a.right) shl 8)
            if (b and buttonsDown.inv() != 0) buttons.incrementAndGet()
            buttonsDown = b
            atMs = SystemClock.elapsedRealtime()
        }

    /** A, B, X, Y, grip, thumbstick click: anything but the trigger (S10 "手柄任意键 = 暂停"). */
    private fun mask(c: ControllerAction): Int {
        var m = 0
        if (c.aButtonPressed) m = m or 1
        if (c.bButtonPressed) m = m or 2
        if (c.xButtonPressed) m = m or 4
        if (c.yButtonPressed) m = m or 8
        if (c.gripPressed) m = m or 16
        if (c.thumbstickPressed) m = m or 32
        return m
    }

    fun start() {
        if (provider != null) return
        val p = ControllerTrackingProvider()
        runCatching { p.addControllerActionListener(listener) }.onFailure { PianoLog.w("STICK listener: $it") }
        val result = runCatching { p.start() }
        PianoLog.i("TRACK controller start=${result.getOrNull() ?: result.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() {
        provider?.let { p ->
            runCatching { p.removeControllerActionListener(listener) }
            runCatching { p.stop() }
        }
        provider = null
        stickL = 0f
        stickR = 0f
    }

    private fun supported(): Boolean = runCatching { provider?.supportState }.getOrNull() == DataProvider.SupportState.SUPPORTED

    /** Thumbstick X in [-1, 1] (right +), the larger of the two; 0 when stale. */
    fun stickX(nowMs: Long): Float {
        if (nowMs < debugUntilMs) return debugX
        if (provider == null || !supported() || nowMs - atMs > STALE_MS) return 0f
        return (if (abs(stickR) >= abs(stickL)) stickR else stickL).coerceIn(-1f, 1f)
    }

    fun hands(): List<Hand> {
        val p = provider ?: return emptyList()
        if (!supported()) return emptyList()
        val d = runCatching { p.latestData }.getOrNull() ?: return emptyList()
        fun conv(c: com.pico.spatial.tracking.controller.ControllerPose?, right: Boolean): Hand? {
            c ?: return null
            val v = Vec3(c.position.x, c.position.y, c.position.z)
            if (!v.isFinite() || v.length < 1e-4f) return null
            return Hand(right, v, Quat4(c.rotation.x, c.rotation.y, c.rotation.z, c.rotation.w).normalized())
        }
        return listOfNotNull(conv(d.right, true), conv(d.left, false))
    }

    /** The controller that aims: the one whose trigger was pressed last (the right one at first). */
    fun aiming(): Hand? {
        val h = hands()
        return h.firstOrNull { it.right == lastTriggerRight } ?: h.firstOrNull()
    }

    data class Edges(val downLeft: Boolean, val downRight: Boolean, val upLeft: Boolean, val upRight: Boolean, val button: Boolean)

    /** Trigger presses / releases and other button presses since the last call. */
    fun takeEdges(): Edges {
        val dl = triggerDownL.get()
        val dr = triggerDownR.get()
        val ul = triggerUpL.get()
        val ur = triggerUpR.get()
        val b = buttons.get()
        val e = Edges(dl != seenDownL, dr != seenDownR, ul != seenUpL, ur != seenUpR, b != seenButtons)
        seenDownL = dl
        seenDownR = dr
        seenUpL = ul
        seenUpR = ur
        seenButtons = b
        return e
    }

    fun triggerHeld(right: Boolean): Boolean = if (right) downR else downL

    fun debugHold(value: Float, ms: Long) {
        debugX = value.coerceIn(-1f, 1f)
        debugUntilMs = SystemClock.elapsedRealtime() + ms
    }

    fun status(): String = "support=${runCatching { provider?.supportState }.getOrNull()} trig=${"%.2f".format(trigR)}/${"%.2f".format(trigL)}"

    companion object {
        const val STALE_MS = 500L

        /** The virtual tip: 6 cm ahead of the controller pose (Spatial Ruler's contact channel). */
        const val TIP_M = 0.06f
    }
}
