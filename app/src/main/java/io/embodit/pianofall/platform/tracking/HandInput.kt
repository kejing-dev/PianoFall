package io.embodit.pianofall.platform.tracking

import android.os.Build
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandPinch
import com.pico.spatial.tracking.hand.HandPose
import com.pico.spatial.tracking.hand.HandTrackingProvider
import io.embodit.pianofall.domain.Vec3
import io.embodit.pianofall.platform.PianoLog

/** One tracked hand in one frame, Stage space: the five fingertips (thumb .. little) and the pinch strength. */
data class HandSample(val right: Boolean, val tips: List<Vec3?>, val pinch: Float) {
    val index: Vec3?
        get() = tips.getOrNull(1)
}

/**
 * Hand joints (`HandTrackingProvider`, Full Space only; SDK doc "hand tracking"), read once per frame on the main
 * thread. Used for the fingertip presses on the virtual piano, the corner taken with the index tip and S21
 * (hands on the keys). Not started on the emulator: there it switched the guest's hand tracking on and the
 * emulator's gaze + pinch stopped reaching apps (Spatial Ruler, 2026-10-02); hands do not exist there anyway.
 */
class HandInput {
    private var provider: HandTrackingProvider? = null
    private var lastSupport: DataProvider.SupportState? = null

    val wanted: Boolean = !onEmulator

    fun start() {
        if (provider != null || !wanted) return
        val p = HandTrackingProvider()
        val result = runCatching { p.start() }
        lastSupport = runCatching { p.supportState }.getOrNull()
        PianoLog.i("TRACK hands start=${result.getOrNull() ?: result.exceptionOrNull()} support=$lastSupport")
        provider = p
    }

    fun stop() {
        provider?.let { runCatching { it.stop() } }
        provider = null
    }

    fun sample(): List<HandSample> {
        val p = provider ?: return emptyList()
        val support = runCatching { p.supportState }.getOrNull()
        if (support != lastSupport) {
            lastSupport = support
            PianoLog.i("TRACK hands support=$support")
        }
        if (support != DataProvider.SupportState.SUPPORTED) return emptyList()
        val data = runCatching { p.latestData }.getOrNull() ?: return emptyList()
        return listOfNotNull(hand(data.right, data.rightPinch, true), hand(data.left, data.leftPinch, false))
    }

    private fun hand(pose: HandPose?, pinch: HandPinch?, right: Boolean): HandSample? {
        val joints = pose?.handJoints ?: return null
        fun at(i: HandJoint.Index): Vec3? = joints.firstOrNull { it.index == i }?.position?.let { Vec3(it.x, it.y, it.z) }?.takeIf { it.isFinite() && it.length > 1e-3f }
        val tips = TIPS.map { at(it) }
        if (tips.all { it == null }) return null
        val strength = if (pinch != null && pinch.isReady) pinch.pinchValue else 0f
        return HandSample(right, tips, strength)
    }

    companion object {
        private val TIPS = listOf(HandJoint.Index.THUMB_TIP, HandJoint.Index.INDEX_TIP, HandJoint.Index.MIDDLE_TIP, HandJoint.Index.RING_TIP, HandJoint.Index.LITTLE_TIP)

        val onEmulator: Boolean = Build.HARDWARE == "ranchu" || Build.DEVICE.contains("emulator") || Build.FINGERPRINT.contains("emulator")
    }
}
