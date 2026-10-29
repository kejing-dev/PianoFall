package io.embodit.pianofall.domain

import kotlin.math.abs

/** The three ways to play along (design `guidance`): listen once, play along (scored), wait for me. */
enum class PlayMode(val tag: String) { LISTEN("listen"), FOLLOW("follow"), WAIT("wait") }

/** Which hand practises; the other hand's notes are grey, never wait and never light a key. */
enum class HandSel(val tag: String) {
    RIGHT("right"),
    LEFT("left"),
    BOTH("both");

    fun practises(part: HandPart): Boolean = this == BOTH || (this == RIGHT && part == HandPart.RIGHT) || (this == LEFT && part == HandPart.LEFT)
}

/** The four speeds (T13): only the clock changes, the geometry stays. */
val SPEEDS = listOf(0.3f, 0.5f, 0.75f, 1f)

data class PlayOptions(val hands: HandSel = HandSel.RIGHT, val mode: PlayMode = PlayMode.LISTEN, val speed: Float = 1f)

/** One note as the performance sees it: song seconds, the key it lands on, whether the player plays it. */
data class PlayNote(val index: Int, val start: Float, val end: Float, val midi: Int, val hand: HandPart, val practice: Boolean)

/** The result of one run (design `scoring`). */
data class Score(
    val total: Int,
    val perfect: Int,
    val earlyLate: Int,
    val missed: Int,
    val wrong: Int,
    /** WAIT: mean seconds the clock stood still per group. */
    val meanWaitSec: Float = 0f,
) {
    val accuracy: Float
        get() = if (total == 0) 0f else (perfect + 0.5f * earlyLate) / total

    val stars: Int
        get() = stars(accuracy)

    companion object {
        fun stars(accuracy: Float): Int = when {
            accuracy >= 0.95f -> 3
            accuracy >= 0.80f -> 2
            accuracy >= 0.60f -> 1
            else -> 0
        }
    }
}

sealed interface PerfEvent {
    /** A note reached the landing line. */
    data class Landed(val note: PlayNote) : PerfEvent

    /** The app sounds a note itself (listen mode, the other hand on the virtual piano). */
    data class AutoOn(val midi: Int, val volume: Float) : PerfEvent

    data class AutoOff(val midi: Int) : PerfEvent

    data class Waiting(val on: Boolean, val keys: Set<Int>) : PerfEvent

    data class Hit(val midi: Int, val grade: Grade, val combo: Int = 0) : PerfEvent

    data class Wrong(val midi: Int) : PerfEvent

    data class Missed(val note: PlayNote) : PerfEvent

    /** Three presses in a row an octave off the expected note (S11 "像是琴移了八度？"). */
    data class OctaveHint(val semitones: Int) : PerfEvent

    data class Finished(val score: Score?) : PerfEvent
}

enum class Grade { PERFECT, EARLY, LATE }

/**
 * The clock, the wait mode and the scoring of one run of one song (pure, JVM-tested). The time [t] is the song
 * time at the landing line, in seconds at the song's tempo; the clock runs at [PlayOptions.speed]. Notes on the
 * curtain are those with `start` in `t .. t + lookAhead`. Main thread only.
 *
 * Timing windows are in real milliseconds (design T28: ±150 ms = on time, 150 - 300 ms = early / late; S11: a
 * press counts from 250 ms before the group lands).
 */
