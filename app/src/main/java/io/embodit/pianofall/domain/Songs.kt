package io.embodit.pianofall.domain

enum class HandPart(val tag: String) { RIGHT("R"), LEFT("L") }

/** One note of a song: start and length in beats at the song's tempo. */
data class Note(val start: Float, val length: Float, val midi: Int, val hand: HandPart)

/**
 * A song of the library (design `songs`, `contentSource`). Every work is in the public domain (composer dead more
 * than 70 years, or a traditional tune); the note data was typed in for this app from public-domain scores. Left
 * hands that the tune has none of are our own simplest accompaniment ([leftByUs]); simplified arrangements are
 * marked [arrangedByUs].
 */
data class Song(
    val id: String,
    val zh: String,
    val en: String,
    val composerZh: String,
    val composerEn: String,
    val level: Int,
    val bpm: Float,
    val beatsPerBar: Int,
    val notes: List<Note>,
    val source: String,
    val leftByUs: Boolean = false,
    val arrangedByUs: Boolean = false,
) {
    val secPerBeat: Float
        get() = 60f / bpm

    fun sec(beat: Float): Float = beat * secPerBeat

    val lengthBeats: Float by lazy { notes.maxOf { it.start + it.length } }

    val lengthSec: Float
        get() = sec(lengthBeats)

    val hasLeft: Boolean by lazy { notes.any { it.hand == HandPart.LEFT } }

    val lowest: Int by lazy { notes.minOf { it.midi } }

    val highest: Int by lazy { notes.maxOf { it.midi } }

    /** The song moved by whole octaves so that it fits [count] (49-key keyboards); 0 when it fits already. */
    fun octaveShiftFor(count: KeyCount): Int {
        var shift = 0
        while (lowest + shift < count.lowest && highest + shift + 12 <= count.highest) shift += 12
        while (highest + shift > count.highest && lowest + shift - 12 >= count.lowest) shift -= 12
        return shift
    }
}

/**
 * A tiny score notation: tokens separated by spaces, `|` bar lines ignored. A token is `NOTE/beats`, where NOTE is
 * a name (`C4`, `F#3`, `Bb2`), several joined by `+` for a chord, or `r` for a rest; `/beats` defaults to 1.
 */
object Notation {
    fun voice(hand: HandPart, text: String, from: Float = 0f): List<Note> {
        val out = ArrayList<Note>()
        var beat = from
        for (raw in text.trim().split(Regex("\\s+"))) {
            if (raw.isEmpty() || raw == "|") continue
            val slash = raw.indexOf('/')
            val names = if (slash < 0) raw else raw.substring(0, slash)
            val len = if (slash < 0) 1f else raw.substring(slash + 1).toFloat()
            if (names != "r") for (n in names.split('+')) out += Note(beat, len * NOTE_FILL, Keys.parse(n), hand)
            beat += len
        }
        return out
    }

    fun song(right: String, left: String = ""): List<Note> =
        (voice(HandPart.RIGHT, right) + if (left.isBlank()) emptyList() else voice(HandPart.LEFT, left)).sortedWith(compareBy({ it.start }, { it.midi }))

    /** A note sounds and falls for 92 % of its written length: repeated notes stay two bars. */
    private const val NOTE_FILL = 0.92f
}

/** The 12 songs, 4 levels (design `songs`). */
object Library {
    private const val PD = "Public domain"

