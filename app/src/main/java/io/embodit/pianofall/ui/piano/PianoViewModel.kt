package io.embodit.pianofall.ui.piano

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.pianofall.data.AnchorGateway
import io.embodit.pianofall.data.AnchorOutcome
import io.embodit.pianofall.data.AnchorRecord
import io.embodit.pianofall.data.PianoStore
import io.embodit.pianofall.domain.Desk
import io.embodit.pianofall.domain.HeadPose
import io.embodit.pianofall.domain.KeyCount
import io.embodit.pianofall.domain.KeyboardFrame
import io.embodit.pianofall.domain.Keys
import io.embodit.pianofall.domain.LengthSnap
import io.embodit.pianofall.domain.Library
import io.embodit.pianofall.domain.MidiRange
import io.embodit.pianofall.domain.P2
import io.embodit.pianofall.domain.PerfEvent
import io.embodit.pianofall.domain.PianoMode
import io.embodit.pianofall.domain.PianoPlacement
import io.embodit.pianofall.domain.PlayMode
import io.embodit.pianofall.domain.SPEEDS
import io.embodit.pianofall.domain.SavedKeyboard
import io.embodit.pianofall.domain.SongMemory
import io.embodit.pianofall.domain.Vec3
import io.embodit.pianofall.platform.PianoGraph
import io.embodit.pianofall.platform.PianoLog
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The whole behaviour (design states S00 - S24): align a real keyboard by its two ends, or put a 1:1 virtual
 * piano on the desk; pick a song, then listen / play along / wait for me. Events from the panels and the scene
 * come in through [onEvent], the state goes out through [state]. The run's clock and scoring live in the scene's
 * `Performance` (per frame, never through Compose); this class only hears what changes the panels.
 */
