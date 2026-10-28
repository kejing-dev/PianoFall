package io.embodit.pianofall.platform.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.embodit.pianofall.platform.PianoLog
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The piano's voice (design `audio`): Salamander Grand Piano (Yamaha C5, Alexander Holm, CC-BY 3.0), one sample
 * every minor third from A0 to C8 (30 files in assets/piano, one velocity layer). A key between two samples plays
 * the nearest one faster or slower by 2^(semitones / 12): at most one semitone, so the samples' own A4 = 440 Hz
 * tuning holds for every key. Everything is loaded into one SoundPool at start (no decoding at a key press);
 * 16 voices, the oldest is cut first; a released key fades out over 120 ms.
 *
 * Main thread only; [release] on every path that ends the scene.
 */
class PianoAudio(context: Context) {
    private val pool: SoundPool =
        SoundPool.Builder()
            .setMaxStreams(MAX_VOICES)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .build()
    private val main = Handler(Looper.getMainLooper())
    private val samples = IntArray(SAMPLE_COUNT)
    private val loaded = HashSet<Int>()
    private var click = 0

    private class Voice(val stream: Int, val midi: Int, val startMs: Long, var volume: Float)

    private val voices = ArrayList<Voice>()

    /** Master volume 0..1 and mute (D15), set from the settings. */
    var volume = 0.7f
    var muted = false

    /** Key press -> play() latencies of the last presses (ms), for the first-use log (p95). */
    private val latencies = ArrayList<Long>()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded += id }
        val assets = context.assets
        for (i in 0 until SAMPLE_COUNT) {
            val name = sampleName(LOWEST + i * STEP)
            runCatching { assets.openFd("piano/$name.ogg").use { fd -> samples[i] = pool.load(fd, 1) } }
                .onFailure { PianoLog.e("AUDIO load $name failed: $it", it) }
        }
        click = runCatching { pool.load(clickFile(context).absolutePath, 1) }.getOrDefault(0)
        PianoLog.i("AUDIO loading $SAMPLE_COUNT samples")
    }

    val ready: Boolean
        get() = loaded.size >= SAMPLE_COUNT

    /**
     * Sounds [midi] at [velocity] (0..1). [pressedAtMs] is when the key went down (elapsedRealtime), for the
     * latency log. Returns false when muted or not loaded yet.
     */
    fun noteOn(midi: Int, velocity: Float, pressedAtMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (muted || midi !in LOWEST..HIGHEST) return false
        val i = ((midi - LOWEST) / STEP.toFloat()).roundToInt().coerceIn(0, SAMPLE_COUNT - 1)
        val id = samples[i]
        if (id == 0 || id !in loaded) return false
        // the same key again: the old voice stops (a real piano re-strikes the same string)
        voices.filter { it.midi == midi }.forEach { stop(it) }
        if (voices.size >= MAX_VOICES) voices.minByOrNull { it.startMs }?.let { stop(it) }
        val semis = midi - (LOWEST + i * STEP)
        val rate = 2.0.pow(semis / 12.0).toFloat()
        val v = (volume * velocity).coerceIn(0f, 1f)
        val stream = pool.play(id, v, v, 1, 0, rate)
        if (stream == 0) return false
        val now = SystemClock.elapsedRealtime()
        voices += Voice(stream, midi, now, v)
        latencies += now - pressedAtMs
        if (latencies.size >= 50) logLatency()
        return true
    }

    /** Lets go of [midi]: the voice fades over 120 ms (no sustain pedal in v1). */
    fun noteOff(midi: Int) {
        val v = voices.lastOrNull { it.midi == midi } ?: return
        voices.remove(v)
        fade(v.stream, v.volume, 0)
    }

    private fun fade(stream: Int, from: Float, step: Int) {
        if (step >= FADE_STEPS) {
            pool.stop(stream)
            return
        }
        val k = 1f - (step + 1f) / FADE_STEPS
        pool.setVolume(stream, from * k, from * k)
        main.postDelayed({ fade(stream, from, step + 1) }, FADE_MS / FADE_STEPS)
    }

    private fun stop(v: Voice) {
        voices.remove(v)
        pool.stop(v.stream)
    }

    fun allOff() {
        voices.toList().forEach { noteOff(it.midi) }
    }

    /** The metronome's tick (a short synthesized click), quiet. */
    fun tick(accent: Boolean) {
        if (muted || click == 0 || click !in loaded) return
        val v = volume * if (accent) 0.5f else 0.32f
        pool.play(click, v, v, 0, 0, if (accent) 1.25f else 1f)
    }

    fun logLatency() {
        if (latencies.isEmpty()) return
        val s = latencies.sorted()
        val p95 = s[((s.size - 1) * 0.95f).toInt()]
        PianoLog.i("PIANO latency n=${s.size} p50=${s[s.size / 2]}ms p95=${p95}ms (key -> play)")
        latencies.clear()
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        logLatency()
        runCatching { pool.release() }
        voices.clear()
    }

    companion object {
        const val LOWEST = 21
        const val HIGHEST = 108
        const val STEP = 3
        const val SAMPLE_COUNT = 30
        const val MAX_VOICES = 16
        const val FADE_MS = 120L
        const val FADE_STEPS = 4

        private val NAMES = arrayOf("C", "Cs", "D", "Ds", "E", "F", "Fs", "G", "Gs", "A", "As", "B")

        /** File name of the sample at [midi]: A4, Ds5, Fs2 (as in the Tone.js Salamander set). */
        fun sampleName(midi: Int): String = NAMES[midi % 12] + (midi / 12 - 1)

        /** A 25 ms woodblock-like click, written once into the cache. */
        private fun clickFile(context: Context): File {
            val f = File(context.cacheDir, "click.wav")
            if (f.exists() && f.length() > 44) return f
            val rate = 44100
            val n = rate * 25 / 1000
            val pcm = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) {
                val t = i / rate.toFloat()
                val s = sin(2 * PI * 1800 * t) * exp(-t * 180.0) * 0.8
                pcm.putShort((s * Short.MAX_VALUE).toInt().toShort())
            }
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(n * 2)
            FileOutputStream(f).use {
                it.write(header.array())
                it.write(pcm.array())
            }
            return f
        }
    }
}
