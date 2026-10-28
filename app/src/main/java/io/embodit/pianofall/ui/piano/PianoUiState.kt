package io.embodit.pianofall.ui.piano

import io.embodit.pianofall.domain.Desk
import io.embodit.pianofall.domain.HandSel
import io.embodit.pianofall.domain.KeyCount
import io.embodit.pianofall.domain.KeyboardFrame
import io.embodit.pianofall.domain.Library
import io.embodit.pianofall.domain.PerfEvent
import io.embodit.pianofall.domain.PianoMode
import io.embodit.pianofall.domain.PlayMode
import io.embodit.pianofall.domain.PlayOptions
import io.embodit.pianofall.domain.Score
import io.embodit.pianofall.domain.Settings
import io.embodit.pianofall.domain.Song
import io.embodit.pianofall.domain.SongMemory
import io.embodit.pianofall.domain.Vec3

/** Panels that open over the side panel (design P3 / P4, the "琴" menu). */
enum class Overlay { NONE, SETTINGS, INPUT, PIANO_MENU }

/**
 * Everything the panels and the scene draw. [designState] names the design state (QA screenshots):
 * S00 loading · S01 welcome · S02 re-pin one end · S03 left end · S04 right end · S05 fine-tune · S06 length
 * mismatch · S07 library · S08 ready · S09 countdown · S10 playing · S11 waiting · S12 paused · S13 finished ·
 * S16 welcome back · S17 anchor lost · S18 position lost · S19 no permission · S21 hands ready · S22 finding a
 * desk · S23 virtual desk · S24 place the virtual piano. S14 (settings), S15 (input source) and S20 (wait mode
 * unavailable) are shown over / inside another state: [overlay], [waitUnavailable].
 */
data class PianoUiState(
    val designState: String = "S00",
    val sceneReady: Boolean = false,
    val settings: Settings = Settings(),
    val mode: PianoMode = PianoMode.REAL,
    /** S01: "我有琴" was picked and the key counts show. */
    val pickingCount: Boolean = false,
    val count: KeyCount = KeyCount.K88,
    val virtualCount: KeyCount = KeyCount.K61,
    // ---- real piano alignment (S02 - S06)
    val leftEnd: Vec3? = null,
    val rightEnd: Vec3? = null,
    /** Live horizontal distance from the left end to the tool tip (S04), metres. */
    val liveLength: Float? = null,
    /** S06: measured length and the two counts to choose from. */
    val mismatch: Triple<Float, KeyCount, KeyCount>? = null,
    /** The keyboard as it lies now (aligned, placed, or being fine-tuned). */
    val keyboard: KeyboardFrame? = null,
    /** The world anchors at its left and right ends (null while they are made, or not stored). */
    val anchorIds: List<String> = emptyList(),
    /** S05 / S16: middle C lit until this time (elapsedRealtime). */
    val middleCUntilMs: Long = 0L,
    /** S05 with MIDI: the lowest and highest notes heard, and the verdict line. */
    val midiLow: Int? = null,
    val midiHigh: Int? = null,
    val rangeNote: String? = null,
    /** S02: the state to return to. */
    val repinFrom: String? = null,
    /** S16 / S07: the overlay is shown, outlines fade after 5 s. */
    val outlineUntilMs: Long = 0L,
    // ---- virtual piano (S22 - S24)
    val desk: Desk? = null,
    /** S24: the piano is down (好了 / 重新放 show). */
    val pianoPlaced: Boolean = false,
    val snapped: Boolean = false,
    val ghostOnDesk: Boolean = false,
    val pianoHidden: Boolean = false,
    // ---- library and play
    val songId: String = Library.first.id,
    /** S07: the level whose songs show (1 .. 4). */
    val libraryLevel: Int = 1,
    val options: PlayOptions = PlayOptions(),
    val songs: Map<String, SongMemory> = emptyMap(),
    val countdown: Int = 0,
    val progress: Float = 0f,
    val waitingKeys: Set<Int> = emptySet(),
    /** S11: semitones of the suspected octave shift (+12 / -12), or null. */
    val octaveHint: Int? = null,
    val score: Score? = null,
    val newBest: Boolean = false,
    val playSec: Float = 0f,
    /** S21: which hands are seen on the keys. */
    val handsLeft: Boolean = false,
    val handsRight: Boolean = false,
    /** Bumped for every new run, so the scene builds a fresh performance. */
    val runToken: Int = 0,
    /** Bumped when the run is moved (restart / back a bar / resume after the octave fix). */
    val seekToken: Int = 0,
    val seekTo: SeekKind = SeekKind.NONE,
    // ---- inputs and tracking
    val midiAvailable: Boolean = true,
    val midiName: String? = null,
    val midiTestOk: Boolean = false,
    val controller: Boolean = false,
    val trackingLost: Boolean = false,
    /** S18: since when (elapsedRealtime); the re-pin link shows after 5 s. */
    val lostSinceMs: Long = 0L,
    /** S19 "先这样用": nothing is stored this session. */
    val noSave: Boolean = false,
    val overlay: Overlay = Overlay.NONE,
    /** The state the side panel stood in before S12 / S14. */
    val pausedFrom: String? = null,
    /** The first run shows the left / right legend (B19). */
    val showLegend: Boolean = false,
) {
    val song: Song
        get() = Library.song(songId) ?: Library.first

    val virtual: Boolean
        get() = mode == PianoMode.VIRTUAL

    /** The app knows which key went down: the virtual piano, or a real one with MIDI (design `scoring.when`). */
    val canScore: Boolean
        get() = virtual || midiName != null

    /** S20: a real piano without MIDI cannot wait for the player. */
    val waitUnavailable: Boolean
        get() = !virtual && midiName == null

    /** The run is on: countdown, playing, waiting, hands-ready. */
    val inRun: Boolean
        get() = designState in RUN_STATES

    /** The mini bar instead of the side panel. */
    val miniBar: Boolean
        get() = designState in setOf("S09", "S10", "S11", "S21")

    /** The side panel stands over the keys (after alignment); before that it floats ahead of the head. */
    val panelOnKeys: Boolean
        get() = keyboard != null && designState in ON_KEYS

    val aligning: Boolean
        get() = designState in setOf("S02", "S03", "S04", "S05", "S06")

    companion object {
        val RUN_STATES = setOf("S09", "S10", "S11", "S21")
        val ON_KEYS = setOf("S07", "S08", "S12", "S13", "S16", "S18")
    }
}

