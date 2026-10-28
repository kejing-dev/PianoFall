package io.embodit.pianofall.domain

import kotlin.math.abs

/**
 * The standard keyboards (design `spatialLayout.keyboard.standardLengths`): range, number of white keys and
 * the length of the front edge with 23.5 mm white keys.
 */
enum class KeyCount(val keys: Int, val lowest: Int, val highest: Int, val stdLength: Float) {
    K88(88, 21, 108, 1.222f),
    K76(76, 28, 103, 1.058f),
    K73(73, 28, 100, 1.011f),
    K61(61, 36, 96, 0.846f),
    K49(49, 36, 84, 0.682f);

    val whites: Int
        get() = (lowest..highest).count { !Keys.isBlack(it) }

    companion object {
        fun of(keys: Int): KeyCount? = entries.firstOrNull { it.keys == keys }

        /** The choices of S01 in panel order, and of the virtual piano (S24). */
        val real = listOf(K88, K76, K73, K61, K49)
        val virtual = listOf(K49, K61, K88)
    }
}

object Keys {
    const val MIDDLE_C = 60
    private val BLACK = booleanArrayOf(false, true, false, true, false, false, true, false, true, false, true, false)
    private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun isBlack(midi: Int): Boolean = BLACK[Math.floorMod(midi, 12)]

    fun name(midi: Int): String = NAMES[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1)

    /** Equal temperament, A4 = 440 Hz (design `audio.tuning`). */
    fun hz(midi: Int): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)

    /**
     * Note name -> MIDI number: `C4` = 60, `F#3`, `Bb2`, `Ds5` (s = sharp as in the sample files).
     */
    fun parse(name: String): Int {
        val n = name.trim()
        val base =
            when (n[0].uppercaseChar()) {
                'C' -> 0
                'D' -> 2
                'E' -> 4
                'F' -> 5
                'G' -> 7
                'A' -> 9
                'B' -> 11
                else -> error("bad note $name")
            }
        var i = 1
        var acc = 0
        while (i < n.length && (n[i] == '#' || n[i] == 's' || n[i] == 'b')) {
            acc += if (n[i] == 'b') -1 else 1
            i++
        }
        val octave = n.substring(i).toInt()
        return (octave + 1) * 12 + base + acc
    }
}

/** One key's footprint in keyboard-local metres: x from the left end, z from the front edge (negative = away). */
data class KeyRect(val midi: Int, val black: Boolean, val x0: Float, val x1: Float, val zFront: Float, val zBack: Float) {
    val cx: Float
        get() = (x0 + x1) / 2f

    val width: Float
        get() = x1 - x0
}

/**
 * Key geometry of a keyboard of [count] keys whose front edge is [length] metres long (design: the white key
 * width is the measured length / white keys, never a hard 23.5 mm). Black keys sit on the standard offsets:
 * C-E (three whites) splits into five equal slots, F-B (four whites) into seven.
 */
class KeyboardLayout(val count: KeyCount, val length: Float) {
    val whiteW: Float = length / count.whites
    val blackW: Float = whiteW * BLACK_RATIO

    private val rects: Map<Int, KeyRect> = build()

    fun key(midi: Int): KeyRect? = rects[midi]

    val all: Collection<KeyRect>
        get() = rects.values

    fun contains(midi: Int): Boolean = midi in count.lowest..count.highest

    /** The key under a point of the key bed (x along the front edge, z negative away from the player); black keys first. */
    fun keyAt(x: Float, z: Float): Int? {
        if (x < 0f || x > length || z > 0f || z < -WHITE_DEPTH) return null
        if (z <= -WHITE_DEPTH + BLACK_DEPTH) rects.values.firstOrNull { it.black && x in it.x0..it.x1 }?.let { return it.midi }
        return rects.values.firstOrNull { !it.black && x in it.x0..it.x1 }?.midi
    }

    private fun build(): Map<Int, KeyRect> {
        val out = LinkedHashMap<Int, KeyRect>()
        var white = 0
        for (m in count.lowest..count.highest) {
            if (!Keys.isBlack(m)) {
                out[m] = KeyRect(m, false, white * whiteW, (white + 1) * whiteW, 0f, -WHITE_DEPTH)
                white++
            }
        }
        for (m in count.lowest..count.highest) {
            if (!Keys.isBlack(m)) continue
            val below = out[m - 1] ?: continue
            // left edge of the group's C (C# D#) or F (F# G# A#), counted back from the white key below
            val pc = Math.floorMod(m, 12)
            val groupStart = below.x0 - whiteW * WHITES_BACK[pc]
            val center = groupStart + whiteW * BLACK_CENTER[pc]
            out[m] = KeyRect(m, true, center - blackW / 2f, center + blackW / 2f, -(WHITE_DEPTH - BLACK_DEPTH), -WHITE_DEPTH)
        }
        return out
    }

    companion object {
        const val WHITE_DEPTH = 0.15f
        const val BLACK_DEPTH = 0.095f
        const val BLACK_RATIO = 0.58f

        /** Black key centres from the group's left edge, in white widths (C# D# | F# G# A#). */
        /** White keys from the group's first white key to the white key just below the black one. */
        private val WHITES_BACK = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 2f, 0f)

        private val BLACK_CENTER = floatArrayOf(0f, 0.9f, 0f, 2.1f, 0f, 0f, 4f / 7f * 1.5f, 0f, 4f / 7f * 3.5f, 0f, 4f / 7f * 5.5f, 0f)
    }
}