class PianoViewModel(
    private val anchors: AnchorGateway,
    private val store: PianoStore,
    private val head: () -> HeadPose?,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(PianoUiState(settings = store.loadSettings(), songs = store.loadSongs()))
    val state: StateFlow<PianoUiState> = _state

    private var countdownJob: Job? = null
    private var deskJob: Job? = null
    private var loadJob: Job? = null
    private var lostJob: Job? = null
    private var lastLogged: String? = null

    /** S02: the keyboard before the re-pin (取消 puts it back). */
    private var repinBefore: KeyboardFrame? = null

    fun current(): PianoUiState = _state.value

    // =========================================================================================
    // events
    // =========================================================================================

    fun onEvent(e: PianoEvent) {
        val s = _state.value
        if (e !is PianoEvent.Progress && e !is PianoEvent.LiveLength && e !is PianoEvent.GhostChanged && e !is PianoEvent.Perf) PianoLog.i("UI ${e.javaClass.simpleName} state=${s.designState}")
        when (e) {
            PianoEvent.SceneReady -> {
                update { it.copy(sceneReady = true) }
                if (s.designState == "S00") startup()
            }
            PianoEvent.HavePianoClicked -> if (s.designState == "S01") update { it.copy(pickingCount = true) }
            PianoEvent.NoPianoClicked -> if (s.designState == "S01") findDesk()
            is PianoEvent.CountPicked ->
                when (s.designState) {
                    "S01" -> update { it.copy(count = e.count) }
                    "S06" -> {
                        val l = s.leftEnd ?: return
                        val r = s.rightEnd ?: return
                        update { it.copy(count = e.count, keyboard = KeyboardFrame.fromEnds(l, r, e.count), mismatch = null) }
                        go("S05")
                    }
                }
            PianoEvent.StartAlignClicked -> startAlign()
            PianoEvent.PullToEyesClicked -> Unit // the scene re-places the panel (it hears the same event)
            PianoEvent.BackClicked ->
                when (s.designState) {
                    "S01" -> update { it.copy(pickingCount = false) }
                    "S03", "S22" -> go(if (s.keyboard != null && current().anchorIds.isNotEmpty()) "S07" else "S01")
                    "S04" -> {
                        update { it.copy(leftEnd = null, liveLength = null) }
                        go("S03")
                    }
                    "S08" -> go("S07")
                    "S23", "S24" -> findDesk()
                }
            PianoEvent.MuteClicked -> saveSettings(s.settings.copy(muted = !s.settings.muted))
            is PianoEvent.OverlayClicked -> update { it.copy(overlay = if (it.overlay == e.overlay) Overlay.NONE else e.overlay) }

            is PianoEvent.PointTaken -> pointTaken(e.at, e.source)
            is PianoEvent.LiveLength -> if (s.designState == "S04" && e.metres != s.liveLength) update { it.copy(liveLength = e.metres) }
            is PianoEvent.EndMoved -> moveEnd(e.left, e.at)
            is PianoEvent.KeyboardTurned -> turn(e.rad)
            PianoEvent.MiddleCClicked -> update { it.copy(middleCUntilMs = clock() + MIDDLE_C_MS) }
            PianoEvent.AlignDoneClicked -> alignDone()
            PianoEvent.RemeasureClicked -> startAlign()
            PianoEvent.RepinClicked -> if (!s.virtual && s.keyboard != null) {
                repinBefore = s.keyboard
                update { it.copy(repinFrom = if (s.designState == "S18") "S12" else s.designState, overlay = Overlay.NONE) }
                go("S02")
            }
            PianoEvent.RepinCancelClicked -> if (s.designState == "S02") {
                update { it.copy(keyboard = repinBefore ?: it.keyboard) }
                go(s.repinFrom ?: "S16")
            }
            PianoEvent.RealignClicked -> {
                update { it.copy(overlay = Overlay.NONE) }
                if (s.virtual) findDesk() else startAlign()
            }
            PianoEvent.KeyboardOkClicked -> if (s.designState in setOf("S16", "S17")) go("S07")

            is PianoEvent.DeskFound -> deskFound(e.desk)
            PianoEvent.UseVirtualDeskClicked -> if (s.designState == "S22") useVirtualDesk()
            is PianoEvent.DeskHeightClicked -> s.desk?.takeIf { it.virtual && s.designState == "S23" }?.let { d -> update { it.copy(desk = d.copy(center = d.center + Vec3(0f, e.cm / 100f, 0f))) } }
            is PianoEvent.DeskMoved -> if (s.designState in setOf("S23", "S24")) update { it.copy(desk = e.desk) }
            PianoEvent.DeskDoneClicked -> if (s.designState == "S23") {
                update { it.copy(pianoPlaced = false, keyboard = null) }
                go("S24")
            }
            is PianoEvent.GhostChanged -> if (e.onDesk != s.ghostOnDesk || e.snapped != s.snapped) update { it.copy(ghostOnDesk = e.onDesk, snapped = e.snapped) }
            is PianoEvent.PianoPlaced -> if (s.designState == "S24" || (s.virtual && s.designState in ON_KEYS_PLAY)) {
                PianoLog.i("PIANO placed origin=${PianoLog.pos(e.frame.origin)} keys=${e.frame.count.keys} snapped=${e.snapped}")
                update { it.copy(keyboard = e.frame, pianoPlaced = true, snapped = e.snapped, mode = PianoMode.VIRTUAL) }
                if (s.designState != "S24") saveKeyboard(e.frame, PianoMode.VIRTUAL)
            }
            PianoEvent.PlaceAgainClicked -> {
                if (s.designState == "S24") update { it.copy(pianoPlaced = false, keyboard = null) } else {
                    update { it.copy(overlay = Overlay.NONE) }
                    findDesk()
                }
            }
            PianoEvent.PianoDoneClicked -> if (s.designState == "S24" && s.keyboard != null) {
                saveKeyboard(s.keyboard, PianoMode.VIRTUAL)
                go("S07")
            }
            PianoEvent.MoveHereClicked -> moveHere()
            PianoEvent.HidePianoClicked -> update { it.copy(pianoHidden = !it.pianoHidden, overlay = Overlay.NONE) }
            is PianoEvent.VirtualCountPicked -> update { it.copy(virtualCount = e.count, pianoPlaced = false, keyboard = if (s.designState == "S24") null else it.keyboard) }

            is PianoEvent.SongPicked -> pickSong(e.id)
            is PianoEvent.LevelPicked -> update { it.copy(libraryLevel = e.level.coerceIn(1, 4)) }
            is PianoEvent.HandsPicked -> update { it.copy(options = it.options.copy(hands = e.hands)) }
            is PianoEvent.ModePicked -> if (!(e.mode == PlayMode.WAIT && s.waitUnavailable)) update { it.copy(options = it.options.copy(mode = e.mode)) }
            is PianoEvent.SpeedPicked -> update { it.copy(options = it.options.copy(speed = e.speed)) }
            PianoEvent.StartClicked -> if (s.designState == "S08") startRun()
            PianoEvent.DirectStartClicked -> if (s.designState == "S21") countdown(3)
            is PianoEvent.HandsSeen -> handsSeen(e.left, e.right)
            is PianoEvent.Perf -> perf(e.event)
            is PianoEvent.Progress -> if (s.inRun || s.designState == "S12") update { it.copy(progress = e.fraction, playSec = e.sec) }
            PianoEvent.PauseClicked, PianoEvent.Background -> if (s.inRun) pause()
            PianoEvent.ContinueClicked -> if (s.designState == "S12") {
                update { it.copy(seekTo = SeekKind.CONTINUE, seekToken = it.seekToken + 1) }
                countdown(1)
            }
            PianoEvent.RestartClicked -> if (s.designState == "S12") {
                update { it.copy(seekTo = SeekKind.RESTART, seekToken = it.seekToken + 1, progress = 0f) }
                countdown(3)
            }
            PianoEvent.BackBarClicked -> if (s.designState == "S12") update { it.copy(seekTo = SeekKind.BACK_BAR, seekToken = it.seekToken + 1) }
            PianoEvent.LibraryClicked -> {
                countdownJob?.cancel()
                update { it.copy(overlay = Overlay.NONE) }
                go("S07")
            }
            PianoEvent.AgainClicked -> if (s.designState == "S13") startRun()
            PianoEvent.FasterClicked -> if (s.designState == "S13") {
                val next = SPEEDS.firstOrNull { it > s.options.speed } ?: s.options.speed
                update { it.copy(options = it.options.copy(speed = next)) }
                startRun()
            }
            PianoEvent.TryFollowClicked -> if (s.designState == "S13") {
                update { it.copy(options = it.options.copy(mode = PlayMode.FOLLOW)) }
                startRun()
            }
            PianoEvent.FixOctaveClicked -> s.octaveHint?.let { n ->
                saveSettings(s.settings.copy(midiOffset = s.settings.midiOffset + n))
                update { it.copy(octaveHint = null) }
            }
            PianoEvent.ToVirtualClicked -> {
                update { it.copy(overlay = Overlay.NONE) }
                findDesk()
            }

            is PianoEvent.SettingsChanged -> saveSettings(e.settings)
            is PianoEvent.PianoModeClicked -> if (e.mode != s.mode || s.keyboard == null) {
                update { it.copy(overlay = Overlay.NONE) }
                if (e.mode == PianoMode.VIRTUAL) findDesk() else {
                    update { it.copy(mode = PianoMode.REAL, pickingCount = true) }
                    go("S01")
                }
            }
            is PianoEvent.MidiDevice -> {
                update { it.copy(midiName = e.name, midiAvailable = e.available, midiTestOk = if (e.name == null) false else it.midiTestOk) }
                // the keyboard went away: a real piano cannot wait any more (S20)
                if (_state.value.waitUnavailable && _state.value.options.mode == PlayMode.WAIT) update { it.copy(options = it.options.copy(mode = PlayMode.FOLLOW)) }
            }
            is PianoEvent.MidiNote -> midiNote(e.midi, e.on)

            is PianoEvent.TrackingChanged -> update { it.copy(trackingLost = e.lost) }
            is PianoEvent.ControllerChanged -> if (s.controller != e.tracked) update { it.copy(controller = e.tracked) }
            is PianoEvent.PositionLost -> positionLost(e.lost)
            is PianoEvent.AnchorsMoved -> if (!s.aligning) update { it.copy(keyboard = e.frame) }
            PianoEvent.GrantClicked -> if (s.designState == "S19") {
                update { it.copy(noSave = false) }
                go("S00")
                startup()
            }
            PianoEvent.UseWithoutClicked -> if (s.designState == "S19") {
                update { it.copy(noSave = true) }
                go("S01")
            }
            is PianoEvent.DebugState -> debugJump(e.id)
        }
    }

    // =========================================================================================
    // start-up: the stored keyboard (S00 -> S16 / S17 / S01)
    // =========================================================================================

    private fun startup() {
        val saved = store.loadKeyboard()
        if (saved == null) {
            go("S01")
            return
        }
        loadJob?.cancel()
        loadJob =
            scope.launch {
                val found = loadAnchors()
                val l = saved.leftAnchor?.let { id -> found?.firstOrNull { it.id == id } }
                val r = saved.rightAnchor?.let { id -> found?.firstOrNull { it.id == id } }
                val axis = Vec3(saved.right.x - saved.left.x, 0f, saved.right.z - saved.left.z).normalized()
                val frame =
                    when {
                        l != null && r != null -> KeyboardFrame.fromEnds(l.pose.position, r.pose.position, saved.count)?.copy(length = saved.length)
                        l != null -> KeyboardFrame(l.pose.position, axis, saved.length, saved.count)
                        r != null -> KeyboardFrame(r.pose.position - axis * saved.length, axis, saved.length, saved.count)
                        else -> null
                    }
                PianoLog.i("ANCHOR startup mode=${saved.mode.tag} left=${l != null} right=${r != null} loaded=${found?.size}")
                // anchors of an older keyboard that a crash or a quit left behind
                found?.filter { it.id != saved.leftAnchor && it.id != saved.rightAnchor }?.forEach { anchors.remove(it.id) }
                update { it.copy(mode = saved.mode, count = if (saved.mode == PianoMode.REAL) saved.count else it.count, virtualCount = if (saved.mode == PianoMode.VIRTUAL) saved.count else it.virtualCount) }
                if (frame != null) {
                    update { it.copy(keyboard = frame, anchorIds = listOfNotNull(saved.leftAnchor, saved.rightAnchor), outlineUntilMs = clock() + OUTLINE_MS, leftEnd = frame.origin, rightEnd = frame.right) }
                    go("S16")
                } else go("S17")
            }
    }

    /** loadAnchor, with the retries that -6 / -5 need (Memory Palace): 3 tries 1 s apart. */
    private suspend fun loadAnchors(): List<AnchorRecord>? {
        repeat(LOAD_TRIES) { i ->
            when (val r = anchors.loadAll()) {
                is AnchorOutcome.Ok -> return r.value
                is AnchorOutcome.Failed -> {
                    PianoLog.w("ANCHOR load failed code=${r.code} try=${i + 1} ${r.message}")
                    if (r.code != -5 && r.code != -6) return null
                }
            }
            delay(LOAD_RETRY_MS)
        }
        return null
    }

    // =========================================================================================
    // real piano: two ends, fine-tune, store
    // =========================================================================================

    private fun startAlign() {
        update { it.copy(mode = PianoMode.REAL, leftEnd = null, rightEnd = null, liveLength = null, mismatch = null, keyboard = null, midiLow = null, midiHigh = null, rangeNote = null, pickingCount = false) }
        go("S03")
    }

    private fun pointTaken(at: Vec3, source: String) {
        val s = _state.value
        PianoLog.i("PIANO take state=${s.designState} src=$source at=${PianoLog.pos(at)}")
        when (s.designState) {
            "S03" -> {
                update { it.copy(leftEnd = at, liveLength = null) }
                go("S04")
            }
            "S04" -> {
                val l = s.leftEnd ?: return
                val len = KeyboardFrame.span(l, at)
                if (len < MIN_LENGTH_M) {
                    PianoLog.i("PIANO right end refused length=${"%.3f".format(Locale.US, len)}")
                    return
                }
                update { it.copy(rightEnd = at) }
                when (val r = LengthSnap.classify(len, s.count)) {
                    is LengthSnap.Result.Mismatch -> {
                        PianoLog.i("PIANO length=${"%.3f".format(Locale.US, len)} mismatch ${r.a.keys}/${r.b.keys}")
                        update { it.copy(mismatch = Triple(len, r.a, r.b)) }
                        go("S06")
                    }
                    else -> {
                        PianoLog.i("PIANO length=${"%.3f".format(Locale.US, len)} result=$r")
                        update { it.copy(keyboard = KeyboardFrame.fromEnds(l, at, s.count)) }
                        go("S05")
                    }
                }
            }
            "S02" -> {
                val k = s.keyboard ?: return
                val left = at.distanceTo(k.origin) <= at.distanceTo(k.right)
                val moved = k.pinned(at, left)
                PianoLog.i("PIANO repin end=${if (left) "left" else "right"} moved_mm=${"%.1f".format(Locale.US, (if (left) at.distanceTo(k.origin) else at.distanceTo(k.right)) * 1000f)}")
                update { it.copy(keyboard = moved, leftEnd = moved.origin, rightEnd = moved.right) }
            }
        }
    }

    private fun moveEnd(left: Boolean, at: Vec3) {
        val s = _state.value
        if (s.designState != "S05") return
        val l = if (left) at else s.leftEnd ?: return
        val r = if (left) s.rightEnd ?: return else at
        val k = KeyboardFrame.fromEnds(l, r, s.count) ?: return
        update { it.copy(leftEnd = l, rightEnd = r, keyboard = k) }
    }

    private fun turn(rad: Float) {
        val s = _state.value
        val k = s.keyboard ?: return
        when (s.designState) {
            "S05", "S02", "S16" -> {
                val t = k.turned(rad)
                update { it.copy(keyboard = t, leftEnd = t.origin, rightEnd = t.right) }
                if (s.designState == "S16") saveKeyboard(t, s.mode)
            }
        }
    }

    private fun alignDone() {
        val s = _state.value
        val k = s.keyboard ?: return
        when (s.designState) {
            "S05" -> {
                saveKeyboard(k, PianoMode.REAL)
                update { it.copy(outlineUntilMs = 0L) }
                go("S07")
            }
            "S02" -> {
                saveKeyboard(k, s.mode)
                go(s.repinFrom ?: "S16")
            }
        }
    }

    /**
     * Two world anchors at the final ends (design: made when 好了 is pressed; drag and turn are already in the
     * ends, no yaw is stored). The old ones are removed once the new ones exist (T15).
     */
    private fun saveKeyboard(k: KeyboardFrame, mode: PianoMode) {
        val s = _state.value
        val old = s.anchorIds
        if (s.noSave) {
            update { it.copy(anchorIds = emptyList()) }
            return
        }
        update { it.copy(anchorIds = emptyList()) }
        scope.launch {
            val l = anchors.create(k.origin, "piano-left")
            val r = anchors.create(k.right, "piano-right")
            val lid = (l as? AnchorOutcome.Ok)?.value?.id
            val rid = (r as? AnchorOutcome.Ok)?.value?.id
            PianoLog.i("ANCHOR create left=${lid?.take(8) ?: (l as? AnchorOutcome.Failed)?.code} right=${rid?.take(8) ?: (r as? AnchorOutcome.Failed)?.code}")
            store.saveKeyboard(SavedKeyboard(mode, k.count, k.length, lid, rid, k.origin, k.right, System.currentTimeMillis()))
            update { it.copy(anchorIds = listOfNotNull(lid, rid)) }
            for (id in old) if (id != lid && id != rid) anchors.remove(id)
        }
    }

    private fun midiNote(midi: Int, on: Boolean) {
        if (!on) return
        val s = _state.value
        if (s.overlay == Overlay.INPUT && midi + s.settings.midiOffset == Keys.MIDDLE_C) update { it.copy(midiTestOk = true) }
        if (s.designState != "S05") return
        val low = minOf(s.midiLow ?: midi, midi)
        val high = maxOf(s.midiHigh ?: midi, midi)
        update { it.copy(midiLow = low, midiHigh = high) }
        if (high - low < MIN_RANGE) return
        when (val r = MidiRange.measure(low, high, s.count)) {
            is MidiRange.Result.Offset -> {
                saveSettings(s.settings.copy(midiOffset = r.semitones))
                val note = String.format(Locale.US, "%d-%d", low, high)
                update { it.copy(rangeNote = note) }
                PianoLog.i("PIANO midi range low=$low high=$high offset=${r.semitones} keys=${r.count?.keys}")
                val counted = r.count
                if (counted != null && counted != s.count) {
                    val len = s.keyboard?.length ?: counted.stdLength
                    update { it.copy(mismatch = Triple(len, counted, s.count)) }
                    go("S06")
                }
            }
            is MidiRange.Result.Transposed -> {
                PianoLog.i("PIANO midi range transposed by ${r.semitones}")
                update { it.copy(rangeNote = "transposed") }
            }
        }
    }

    // =========================================================================================
    // virtual piano: desk, place, move here
    // =========================================================================================

    private fun findDesk() {
        update { it.copy(mode = PianoMode.VIRTUAL, desk = null, pianoPlaced = false, keyboard = null, pickingCount = false, anchorIds = it.anchorIds) }
        go("S22")
        deskJob?.cancel()
        deskJob =
            scope.launch {
                delay(DESK_TIMEOUT_MS)
                if (_state.value.designState == "S22") {
                    PianoLog.i("DESK none after ${DESK_TIMEOUT_MS / 1000}s -> virtual desk")
                    useVirtualDesk()
                }
            }
    }

    private fun deskFound(desk: Desk?) {
        val s = _state.value
        if (desk == null) return
        when (s.designState) {
            "S22" -> {
                deskJob?.cancel()
                PianoLog.i("DESK real at=${PianoLog.pos(desk.center)} ${desk.width}x${desk.depth}")
                update { it.copy(desk = desk, pianoPlaced = false, keyboard = null) }
                go("S24")
            }
            "S23" -> {
                // a real desk turned up: the virtual one goes away
                update { it.copy(desk = desk, pianoPlaced = false, keyboard = null) }
                go("S24")
            }
        }
    }

    private fun useVirtualDesk() {
        deskJob?.cancel()
        val h = head() ?: HeadPose(Vec3(0f, 1.2f, 0f), io.embodit.pianofall.domain.Quat4.IDENTITY)
        val d = Desk.virtualAhead(h)
        PianoLog.i("DESK virtual at=${PianoLog.pos(d.center)}")
        update { it.copy(desk = d, pianoPlaced = false, keyboard = null) }
        go("S23")
    }

    /** T26: the piano straight ahead of the head now, its front edge 0.35 m ahead, at the desk's height (D9). */
    private fun moveHere() {
        val s = _state.value
        val k = s.keyboard ?: return
        val h = head() ?: return
        val f = h.level()
        val c = P2.of(h.position) + f * HERE_M
        val deskTop = k.origin.y - PianoPlacement.KEY_TOP_M
        val placed = PianoPlacement.place(c.at(deskTop), f * -1f, k.count, null).frame
        val desk = s.desk?.takeIf { it.virtual }?.let { d -> Desk((P2.of(placed.center) + f * (Desk.VIRTUAL_D / 2f - 0.05f)).at(d.top), io.embodit.pianofall.domain.yawOf(f * -1f), d.width, d.depth, true) } ?: s.desk
        PianoLog.i("PIANO move here origin=${PianoLog.pos(placed.origin)}")
        update { it.copy(keyboard = placed, desk = desk, overlay = Overlay.NONE, pianoHidden = false) }
        saveKeyboard(placed, PianoMode.VIRTUAL)
    }

    // =========================================================================================
    // library and the run
    // =========================================================================================

    private fun pickSong(id: String) {
        val song = Library.song(id) ?: return
        val mem = _state.value.songs[id] ?: SongMemory()
        val mode = if (mem.played) PlayMode.FOLLOW else PlayMode.LISTEN // never WAIT here: S20 may forbid it
        val hands = if (!song.hasLeft && mem.hands != io.embodit.pianofall.domain.HandSel.RIGHT) io.embodit.pianofall.domain.HandSel.RIGHT else mem.hands
        update { it.copy(songId = id, options = it.options.copy(hands = hands, speed = mem.speed, mode = mode), score = null, overlay = Overlay.NONE) }
        go("S08")
    }

    private fun startRun() {
        val s = _state.value
        val mem = s.songs[s.songId] ?: SongMemory()
        val nextMem = mem.copy(speed = s.options.speed, hands = s.options.hands)
        store.saveSong(s.songId, nextMem)
        update {
            it.copy(
                runToken = it.runToken + 1,
                progress = 0f,
                playSec = 0f,
                score = null,
                newBest = false,
                octaveHint = null,
                waitingKeys = emptySet(),
                songs = it.songs + (s.songId to nextMem),
                showLegend = !it.settings.legendSeen,
                handsLeft = false,
                handsRight = false,
                overlay = Overlay.NONE,
            )
        }
        if (!s.settings.legendSeen) saveSettings(_state.value.settings.copy(legendSeen = true))
        // listening needs no hands on the keys
        if (s.options.mode == PlayMode.LISTEN) countdown(3) else go("S21")
    }

    private fun handsSeen(left: Boolean, right: Boolean) {
        val s = _state.value
        update { it.copy(handsLeft = left, handsRight = right) }
        if (s.designState != "S21") return
        val need =
            when (s.options.hands) {
                io.embodit.pianofall.domain.HandSel.BOTH -> left && right
                io.embodit.pianofall.domain.HandSel.LEFT -> left
                io.embodit.pianofall.domain.HandSel.RIGHT -> right
            }
        if (need) countdown(3)
    }

    /** S09: [beats] beats of the song's tempo at the chosen speed, then S10 (design S09 / S12 继续 = 1 beat). */
    private fun countdown(beats: Int) {
        countdownJob?.cancel()
        val s = _state.value
        val beatMs = (60_000f / (s.song.bpm * s.options.speed)).toLong().coerceIn(BEAT_MIN_MS, BEAT_MAX_MS)
        update { it.copy(countdown = beats) }
        go("S09")
        countdownJob =
            scope.launch {
                for (n in beats downTo 1) {
                    update { it.copy(countdown = n) }
                    delay(beatMs)
                }
                if (_state.value.designState == "S09") {
                    update { it.copy(countdown = 0) }
                    go("S10")
                }
            }
    }

    private fun pause() {
        countdownJob?.cancel()
        val s = _state.value
        update { it.copy(pausedFrom = s.designState) }
        go("S12")
    }

    private fun perf(e: PerfEvent) {
        val s = _state.value
        when (e) {
            is PerfEvent.Waiting -> {
                update { it.copy(waitingKeys = e.keys) }
                if (e.on && s.designState == "S10") go("S11")
                if (!e.on && s.designState == "S11") go("S10")
            }
            is PerfEvent.OctaveHint -> update { it.copy(octaveHint = e.semitones) }
            is PerfEvent.Finished -> finish(e.score)
            else -> Unit
        }
    }

    private fun finish(score: io.embodit.pianofall.domain.Score?) {
        val s = _state.value
        val mem = s.songs[s.songId] ?: SongMemory()
        var best = mem
        var newBest = false
        if (score != null && s.options.mode == PlayMode.FOLLOW) {
            if (score.accuracy > mem.bestAccuracy) {
                best = best.copy(bestAccuracy = score.accuracy, bestStars = maxOf(mem.bestStars, score.stars))
                newBest = true
            }
        }
        best = best.copy(played = true)
        if (!s.noSave) store.saveSong(s.songId, best)
        PianoLog.i("PIANO finished song=${s.songId} mode=${s.options.mode.tag} score=$score newBest=$newBest")
        update { it.copy(score = score, newBest = newBest, songs = it.songs + (s.songId to best), progress = 1f, waitingKeys = emptySet()) }
        go("S13")
    }

    private fun positionLost(lost: Boolean) {
        val s = _state.value
        if (lost) {
            if (s.designState == "S18" || s.keyboard == null || s.aligning || s.designState in setOf("S00", "S01", "S22", "S23", "S24")) return
            countdownJob?.cancel()
            update { it.copy(pausedFrom = if (s.inRun) s.designState else s.pausedFrom, lostSinceMs = clock(), lostTick = 0, repinFrom = s.designState) }
            PianoLog.i("ANCHOR position lost in ${s.designState}")
            go("S18")
            lostJob?.cancel()
            lostJob = scope.launch {
                delay(LOST_REPIN_MS)
                update { it.copy(lostTick = it.lostTick + 1) } // a new state: the panel re-reads the time, the re-pin button shows
            }
        } else if (s.designState == "S18") {
            PianoLog.i("ANCHOR position back")
            go(if (s.pausedFrom != null && s.repinFrom in PianoUiState.RUN_STATES) "S12" else s.repinFrom?.takeIf { it in setOf("S07", "S08", "S12", "S13", "S16") } ?: "S07")
        }
    }

    // =========================================================================================
    // helpers
    // =========================================================================================

    private fun saveSettings(settings: io.embodit.pianofall.domain.Settings) {
        store.saveSettings(settings)
        update { it.copy(settings = settings) }
    }

    private fun update(f: (PianoUiState) -> PianoUiState) {
        _state.value = f(_state.value)
    }

    private fun go(id: String) {
        update { it.copy(designState = id) }
        if (id != lastLogged) {
            lastLogged = id
            PianoLog.i("STATE $id mode=${_state.value.mode.tag}")
        }
    }

    // =========================================================================================
    // debug seams (src/debug drives them)
    // =========================================================================================

    /** Debug `seedKeyboard`: an aligned keyboard as if S05 好了 had been pressed (anchors made too). */
    fun debugKeyboard(frame: KeyboardFrame, mode: PianoMode, save: Boolean = true) {
        countdownJob?.cancel()
        update { it.copy(mode = mode, keyboard = frame, leftEnd = frame.origin, rightEnd = frame.right, count = if (mode == PianoMode.REAL) frame.count else it.count, virtualCount = if (mode == PianoMode.VIRTUAL) frame.count else it.virtualCount, pianoPlaced = true, mismatch = null) }
        if (save) saveKeyboard(frame, mode)
    }

    fun debugDesk(desk: Desk) = update { it.copy(desk = desk) }

    fun debugMidi(name: String?) = onEvent(PianoEvent.MidiDevice(name, true))

    fun debugJump(id: String) {
        countdownJob?.cancel()
        deskJob?.cancel()
        val s = _state.value
        when (id) {
            "S14" -> update { it.copy(overlay = Overlay.SETTINGS) }
            "S15" -> update { it.copy(overlay = Overlay.INPUT) }
            "S20" -> {
                update { it.copy(overlay = Overlay.NONE, midiName = null, mode = PianoMode.REAL) }
                go("S08")
            }
            "S01" -> {
                update { it.copy(overlay = Overlay.NONE, pickingCount = false) }
                go("S01")
            }
            "S06" -> {
                val l = s.leftEnd ?: s.keyboard?.origin ?: return
                val dir = s.keyboard?.xAxis ?: Vec3(1f, 0f, 0f)
                val r = l + dir * 0.74f
                update { it.copy(overlay = Overlay.NONE, leftEnd = l, rightEnd = r, mismatch = Triple(0.74f, KeyCount.K49, KeyCount.K61)) }
                go("S06")
            }
            "S09" -> {
                update { it.copy(overlay = Overlay.NONE, runToken = it.runToken + 1) }
                countdown(3)
            }
            "S12" -> {
                update { it.copy(overlay = Overlay.NONE, pausedFrom = "S10") }
                go("S12")
            }
            "S13" -> {
                update { it.copy(overlay = Overlay.NONE) }
                go("S13")
            }
            "S18" -> positionLost(true)
            "S22" -> findDesk()
            "S23" -> {
                findDesk()
                useVirtualDesk()
            }
            "S24" -> {
                if (s.desk == null) useVirtualDesk()
                update { it.copy(pianoPlaced = false, keyboard = null, mode = PianoMode.VIRTUAL) }
                go("S24")
            }
            "S03" -> startAlign()
            else -> {
                update { it.copy(overlay = Overlay.NONE) }
                go(id)
            }
        }
    }

    /** Debug: a finished run with this score (S13). */
    fun debugFinish(score: io.embodit.pianofall.domain.Score?) = finish(score)

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = context.applicationContext
            return PianoViewModel(PianoGraph.anchors, PianoGraph.store(app), { PianoGraph.head.pose() }) as T
        }
    }

    companion object {
        const val MIDDLE_C_MS = 3_000L
        const val OUTLINE_MS = 5_000L
        const val DESK_TIMEOUT_MS = 5_000L
        const val LOAD_TRIES = 3
        const val LOAD_RETRY_MS = 1_000L
        const val MIN_LENGTH_M = 0.30f
        const val MIN_RANGE = 24
        const val BEAT_MIN_MS = 450L
        const val BEAT_MAX_MS = 1_100L
        const val LOST_REPIN_MS = 5_000L
        const val HERE_M = 0.35f
        val ON_KEYS_PLAY = setOf("S07", "S08", "S12", "S13", "S16")
    }
}