    val all: List<Song> by lazy {
        listOf(
            Song(
                "twinkle", "小星星", "Twinkle, Twinkle, Little Star", "法国民歌", "French folk tune", 1, 84f, 4,
                Notation.song(
                    "C4 C4 G4 G4 | A4 A4 G4/2 | F4 F4 E4 E4 | D4 D4 C4/2 | G4 G4 F4 F4 | E4 E4 D4/2 | G4 G4 F4 F4 | E4 E4 D4/2 | " +
                        "C4 C4 G4 G4 | A4 A4 G4/2 | F4 F4 E4 E4 | D4 D4 C4/2"
                ),
                "$PD: French folk tune 'Ah! vous dirai-je, maman' (1761)",
            ),
            Song(
                "two-tigers", "两只老虎", "Frère Jacques", "法国民歌", "French folk tune", 1, 100f, 4,
                Notation.song(
                    "C4 D4 E4 C4 | C4 D4 E4 C4 | E4 F4 G4/2 | E4 F4 G4/2 | G4/0.5 A4/0.5 G4/0.5 F4/0.5 E4 C4 | " +
                        "G4/0.5 A4/0.5 G4/0.5 F4/0.5 E4 C4 | C4 G3 C4/2 | C4 G3 C4/2 | " +
                        "C4 D4 E4 C4 | C4 D4 E4 C4 | E4 F4 G4/2 | E4 F4 G4/2 | G4/0.5 A4/0.5 G4/0.5 F4/0.5 E4 C4 | " +
                        "G4/0.5 A4/0.5 G4/0.5 F4/0.5 E4 C4 | C4 G3 C4/2 | C4 G3 C4/2"
                ),
                "$PD: French folk tune 'Frère Jacques' (18th c.)",
            ),
            Song(
                "mary-lamb", "玛丽有只小羊羔", "Mary Had a Little Lamb", "美国童谣（1830）", "American nursery rhyme (1830)", 1, 100f, 4,
                Notation.song(
                    "E4 D4 C4 D4 | E4 E4 E4/2 | D4 D4 D4/2 | E4 G4 G4/2 | E4 D4 C4 D4 | E4 E4 E4 E4 | D4 D4 E4 D4 | C4/4 | " +
                        "E4 D4 C4 D4 | E4 E4 E4/2 | D4 D4 D4/2 | E4 G4 G4/2 | E4 D4 C4 D4 | E4 E4 E4 E4 | D4 D4 E4 D4 | C4/4"
                ),
                "$PD: Sarah J. Hale / Lowell Mason tune (1830)",
            ),
            Song(
                "little-bee", "小蜜蜂", "Little Bee (Hänschen klein)", "德国民歌", "German folk tune", 1, 100f, 4,
                Notation.song(
                    "G4 E4 E4/2 | F4 D4 D4/2 | C4 D4 E4 F4 | G4 G4 G4/2 | G4 E4 E4/2 | F4 D4 D4/2 | C4 E4 G4 G4 | E4/4 | " +
                        "D4 D4 D4 D4 | D4 E4 F4/2 | E4 E4 E4 E4 | E4 F4 G4/2 | G4 E4 E4/2 | F4 D4 D4/2 | C4 E4 G4 G4 | C4/4"
                ),
                "$PD: German folk tune 'Hänschen klein' (19th c.)",
            ),
            Song(
                "ode-to-joy", "欢乐颂", "Ode to Joy", "贝多芬（1824）", "Beethoven (1824)", 2, 96f, 4,
                Notation.song(
                    "E4 E4 F4 G4 | G4 F4 E4 D4 | C4 C4 D4 E4 | E4/1.5 D4/0.5 D4/2 | E4 E4 F4 G4 | G4 F4 E4 D4 | C4 C4 D4 E4 | D4/1.5 C4/0.5 C4/2 | " +
                        "D4 D4 E4 C4 | D4 E4/0.5 F4/0.5 E4 C4 | D4 E4/0.5 F4/0.5 E4 D4 | C4 D4 G3/2 | " +
                        "E4 E4 F4 G4 | G4 F4 E4 D4 | C4 C4 D4 E4 | D4/1.5 C4/0.5 C4/2",
                    "C3/4 | G2/4 | C3/4 | G2/4 | C3/4 | G2/4 | C3/4 | G2/2 C3/2 | G2/4 | G2/2 C3/2 | G2/4 | C3/2 G2/2 | C3/4 | G2/4 | C3/4 | G2/2 C3/2",
                ),
                "$PD: Beethoven, Symphony No. 9, finale theme (1824); left hand: simplest roots by this app",
                leftByUs = true,
            ),
            Song(
                "jasmine", "茉莉花", "Mo Li Hua (Jasmine Flower)", "江苏民歌", "Jiangsu folk song", 2, 72f, 4,
                Notation.song(
                    "E4 E4/0.5 G4/0.5 A4/0.5 C5/0.5 C5/0.5 A4/0.5 | G4 G4/0.5 A4/0.5 G4/2 | E4 E4/0.5 G4/0.5 A4/0.5 C5/0.5 C5/0.5 A4/0.5 | G4 G4/0.5 A4/0.5 G4/2 | " +
                        "G4 G4 G4 E4/0.5 G4/0.5 | A4 A4 G4/2 | E4 D4/0.5 E4/0.5 G4 E4/0.5 D4/0.5 | C4 C4/0.5 D4/0.5 C4/2 | " +
                        "E4/0.5 D4/0.5 C4/0.5 E4/0.5 D4/1.5 E4/0.5 | G4 A4/0.5 C5/0.5 G4/2 | D4 E4/0.5 G4/0.5 D4/0.5 E4/0.5 C4/0.5 A3/0.5 | G3/2 r/2 | " +
                        "A3 C4 D4/1.5 E4/0.5 | C4/0.5 D4/0.5 C4/0.5 A3/0.5 G3/2",
                    "C3+G3/4 | C3+G3/4 | C3+G3/4 | C3+G3/4 | C3+G3/4 | F2+C3/4 | C3+G3/4 | C3+G3/4 | " +
                        "C3+G3/4 | C3+G3/4 | G2+D3/4 | C3+G3/4 | F2+C3/4 | C3+G3/4",
                ),
                "$PD: traditional Jiangsu folk song; simplified arrangement and left-hand fifths by this app",
                leftByUs = true,
                arrangedByUs = true,
            ),
            Song(
                "auld-lang-syne", "友谊地久天长", "Auld Lang Syne", "苏格兰民歌", "Scottish folk tune", 2, 88f, 4,
                Notation.voice(HandPart.RIGHT, "C4", 0f) +
                    Notation.voice(
                        HandPart.RIGHT,
                        "F4/1.5 F4/0.5 F4 A4 | G4/1.5 F4/0.5 G4 A4/0.5 G4/0.5 | F4/1.5 F4/0.5 A4 C5 | D5/3 D5 | " +
                            "C5/1.5 A4/0.5 A4 F4 | G4/1.5 F4/0.5 G4 A4/0.5 G4/0.5 | F4/1.5 D4/0.5 D4 C4 | F4/3 D5 | " +
                            "C5/1.5 A4/0.5 A4 F4 | G4/1.5 F4/0.5 G4 D5 | C5/1.5 A4/0.5 A4 C5 | D5/3 D5 | " +
                            "C5/1.5 A4/0.5 A4 F4 | G4/1.5 F4/0.5 G4 A4/0.5 G4/0.5 | F4/1.5 D4/0.5 D4 C4 | F4/4",
                        1f,
                    ) +
                    Notation.voice(
                        HandPart.LEFT,
                        "F2/4 | C3/4 | F2/4 | Bb2/4 | F2/4 | C3/4 | F2/2 Bb2/2 | F2/2 C3/2 | F2/4 | C3/4 | F2/4 | Bb2/4 | F2/4 | C3/4 | F2/2 C3/2 | F2/4",
                        1f,
                    ),
                "$PD: traditional Scottish tune (Burns, 1788); left-hand roots by this app",
                leftByUs = true,
            ),
            Song(
                "going-home", "念故乡", "Going Home (New World Largo)", "德沃夏克（1893）", "Dvořák (1893)", 2, 56f, 4,
                Notation.song(
                    "E4 G4 G4/2 | E4 D4 C4/2 | D4 E4 G4 E4 | D4/4 | E4 G4 G4/2 | E4 D4 C4/2 | D4 E4 D4 C4 | C4/4 | " +
                        "A4 C5 C5/2 | B4 G4 A4/2 | A4 C5 C5/2 | B4 G4 A4/2 | E4 G4 G4/2 | E4 D4 C4/2 | D4 E4 D4 C4 | C4/4",
                    "C3/4 | C3/2 A2/2 | G2/4 | G2/4 | C3/4 | C3/2 A2/2 | G2/4 | C3/4 | F2/4 | G2/2 A2/2 | F2/4 | E2/2 A2/2 | C3/4 | C3/2 A2/2 | G2/4 | C3/4",
                ),
                "$PD: Dvořák, Symphony No. 9, Largo theme (1893), moved from D-flat to C; simplified arrangement by this app",
                leftByUs = true,
                arrangedByUs = true,
            ),
            Song(
                "minuet-g", "G 大调小步舞曲", "Minuet in G", "佩措尔德（BWV Anh. 114）", "Petzold (BWV Anh. 114)", 3, 100f, 3,
                Notation.song(
                    "D5 G4/0.5 A4/0.5 B4/0.5 C5/0.5 | D5 G4 G4 | E5 C5/0.5 D5/0.5 E5/0.5 F#5/0.5 | G5 G4 G4 | " +
                        "C5 D5/0.5 C5/0.5 B4/0.5 A4/0.5 | B4 C5/0.5 B4/0.5 A4/0.5 G4/0.5 | F#4 G4/0.5 A4/0.5 B4/0.5 G4/0.5 | A4/3 | " +
                        "D5 G4/0.5 A4/0.5 B4/0.5 C5/0.5 | D5 G4 G4 | E5 C5/0.5 D5/0.5 E5/0.5 F#5/0.5 | G5 G4 G4 | " +
                        "C5 D5/0.5 C5/0.5 B4/0.5 A4/0.5 | B4 C5/0.5 B4/0.5 A4/0.5 G4/0.5 | A4 B4/0.5 A4/0.5 G4/0.5 F#4/0.5 | G4/3",
                    "G3+B3/2 A3 | B3/3 | C4/3 | B3/3 | A3/3 | G3/3 | D4 B3 G3 | D4 D3 C4 | " +
                        "B3/2 A3 | G3 B3 G3 | C4/3 | B3 C4/0.5 B3/0.5 A3/0.5 G3/0.5 | A3/2 F#3 | G3/2 B3 | C4 D4 D3 | G3/2 G2",
                ),
                "$PD: Christian Petzold, Minuet in G major, BWV Anh. 114 (1725)",
            ),
            Song(
                "canon", "卡农（简化）", "Canon in D (simplified)", "帕赫贝尔", "Pachelbel", 3, 60f, 4,
                Notation.song(
                    "F#5/2 E5/2 | D5/2 C#5/2 | B4/2 A4/2 | B4/2 C#5/2 | D5/2 C#5/2 | B4/2 A4/2 | G4/2 F#4/2 | G4/2 E4/2 | " +
                        "D4 F#4 A4 G4 | F#4 D4 F#4 E4 | D4 B3 D4 A4 | G4 B4 A4 G4 | " +
                        "F#4 D4 E4 C#5 | D5 F#5 A5 A4 | B4 G4 A4 F#4 | D4 D5 D5/0.5 C#5/0.5 D5 | F#5/2 E5/2 | D5/2 C#5/2 | B4/2 A4/2 | D5/4",
                    "D3/2 A2/2 | B2/2 F#2/2 | G2/2 D2/2 | G2/2 A2/2 | D3/2 A2/2 | B2/2 F#2/2 | G2/2 D2/2 | G2/2 A2/2 | " +
                        "D3/2 A2/2 | B2/2 F#2/2 | G2/2 D2/2 | G2/2 A2/2 | D3/2 A2/2 | B2/2 F#2/2 | G2/2 D2/2 | G2/2 A2/2 | D3/2 A2/2 | B2/2 F#2/2 | G2/2 A2/2 | D3/4",
                ),
                "$PD: Johann Pachelbel, Canon in D (c. 1680); simplified arrangement by this app",
                arrangedByUs = true,
            ),
            Song(
                "fur-elise", "致爱丽丝（A 段）", "Für Elise (A section)", "贝多芬（1810）", "Beethoven (1810)", 3, 132f, 3,
                // one beat = one eighth note (3/8); the A section twice, first and second endings
                Notation.voice(HandPart.RIGHT, "E5/0.5 D#5/0.5", 0f) +
                    Notation.voice(
                        HandPart.RIGHT,
                        "E5/0.5 D#5/0.5 E5/0.5 B4/0.5 D5/0.5 C5/0.5 | A4 r/0.5 C4/0.5 E4/0.5 A4/0.5 | B4 r/0.5 E4/0.5 G#4/0.5 B4/0.5 | C5 r/0.5 E4/0.5 E5/0.5 D#5/0.5 | " +
                            "E5/0.5 D#5/0.5 E5/0.5 B4/0.5 D5/0.5 C5/0.5 | A4 r/0.5 C4/0.5 E4/0.5 A4/0.5 | B4 r/0.5 E4/0.5 C5/0.5 B4/0.5 | A4 r/0.5 E5/0.5 D#5/0.5 | " +
                            "E5/0.5 D#5/0.5 E5/0.5 B4/0.5 D5/0.5 C5/0.5 | A4 r/0.5 C4/0.5 E4/0.5 A4/0.5 | B4 r/0.5 E4/0.5 G#4/0.5 B4/0.5 | C5 r/0.5 E4/0.5 E5/0.5 D#5/0.5 | " +
                            "E5/0.5 D#5/0.5 E5/0.5 B4/0.5 D5/0.5 C5/0.5 | A4 r/0.5 C4/0.5 E4/0.5 A4/0.5 | B4 r/0.5 E4/0.5 C5/0.5 B4/0.5 | A4/3",
                        1f,
                    ) +
                    Notation.voice(
                        HandPart.LEFT,
                        "r/3 | A2/0.5 E3/0.5 A3/0.5 r/1.5 | E2/0.5 E3/0.5 G#3/0.5 r/1.5 | A2/0.5 E3/0.5 A3/0.5 r/1.5 | " +
                            "r/3 | A2/0.5 E3/0.5 A3/0.5 r/1.5 | E2/0.5 E3/0.5 G#3/0.5 r/1.5 | A2/0.5 E3/0.5 A3/0.5 r/1.5 | " +
                            "r/3 | A2/0.5 E3/0.5 A3/0.5 r/1.5 | E2/0.5 E3/0.5 G#3/0.5 r/1.5 | A2/0.5 E3/0.5 A3/0.5 r/1.5 | " +
                            "r/3 | A2/0.5 E3/0.5 A3/0.5 r/1.5 | E2/0.5 E3/0.5 G#3/0.5 r/1.5 | A2/3",
                        1f,
                    ),
                "$PD: Beethoven, Bagatelle in A minor WoO 59 'Für Elise' (1810), A section",
            ),
            Song(
                "moonlight", "月光奏鸣曲（第一乐章开头）", "Moonlight Sonata, mvt. 1 (opening)", "贝多芬（1801）", "Beethoven (1801)", 4, 54f, 4,
                // one beat = one quarter, the right hand's triplets are thirds of a beat
                Notation.song(
                    triplets("G#3 C#4 E4", 4) + triplets("G#3 C#4 E4", 4) + triplets("A3 C#4 E4", 2) + triplets("A3 D4 F#4", 2) +
                        triplets("G#3 C4 F#4", 1) + triplets("G#3 C#4 E4", 1) + triplets("G#3 C#4 D#4", 1) + triplets("F#3 C4 D#4", 1) +
                        triplets("E3 G#3 C#4", 3) + "G#3/0.333 C#4/0.333 E4/0.334 | " +
                        triplets("G#3 C#4 E4", 4) + triplets("A3 C#4 F#4", 2) + triplets("A3 C#4 E4", 2) +
                        triplets("F#3 B3 D#4", 2) + triplets("G#3 B3 E4", 2) + triplets("G#3 B#3 F#4", 2) + triplets("G#3 C#4 E4", 2) +
                        triplets("G#3 C#4 E4", 3) + "G#3/1",
                    // the melody over the triplets, from bar 5 beat 4 (dotted eighth + sixteenth)
                    "",
                ) +
                    Notation.voice(HandPart.RIGHT, "G#4/0.75 G#4/0.25 | G#4/3 G#4/0.75 G#4/0.25 | G#4/2 A4/2 | G#4/2 F#4/2 | E4/2", 19f) +
                    Notation.voice(
                        HandPart.LEFT,
                        "C#3+C#2/4 | B2+B1/4 | A2+A1/2 F#2+F#1/2 | G#2+G#1/2 G#2+G#1/2 | C#3+C#2/4 | C#3+C#2/4 | F#2+F#1/2 E2+E1/2 | B1+B2/2 B1+B2/2 | E2+E1/2 G#2+G#1/2 | C#3+C#2/2",
                    ),
                "$PD: Beethoven, Piano Sonata No. 14 op. 27/2, mvt. 1, bars 1 - 10 (1801)",
                arrangedByUs = true,
            ),
        )
    }

    private fun triplets(chord: String, beats: Int): String {
        val n = chord.split(' ')
        return (0 until beats).joinToString(" ", postfix = " ") { "${n[0]}/0.333 ${n[1]}/0.333 ${n[2]}/0.334" }
    }

    fun song(id: String): Song? = all.firstOrNull { it.id == id }

    val first: Song
        get() = all.first()

    /** Songs by level, 1 .. 4 (S07 groups). */
    val byLevel: Map<Int, List<Song>>
        get() = all.groupBy { it.level }.toSortedMap()
}