/**
 * What a measured front edge says about the keyboard (design `snapTolerance`): the key count chosen in S01
 * wins; the snap only confirms it within -7 % .. +2.5 % (portable keyboards have 3 - 6 % narrower keys).
 * Outside that and nearer another standard length the user is asked (S06).
 */
object LengthSnap {
    const val BELOW = 0.07f
    const val ABOVE = 0.025f

    sealed interface Result {
        data class Confirmed(val count: KeyCount) : Result

        /** Accepted without the snap (outside the tolerance but still nearest the chosen count). */
        data class Kept(val count: KeyCount) : Result

        /** Ask which of the two standard lengths around the measurement it is (S06). */
        data class Mismatch(val a: KeyCount, val b: KeyCount) : Result
    }

    fun within(measured: Float, count: KeyCount): Boolean = measured >= count.stdLength * (1f - BELOW) && measured <= count.stdLength * (1f + ABOVE)

    fun classify(measured: Float, chosen: KeyCount): Result {
        if (within(measured, chosen)) return Result.Confirmed(chosen)
        val nearest = KeyCount.entries.minByOrNull { abs(it.stdLength - measured) }!!
        if (nearest == chosen) return Result.Kept(chosen)
        val sorted = KeyCount.entries.sortedBy { it.stdLength }
        val shorter = sorted.lastOrNull { it.stdLength <= measured } ?: sorted.first()
        val longer = sorted.firstOrNull { it.stdLength > measured } ?: sorted.last()
        return if (shorter == longer) Result.Kept(chosen) else Result.Mismatch(shorter, longer)
    }

    /** The standard keyboard nearest to a length (S04 live line). */
    fun nearest(measured: Float): KeyCount = KeyCount.entries.minByOrNull { abs(it.stdLength - measured) }!!
}

/**
 * Where a keyboard is (design `container.notes`): origin = front-left corner of the lowest white key, +X along
 * the front edge to the right, +Y straight up (gravity, never tilted with the hand), +Z towards the player.
 */
data class KeyboardFrame(val origin: Vec3, val xAxis: Vec3, val length: Float, val count: KeyCount) {
    val zAxis: Vec3
        get() = xAxis.cross(Vec3.UP).normalized()

    val rotation: Quat4
        get() = Quat4.fromBasis(xAxis, Vec3.UP, zAxis)

    val right: Vec3
        get() = origin + xAxis * length

    val center: Vec3
        get() = origin + xAxis * (length / 2f)

    val layout: KeyboardLayout by lazy { KeyboardLayout(count, length) }

    fun toWorld(local: Vec3): Vec3 = origin + xAxis * local.x + Vec3.UP * local.y + zAxis * local.z

    fun toLocal(world: Vec3): Vec3 {
        val d = world - origin
        return Vec3(d.dot(xAxis), d.y, d.dot(zAxis))
    }

    fun dirToLocal(dir: Vec3): Vec3 = Vec3(dir.dot(xAxis), dir.y, dir.dot(zAxis))

    /** Turned about the vertical through the keyboard's centre (T4). */
    fun turned(rad: Float): KeyboardFrame {
        val q = Quat4.axisAngle(Vec3.UP, rad)
        val c = center
        val x = q.rotate(xAxis).let { Vec3(it.x, 0f, it.z).normalized() }
        return copy(origin = c - x * (length / 2f), xAxis = x)
    }

    /** Moved so that its left end ([left] = true) or right end lands on [at], direction and length kept (S02). */
    fun pinned(at: Vec3, left: Boolean): KeyboardFrame = if (left) copy(origin = at) else copy(origin = at - xAxis * length)

    companion object {
        /** Ends closer in height than this are levelled (design S05: "两端高差 ≤ 8 mm 自动吸平"). */
        const val LEVEL_M = 0.008f

        /**
         * From the two corners taken in S03 / S04: the front-left of the lowest white key and the front-right of the
         * highest one. The axis is levelled (the vertical is gravity).
         */
        fun fromEnds(left: Vec3, right: Vec3, count: KeyCount): KeyboardFrame? {
            val flat = Vec3(right.x - left.x, 0f, right.z - left.z)
            val len = flat.length
            if (len < 0.2f) return null
            val dy = right.y - left.y
            val y = if (abs(dy) <= LEVEL_M) (left.y + right.y) / 2f else left.y
            return KeyboardFrame(Vec3(left.x, y, left.z), flat / len, len, count)
        }

        /** The horizontal distance between the two ends (what S04 shows). */
        fun span(left: Vec3, right: Vec3): Float = Vec3(right.x - left.x, 0f, right.z - left.z).length
    }
}

/**
 * T17: the lowest and highest MIDI notes of a connected keyboard tell its range. The offset makes the
 * keyboard's notes match the standard range of [count]; a multiple of 12 is applied, anything else means the
 * keyboard is transposed (the user has to switch that off).
 */
object MidiRange {
    sealed interface Result {
        data class Offset(val semitones: Int, val count: KeyCount?) : Result

        data class Transposed(val semitones: Int) : Result
    }

    fun measure(low: Int, high: Int, chosen: KeyCount): Result {
        val keys = high - low + 1
        val count = KeyCount.of(keys) ?: chosen
        val offset = count.lowest - low
        return if (offset % 12 == 0) Result.Offset(offset, KeyCount.of(keys)) else Result.Transposed(offset)
    }
}