enum class SeekKind { NONE, RESTART, BACK_BAR, CONTINUE }

sealed interface PianoEvent {
    data object SceneReady : PianoEvent

    // ---- S01 and the side panel's chrome
    data object HavePianoClicked : PianoEvent

    data object NoPianoClicked : PianoEvent

    data class CountPicked(val count: KeyCount) : PianoEvent

    data object StartAlignClicked : PianoEvent

    data object PullToEyesClicked : PianoEvent

    data object BackClicked : PianoEvent

    data object MuteClicked : PianoEvent

    data class OverlayClicked(val overlay: Overlay) : PianoEvent

    // ---- alignment
    /** A corner was taken with the controller tip or the index fingertip (S02 / S03 / S04). */
    data class PointTaken(val at: Vec3, val source: String) : PianoEvent

    data class LiveLength(val metres: Float?) : PianoEvent

    /** An end ring was dragged (S05): its new place (Stage). */
    data class EndMoved(val left: Boolean, val at: Vec3) : PianoEvent

    /** Thumbstick turn about the keyboard's centre, written on release (radians). */
    data class KeyboardTurned(val rad: Float) : PianoEvent

    data object MiddleCClicked : PianoEvent

    data object AlignDoneClicked : PianoEvent

    data object RemeasureClicked : PianoEvent

    data object RepinClicked : PianoEvent

    data object RepinCancelClicked : PianoEvent

    data object RealignClicked : PianoEvent

    data object KeyboardOkClicked : PianoEvent

    // ---- virtual piano
    data class DeskFound(val desk: Desk?) : PianoEvent

    data object UseVirtualDeskClicked : PianoEvent

    data class DeskHeightClicked(val cm: Int) : PianoEvent

    data class DeskMoved(val desk: Desk) : PianoEvent

    data object DeskDoneClicked : PianoEvent

    data class GhostChanged(val onDesk: Boolean, val snapped: Boolean) : PianoEvent

    data class PianoPlaced(val frame: KeyboardFrame, val snapped: Boolean) : PianoEvent

    data object PlaceAgainClicked : PianoEvent

    data object PianoDoneClicked : PianoEvent

    data object MoveHereClicked : PianoEvent

    data object HidePianoClicked : PianoEvent

    data class VirtualCountPicked(val count: KeyCount) : PianoEvent

    // ---- library and play
    data class SongPicked(val id: String) : PianoEvent

    data class LevelPicked(val level: Int) : PianoEvent

    data class HandsPicked(val hands: HandSel) : PianoEvent

    data class ModePicked(val mode: PlayMode) : PianoEvent

    data class SpeedPicked(val speed: Float) : PianoEvent

    data object StartClicked : PianoEvent

    data object DirectStartClicked : PianoEvent

    data class HandsSeen(val left: Boolean, val right: Boolean) : PianoEvent

    data class Perf(val event: PerfEvent) : PianoEvent

    data class Progress(val fraction: Float, val sec: Float) : PianoEvent

    data object PauseClicked : PianoEvent

    /** The Stage went to the background or lost focus. */
    data object Background : PianoEvent

    data object ContinueClicked : PianoEvent

    data object RestartClicked : PianoEvent

    data object BackBarClicked : PianoEvent

    data object LibraryClicked : PianoEvent

    data object AgainClicked : PianoEvent

    data object FasterClicked : PianoEvent

    data object TryFollowClicked : PianoEvent

    data object FixOctaveClicked : PianoEvent

    data object ToVirtualClicked : PianoEvent

    // ---- settings / input
    data class SettingsChanged(val settings: Settings) : PianoEvent

    data class PianoModeClicked(val mode: PianoMode) : PianoEvent

    data class MidiDevice(val name: String?, val available: Boolean) : PianoEvent

    data class MidiNote(val midi: Int, val on: Boolean) : PianoEvent

    // ---- tracking, anchors, permission
    data class TrackingChanged(val lost: Boolean) : PianoEvent

    data class ControllerChanged(val tracked: Boolean) : PianoEvent

    /** S18: the keyboard's anchors disagree / one was removed (true), or agree again for 1 s (false). */
    data class PositionLost(val lost: Boolean) : PianoEvent

    data object GrantClicked : PianoEvent

    data object UseWithoutClicked : PianoEvent

    // ---- debug
    data class DebugState(val id: String) : PianoEvent

    /** The anchors were re-located by the platform: the keyboard recomputed from its two ends. */
    data class AnchorsMoved(val frame: KeyboardFrame) : PianoEvent
}