class Performance(
    val song: Song,
    val options: PlayOptions,
    val canScore: Boolean,
    val transpose: Int = 0,
    val lookAheadSec: Float = 3f,
    /** Timing windows of play-along (real ms): on time, and the outer early / late edge (v0.1.2 assist levels). */
    val perfectMs: Float = PERFECT_MS,
    val lateMs: Float = LATE_MS,
) {
    val notes: List<PlayNote> =
        song.notes.mapIndexed { i, n -> PlayNote(i, song.sec(n.start), song.sec(n.start + n.length), n.midi + transpose, n.hand, options.hands.practises(n.hand)) }
            .sortedBy { it.start }
            .mapIndexed { i, n -> n.copy(index = i) }

    private val maxLen = notes.maxOfOrNull { it.end - it.start } ?: 0f
    val endSec: Float = (notes.maxOfOrNull { it.end } ?: 0f) + song.secPerBeat * song.beatsPerBar
    private val bar = song.secPerBeat * song.beatsPerBar

    /** Before the first note: the first bar rests just under the top of the curtain (design S08 preview). */
    val startT: Float = -lookAheadSec * PREVIEW

    var t: Float = startT
        private set

    var waiting = false
        private set

    var finished = false
        private set

    private val hit = BooleanArray(notes.size)
    private val missed = BooleanArray(notes.size)
    private val landed = BooleanArray(notes.size)
    private val sounding = HashMap<Int, Int>() // note index -> midi, auto-played
    private var perfect = 0
    private var earlyLate = 0
    private var wrong = 0
    private var octaveStreak = 0
    private var lastOctave = 0

    // WAIT: the group being waited for (indices of practice notes with the same start) and what is still pending
    private var groupStart = Float.NaN
    private var pending = HashSet<Int>()
    private var waitedSec = 0f
    private var groups = 0

    private val practiceCount = notes.count { it.practice }

    /** Song seconds per real second (S12 "换速度" changes it mid-run). */
    var rate: Float = options.speed

    private fun realToSong(ms: Float): Float = ms / 1000f * rate

    val progress: Float
        get() = ((t / (endSec - bar)).coerceIn(0f, 1f))

    /** Score mode: follow with a known input, or wait (wrong count). */
    val scored: Boolean
        get() = canScore && options.mode != PlayMode.LISTEN

    /** Advances the clock by [dtReal] seconds; returns what happened. */
    fun tick(dtReal: Float): List<PerfEvent> {
        if (finished) return emptyList()
        val out = ArrayList<PerfEvent>()
        if (waiting) {
            waitedSec += dtReal
            return out
        }
        var next = t + dtReal * rate
        if (options.mode == PlayMode.WAIT && canScore) {
            val g = nextGroupStart(t)
            if (g != null && next >= g) {
                openGroup(g)
                if (pending.isNotEmpty()) {
                    next = g
                    cross(t, next, out)
                    t = next
                    waiting = true
                    groups++
                    out += PerfEvent.Waiting(true, pending.map { notes[it].midi }.toSet())
                    return out
                }
            }
        }
        cross(t, next, out)
        t = next
        if (t >= endSec) {
            finished = true
            sounding.values.forEach { out += PerfEvent.AutoOff(it) }
            sounding.clear()
            out += PerfEvent.Finished(result())
        }
        return out
    }

    private fun cross(from: Float, to: Float, out: MutableList<PerfEvent>) {
        for (n in notes) {
            if (n.start > to) break
            if (!landed[n.index] && n.start <= to && n.start > from - 1e-6f) {
                landed[n.index] = true
                out += PerfEvent.Landed(n)
                val auto = options.mode == PlayMode.LISTEN || !n.practice
                if (auto) {
                    out += PerfEvent.AutoOn(n.midi, if (n.practice || options.mode == PlayMode.LISTEN) 1f else OTHER_HAND_VOLUME)
                    sounding[n.index] = n.midi
                }
            }
            if (n.index in sounding && n.end <= to) {
                sounding.remove(n.index)
                out += PerfEvent.AutoOff(n.midi)
            }
            if (options.mode == PlayMode.FOLLOW && canScore && n.practice && !hit[n.index] && !missed[n.index] && n.start + realToSong(lateMs) < to) {
                missed[n.index] = true
                combo = 0
                out += PerfEvent.Missed(n)
            }
        }
    }

    private fun nextGroupStart(after: Float): Float? = notes.firstOrNull { it.practice && it.start >= after - 1e-6f && !hit[it.index] && it.start != groupStartDone }?.start

    private var groupStartDone = Float.NaN

    private fun openGroup(start: Float) {
        if (groupStart == start) return
        groupStart = start
        pending = notes.filter { it.practice && abs(it.start - start) < 1e-4f && !hit[it.index] }.map { it.index }.toHashSet()
    }

    /** A key went down (MIDI, a finger on the virtual key, the controller); [midi] after the keyboard's offset. */
    fun noteOn(midi: Int, strayIsFree: Boolean = false): PerfEvent? {
        if (finished || !canScore) return null
        val e =
            when (options.mode) {
                PlayMode.LISTEN -> null
                PlayMode.FOLLOW -> follow(midi, strayIsFree)
                PlayMode.WAIT -> waitPress(midi, strayIsFree)
            }
        if (e is PerfEvent.Wrong || e is PerfEvent.OctaveHint) combo = 0
        return e
    }

    /** Hits in a row (missed and wrong notes reset it); the mini bar shows it. */
    var combo = 0
        private set

    /** First hit time of each chord (by its start): later fingers of the same chord are graded on it. */
    private val chordFirstHit = HashMap<Float, Float>()

    /**
     * The keys the song wants now, for the virtual piano's snapping (v0.1.2): play-along -> the practice notes within
     * the timing window not yet played; wait -> the group waited for, or the next one once its window is open.
     */
    fun targets(): Set<Int> {
        if (finished || !canScore) return emptySet()
        return when (options.mode) {
            PlayMode.LISTEN -> emptySet()
            PlayMode.FOLLOW -> {
                val w = realToSong(lateMs)
                notes.asSequence().filter { it.practice && !hit[it.index] && !missed[it.index] && abs(it.start - t) <= w }.map { it.midi }.toSet()
            }
            PlayMode.WAIT -> {
                if (waiting) return pendingKeys
                val g = nextGroupStart(t) ?: return emptySet()
                if (g - t > realToSong(EARLY_WAIT_MS)) emptySet() else notes.filter { it.practice && abs(it.start - g) < 1e-4f && !hit[it.index] }.map { it.midi }.toSet()
            }
        }
    }

    private fun follow(midi: Int, strayIsFree: Boolean): PerfEvent? {
        val window = realToSong(lateMs)
        val n = notes.filter { it.practice && it.midi == midi && !hit[it.index] && !missed[it.index] && abs(it.start - t) <= window }.minByOrNull { abs(it.start - t) }
        if (n == null) {
            if (strayIsFree) return null
            wrong++
            return PerfEvent.Wrong(midi)
        }
        hit[n.index] = true
        // a chord's fingers rarely land together: every note of it is graded on the chord's first hit
        val at = chordFirstHit.getOrPut(n.start) { t }
        val dMs = (at - n.start) / rate * 1000f
        val grade = if (abs(dMs) <= perfectMs) Grade.PERFECT else if (dMs < 0) Grade.EARLY else Grade.LATE
        if (grade == Grade.PERFECT) perfect++ else earlyLate++
        combo++
        return PerfEvent.Hit(midi, grade, combo)
    }

    private fun waitPress(midi: Int, strayIsFree: Boolean): PerfEvent? {
        // the window of the next group opens 250 ms (real) before it lands
        if (!waiting) {
            val g = nextGroupStart(t) ?: return null
            if (g - t > realToSong(EARLY_WAIT_MS)) return null
            openGroup(g)
        }
        val match = pending.firstOrNull { notes[it].midi == midi }
        if (match != null) {
            pending.remove(match)
            hit[match] = true
            perfect++
            combo++
            octaveStreak = 0
            if (pending.isEmpty()) {
                groupStartDone = groupStart
                if (waiting) {
                    waiting = false
                    return PerfEvent.Waiting(false, emptySet())
                }
            }
            return PerfEvent.Hit(midi, Grade.PERFECT, combo)
        }
        if (strayIsFree && pending.none { notes[it].midi - midi == 12 || notes[it].midi - midi == -12 }) return null
        wrong++
        val octave = pending.map { notes[it].midi - midi }.firstOrNull { it == 12 || it == -12 }
        if (octave != null) {
            octaveStreak = if (octave == lastOctave) octaveStreak + 1 else 1
            lastOctave = octave
            if (octaveStreak >= OCTAVE_STREAK) {
                octaveStreak = 0
                return PerfEvent.OctaveHint(octave)
            }
        } else octaveStreak = 0
        return PerfEvent.Wrong(midi)
    }

    /** The keys the player should press now (WAIT), for the breathing highlight. */
    val pendingKeys: Set<Int>
        get() = if (waiting) pending.map { notes[it].midi }.toSet() else emptySet()

    fun isHit(index: Int): Boolean = hit[index]

    fun isMissed(index: Int): Boolean = missed[index]

    /** The notes on the curtain now: landing between [t] - their length and [t] + [lookAheadSec]. */
    fun visible(out: MutableList<PlayNote>) {
        out.clear()
        val from = t - maxLen
        val to = t + lookAheadSec
        var lo = 0
        var hi = notes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (notes[mid].start < from) lo = mid + 1 else hi = mid
        }
        for (i in lo until notes.size) {
            val n = notes[i]
            if (n.start > to) break
            if (n.end > t) out += n
        }
    }

    fun result(): Score? {
        if (!scored) return null
        return when (options.mode) {
            PlayMode.WAIT -> Score(practiceCount, perfect, 0, 0, wrong, if (groups == 0) 0f else waitedSec / groups)
            else -> Score(practiceCount, perfect, earlyLate, missed.count { it }, wrong)
        }
    }

    /** Back to the start (从头): the first bar waits at the top of the curtain. */
    fun restart() = seek(startT)

    /** One bar back (S12 "后退一小节"). */
    fun backBar() = seek(maxOf(startT, t - bar))

    /** Debug `seek`: jumps to song second [to]; notes after it are fresh again. */
    fun seek(to: Float) {
        t = to
        waiting = false
        finished = false
        groupStart = Float.NaN
        groupStartDone = Float.NaN
        pending.clear()
        chordFirstHit.keys.removeAll { it >= to - 1e-6f }
        combo = 0
        sounding.clear()
        for (n in notes) {
            if (n.start >= to - 1e-6f) {
                hit[n.index] = false
                missed[n.index] = false
                landed[n.index] = false
            }
        }
    }

    companion object {
        const val PERFECT_MS = 150f
        const val LATE_MS = 300f
        const val EARLY_WAIT_MS = 250f
        const val OCTAVE_STREAK = 3
        const val OTHER_HAND_VOLUME = 0.6f
        const val PREVIEW = 0.85f
    }
}
