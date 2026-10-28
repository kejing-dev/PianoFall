package io.embodit.pianofall.platform.midi

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.embodit.pianofall.platform.PianoLog

/**
 * USB MIDI from a digital piano (design `container.notes`, S15): the Android public API `android.media.midi`.
 * Whether it works on PICO is not confirmed (first device use logs it); a missing service means "no MIDI".
 * v1 opens the first device that has an output port; Bluetooth MIDI is v1.1. Note-ons come in on a binder
 * thread and are posted to the main thread with the time they arrived.
 */
class MidiInput(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val manager: MidiManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) runCatching { context.getSystemService(Context.MIDI_SERVICE) as? MidiManager }.getOrNull() else null
    private var device: MidiDevice? = null
    private var port: MidiOutputPort? = null
    private var started = false
    private var noteOns = 0

    /** The service exists on this device. */
    val available: Boolean
        get() = manager != null

    /** Name of the open device, or null. */
    @Volatile var deviceName: String? = null
        private set

    var onNote: ((midi: Int, velocity: Int, on: Boolean, atMs: Long) -> Unit)? = null
    var onDevice: ((name: String?) -> Unit)? = null

    private val callback =
        object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) {
                if (port == null) open(info)
            }

            override fun onDeviceRemoved(info: MidiDeviceInfo) {
                if (device?.info?.id == info.id) close("removed")
            }
        }

    fun start() {
        val m = manager
        PianoLog.i("PIANO midi service=${m != null}")
        if (m == null || started) return
        started = true
        @Suppress("DEPRECATION")
        runCatching { m.registerDeviceCallback(callback, main) }
        @Suppress("DEPRECATION")
        runCatching { m.devices.firstOrNull { it.outputPortCount > 0 }?.let { open(it) } }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { manager?.unregisterDeviceCallback(callback) }
        close("stop")
    }

    private fun open(info: MidiDeviceInfo) {
        val m = manager ?: return
        if (info.outputPortCount == 0) return
        val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI"
        m.openDevice(info, { d ->
            if (d == null || !started) {
                runCatching { d?.close() }
                return@openDevice
            }
            val p = d.openOutputPort(0)
            if (p == null) {
                runCatching { d.close() }
                return@openDevice
            }
            p.connect(receiver)
            device = d
            port = p
            deviceName = name
            PianoLog.i("PIANO midi device=$name")
            onDevice?.invoke(name)
        }, main)
    }

    private fun close(why: String) {
        runCatching { port?.disconnect(receiver) }
        runCatching { port?.close() }
        runCatching { device?.close() }
        val had = deviceName != null
        port = null
        device = null
        deviceName = null
        if (had) {
            PianoLog.i("PIANO midi closed why=$why noteOns=$noteOns")
            onDevice?.invoke(null)
        }
    }

    private val receiver =
        object : MidiReceiver() {
            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                val at = SystemClock.elapsedRealtime()
                var i = offset
                val end = offset + count
                while (i < end) {
                    val status = msg[i].toInt() and 0xFF
                    val type = status and 0xF0
                    if (type == 0x90 || type == 0x80) {
                        if (i + 2 >= end) break
                        val note = msg[i + 1].toInt() and 0x7F
                        val vel = msg[i + 2].toInt() and 0x7F
                        val on = type == 0x90 && vel > 0
                        if (on) noteOns++
                        main.post { onNote?.invoke(note, vel, on, at) }
                        i += 3
                    } else {
                        // other messages: skip to the next status byte
                        i++
                        while (i < end && (msg[i].toInt() and 0x80) == 0) i++
                    }
                }
            }
        }
}
