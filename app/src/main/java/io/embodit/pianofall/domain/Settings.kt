package io.embodit.pianofall.domain

enum class LanguagePref(val tag: String) { SYSTEM("system"), ZH("zh"), EN("en") }

/** Where the side panel stands over the keys (S14 "面板位置"). */
enum class PanelSide(val tag: String, val offsetM: Float) { CENTER("center", 0f), LEFT("left", -0.35f), RIGHT("right", 0.35f) }

/** Real piano under the overlay, or the virtual one on the desk. */
enum class PianoMode(val tag: String) { REAL("real"), VIRTUAL("virtual") }

/** Design `dataModel.local`. */
data class Settings(
    val language: LanguagePref = LanguagePref.SYSTEM,
    /** D15: one tap in the top bar, remembered. */
    val muted: Boolean = false,
    /** Sound volume 0..1, default 70 % (design `audio.volume`). */
    val volume: Float = 0.7f,
    val metronome: Boolean = false,
    /** 2 / 3 / 4 s of look-ahead on the curtain. */
    val lookAheadSec: Int = 3,
    val panelSide: PanelSide = PanelSide.CENTER,
    /** Semitones added to every MIDI note from the keyboard (T17 / S11 octave fix). */
    val midiOffset: Int = 0,
    /** The left / right hand legend was shown once (S08, B19). */
    val legendSeen: Boolean = false,
)

/** Per song: last speed and hands, best result (design `scoring.storage`). */
data class SongMemory(val speed: Float = 1f, val hands: HandSel = HandSel.RIGHT, val bestAccuracy: Float = -1f, val bestStars: Int = 0, val played: Boolean = false)

/**
 * The stored keyboard (design `dataModel.local`): two world anchors at the ends for a real piano, one at the
 * left front corner for the virtual one; length, key count, the Stage positions at alignment (fallback and drift
 * check) and the time.
 */
data class SavedKeyboard(
    val mode: PianoMode,
    val count: KeyCount,
    val length: Float,
    val leftAnchor: String?,
    val rightAnchor: String?,
    val left: Vec3,
    val right: Vec3,
    val alignedAtMs: Long,
) {
    val frame: KeyboardFrame?
        get() = KeyboardFrame.fromEnds(left, right, count)?.copy(length = length)
}
