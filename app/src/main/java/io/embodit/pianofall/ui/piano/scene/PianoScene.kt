package io.embodit.pianofall.ui.piano.scene

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.AnchorComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.anchor.AnchorTarget
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.pianofall.domain.Desk
import io.embodit.pianofall.domain.Desks
import io.embodit.pianofall.domain.DragLine
import io.embodit.pianofall.domain.AssistLevel
import io.embodit.pianofall.domain.FingerPress
import io.embodit.pianofall.domain.KeyAssist
import io.embodit.pianofall.domain.Geo2
import io.embodit.pianofall.domain.HandPart
import io.embodit.pianofall.domain.HeadPose
import io.embodit.pianofall.domain.KeyCount
import io.embodit.pianofall.domain.KeyboardFrame
import io.embodit.pianofall.domain.Keys
import io.embodit.pianofall.domain.LengthSnap
import io.embodit.pianofall.domain.P2
import io.embodit.pianofall.domain.PanelFacing
import io.embodit.pianofall.domain.PerfEvent
import io.embodit.pianofall.domain.Performance
import io.embodit.pianofall.domain.PianoPlacement
import io.embodit.pianofall.domain.PlayMode
import io.embodit.pianofall.domain.PlayNote
import io.embodit.pianofall.domain.Quat4
import io.embodit.pianofall.domain.Ray
import io.embodit.pianofall.domain.Vec3
import io.embodit.pianofall.domain.yawOf
import io.embodit.pianofall.platform.PianoGraph
import io.embodit.pianofall.platform.PianoLog
import io.embodit.pianofall.platform.audio.PianoAudio
import io.embodit.pianofall.platform.midi.MidiInput
import io.embodit.pianofall.ui.piano.Overlay
import io.embodit.pianofall.ui.piano.PianoEvent
import io.embodit.pianofall.ui.piano.PianoUiState
import io.embodit.pianofall.ui.piano.SeekKind
import io.embodit.pianofall.ui.piano.components.PanelSizes
import io.embodit.pianofall.ui.theme.PianoColors
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object ActiveScene {
    @Volatile var scene: PianoScene? = null
}

/** The one per-frame ECS system of the app (`registerSystem` in PianoScreen). */
class PianoFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/** Panel ids of the pool: the side panel (P1 with its overlays P3 / P4) and the mini bar (P2). */
object PanelIds {
    const val SIDE = "side"
    const val MINI = "mini"

    val pool: List<String> = listOf(SIDE, MINI)
}

/**
 * The 3D side of the app. It owns the keyboard root with the curtain, bars and key lights, the virtual piano and
 * desk, the panel pool, the run's [Performance] (clock, wait mode, scoring) and the piano's voice; it starts and
 * stops the sensing (head, controllers, hands, planes, anchors, MIDI) and turns them into key presses and events.
 * Per-frame work happens in [onFrame], never through Compose: the only Compose state written here is which panel
 * is in view. Main thread only.
 */
class PianoScene(private val sink: (PianoEvent) -> Unit) {

    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private val head = PianoGraph.head
    private val controller = PianoGraph.controller
    private val hands = PianoGraph.hands
    private val planes = PianoGraph.planes
    private var audio: PianoAudio? = null
    private var midi: MidiInput? = null
    private var content: SpatialViewContent? = null
    private var contentProbe: Entity? = null
    private var stageRoot: Entity? = null
    private var keyboardRoot: Entity? = null
    private var rig: PanelRig? = null
    private var shared: SharedResources? = null
    private var materials: PianoMaterials? = null
    private var meshes: PianoMeshes? = null
    private var colliders: RoomColliders? = null
    private var views: KeyboardViews? = null
    private var piano: VirtualPianoView? = null
    private var desk: DeskViews? = null
    private var textures = ArrayList<TextureResource>()
    private var built = false
    private var attached = false
    private var paused = false
    private var buildJob: Job? = null

    private var pose = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var ui = PianoUiState()
    private var frames = 0L
    private var lastLogMs = 0L
    private var lastFrameMs = -1L
    private var lastLost = false
    private var lastController = false

    // ---- the run
    private var perf: Performance? = null
    private var perfToken = -1
    private var perfSeek = 0
    private val lights = KeyLights()
    private val visibleNotes = ArrayList<PlayNote>()
    private var lastProgressMs = 0L
    private var lastBeat = Int.MIN_VALUE
    private var lastPerfState = ""

    // ---- live edits (written to the ViewModel on release)
    private var liveTurn = 0f
    private var turning = false
    private var neutralSinceMs = -1L
    private var liveEnd: Pair<Boolean, Vec3>? = null
    private var grab: Grab? = null
    private var ghostYaw = 0f
    private var ghostFrame: KeyboardFrame? = null
    private var ghostSnapped = false

    /** The ghost was put somewhere by the system pointer / a debug hook: no controller ray moves it away. */
    private var ghostPinned = false
    private var liveDesk: Desk? = null
    private var deskGrab: Pair<Vec3, Desk>? = null
    private var pianoGrab: Pair<Vec3, KeyboardFrame>? = null
    private var livePiano: KeyboardFrame? = null

    private class Grab(val left: Boolean, val startEnd: Vec3, val startHit: Vec3, val byController: Boolean) {
        var hand = Vec3.ZERO
        var prevHand = Vec3.ZERO
        var total = Vec3.ZERO
        var dir: Vec3? = null
        var depth = 1f
    }

    // ---- input
    private val fingers = FingerPress({ shownFrame()?.layout }, { assistTargets() })
    private val ctrlKey = HashMap<Boolean, Int>() // controller (right?) -> key held by trigger
    private val tipKey = HashMap<Boolean, Int>() // controller (right?) -> key held by its tip
    private var pinchWasDown = false
    private var dwellAt: Vec3? = null
    private var dwellSinceMs = 0L
    private var lastTaken: Vec3? = null
    private var lastLiveMs = 0L
    private var lastHandsSeen = Pair(false, false)
    private var deskSent: String? = null

    // ---- anchors
    private val anchorEntities = HashMap<String, Entity>()
    private var anchorKey: List<String> = emptyList()
    private var anchorStill = 0
    private var anchorPrev: Pair<Vec3, Vec3>? = null
    private var positionLost = false
    private var agreeSinceMs = -1L
    private var lastAnchorMoveMs = 0L

    /** Debug: the white-model piano under a real keyboard (E8, emulator QA). */
    var debugWhite = false

    /** Debug: the anchors count as disagreeing by this much (mm), 0 = off. */
    var debugAnchorJumpMm = 0f

    /** Debug: no desk counts (S22 runs into the timeout). */
    var debugNoDesk = false

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(context: Context, content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = Entity()
        runCatching { root.setName("stageRoot") }
        content.addEntity(root)
        val probe = Entity()
        runCatching { probe.setName("contentProbe") }
        content.addEntity(probe)
        contentProbe = probe
        // An entity added to the SpatialView is not at the Stage origin (Spatial Ruler): move the root once
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        val kr = Entity()
        runCatching { kr.setName("keyboardRoot") }
        root.addChild(kr)
        keyboardRoot = kr
        PianoLog.i("STAGE root offset=${PianoLog.pos(offset.domain())}")

        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        rig.world(PanelIds.SIDE, PanelSizes.SIDE_W, PanelSizes.SIDE_H, PANEL_SCALE)
        rig.world(PanelIds.MINI, PanelSizes.MINI_W, PanelSizes.MINI_H, PANEL_SCALE)
        midi = MidiInput(context.applicationContext).also { m ->
            m.onNote = { note, vel, on, at -> onMidi(note, vel, on, at) }
            m.onDevice = { name -> sink(PianoEvent.MidiDevice(name, m.available)) }
        }
        ActiveScene.scene = this
        attached = true
        startServices()
        sink(PianoEvent.MidiDevice(null, midi?.available ?: false))
        bindPanels(attachments)
        buildJob =
            scope?.launch {
                try {
                    build(root, kr, t0, context)
                } catch (t: Throwable) {
                    if (t !is CancellationException) PianoLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
        PianoLog.i("SCENE attached")
    }

    private fun startServices() {
        head.start()
        controller.start()
        hands.start()
        PianoGraph.anchors.start()
        planes.start { colliders?.sync(planes.all) }
        midi?.start()
    }

    /**
     * Materials, meshes and textures, then the entities, one step at a time: a mesh costs about a rendered frame
     * and blocking the main thread for all of them raised an ANR on the emulator (Trace Light).
     */
    private suspend fun build(root: Entity, kr: Entity, t0: Long, context: Context) {
        // the sampler opens 30 asset files: off the main thread, while the Stage comes up (cold-start focus ANR)
        val a = withContext(Dispatchers.IO) { PianoAudio(context.applicationContext) }
        if (!attached) {
            a.release()
            return
        }
        audio = a.also {
            it.muted = ui.settings.muted
            it.volume = ui.settings.volume
        }
        delay(BUILD_START_MS)
        val shared = SharedResources()
        this.shared = shared
        val m = PianoMaterials(shared)
        materials = m
        delay(BUILD_STEP_MS)
        val meshes = PianoMeshes(shared)
        this.meshes = meshes
        delay(BUILD_STEP_MS)
        val digits = listOf("1", "2", "3").map { textured(m, Plates.text(it, 256, 256, PianoColors.White.toArgb())) }
        val labels = KeyCount.entries.associate { it.keys to textured(m, Plates.text(it.keys.toString(), 256, 128, PianoColors.Outline.toArgb())) }
        delay(BUILD_STEP_MS)
        val colliders = RoomColliders(root)
        colliders.buildBackstop()
        this.colliders = colliders
        colliders.sync(planes.all)
        delay(BUILD_STEP_MS)
        views = KeyboardViews(kr, meshes, m, digits, labels)
        delay(BUILD_STEP_MS)
        piano = VirtualPianoView(kr, meshes, m)
        delay(BUILD_STEP_MS)
        desk = DeskViews(root, meshes, m)
        built = true
        PianoLog.i("SCENE built ms=${SystemClock.elapsedRealtime() - t0} audioReady=${audio?.ready}")
        render(ui)
        sink(PianoEvent.SceneReady)
    }

    private fun textured(m: PianoMaterials, bmp: android.graphics.Bitmap): Material {
        val tex = TextureResource.create(bmp)
        shared?.keep(tex)
        return m.textured(tex)
    }

    private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
        ((alpha * 255).toInt() shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()

    /** The Stage went to the background: every tracker stops, the run pauses (design `container.lifecycle`). */
    fun pause() {
        if (paused || !attached) return
        paused = true
        sink(PianoEvent.Background)
        audio?.allOff()
        piano?.releaseAll()
        planes.stop()
        head.stop()
        controller.stop()
        hands.stop()
        midi?.stop()
        grab = null
        turning = false
        PianoLog.i("SCENE paused")
    }

    fun resume() {
        if (!paused || !attached) return
        paused = false
        startServices()
        PianoLog.i("SCENE resumed")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        if (!rig.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        buildJob?.cancel()
        scope?.cancel()
        scope = null
        planes.stop()
        head.stop()
        controller.stop()
        hands.stop()
        PianoGraph.anchors.stop()
        midi?.stop()
        midi = null
        audio?.release()
        audio = null
        perf = null
        panelShown.clear()
        runCatching { views?.release() }
        runCatching { desk?.release() }
        views = null
        piano = null
        desk = null
        runCatching { colliders?.clear() }
        colliders = null
        runCatching { rig?.release() }
        anchorEntities.values.forEach { runCatching { it.destroy(true) } }
        anchorEntities.clear()
        anchorKey = emptyList()
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        materials = null
        meshes = null
        stageRoot = null
        keyboardRoot = null
        contentProbe = null
        rig = null
        content = null
        built = false
        hasHead = false
        PianoLog.i("SCENE detached")
    }

    // =========================================================================================
    // state -> scene
    // =========================================================================================

    fun render(state: PianoUiState) {
        val before = ui
        ui = state
        audio?.muted = state.settings.muted
        audio?.volume = state.settings.volume
        if (state.designState != before.designState) onStateChange(before.designState, state.designState)
        if (state.runToken != perfToken) newRun(state)
        if (state.seekToken != perfSeek) {
            perfSeek = state.seekToken
            perf?.let { p ->
                when (state.seekTo) {
                    SeekKind.RESTART -> p.restart()
                    SeekKind.BACK_BAR -> p.backBar()
                    else -> Unit
                }
                lights.clear()
                audio?.allOff()
                piano?.releaseAll()
            }
        }
        perf?.rate = state.options.speed
        // S08 shows the song's first bar resting at the top of the curtain
        if (state.designState == "S08" && (perf == null || perf?.song?.id != state.songId || perf?.options != state.options || perf?.canScore != state.canScore)) preview(state)
        if (state.anchorIds != anchorKey) bindAnchors(state.anchorIds)
    }

    private fun onStateChange(from: String, to: String) {
        PianoLog.i("SCENE state $from -> $to")
        if (to !in setOf("S05", "S02", "S16", "S23", "S24")) {
            turning = false
            liveTurn = 0f
        }
        if (to != "S05" && to != "S02") {
            grab = null
            liveEnd = null
        }
        if (to == "S03") sidePanelFollow.reset()
        if (to == "S24") {
            ghostYaw = 0f
            pianoGrab = null
            livePiano = null
        }
        if (to in setOf("S22", "S23")) deskSent = null
        if (to == "S10" && from == "S09") perf?.let { p -> if (p.waiting) sink(PianoEvent.Perf(PerfEvent.Waiting(true, p.pendingKeys))) }
        if (to in setOf("S07", "S13", "S01")) {
            lights.clear()
            if (to != "S13") audio?.allOff()
        }
        if (from in PianoUiState.RUN_STATES && to !in PianoUiState.RUN_STATES) {
            audio?.allOff()
            piano?.releaseAll()
            perf?.let { PianoLog.i(String.format(Locale.US, "PIANO run stop t=%.2f state=%s", it.t, to)) }
        }
    }

    private fun newRun(state: PianoUiState) {
        perfToken = state.runToken
        perfSeek = state.seekToken
        lights.clear()
        audio?.allOff()
        piano?.releaseAll()
        preview(state)
        lastBeat = Int.MIN_VALUE
        perf?.let { PianoLog.i("PIANO run song=${state.songId} mode=${state.options.mode.tag} hands=${state.options.hands.tag} speed=${state.options.speed} score=${it.canScore} transpose=${it.transpose} notes=${it.notes.size}") }
    }

    private fun preview(state: PianoUiState) {
        val k = state.keyboard
        val shift = if (k != null) state.song.octaveShiftFor(k.count) else 0
        // the assist level's timing windows only for the virtual piano (a real piano keeps the design's ±150 / 300 ms)
        val a = if (state.virtual) state.settings.assist else AssistLevel.STANDARD
        perf = Performance(state.song, state.options, state.canScore, shift, state.settings.lookAheadSec.toFloat(), a.perfectMs, a.lateMs)
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        frames++
        head.pose()?.let {
            pose = it
            hasHead = it.position.length > 1e-3f
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(pose)
        if (!attached || paused) return
        val dt = if (lastFrameMs < 0) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
        pollTracking(now)
        if (built) {
            val edges = controller.takeEdges()
            val samples = hands.sample()
            input(now, dt, edges, samples)
            stepRun(now, dt)
            draw(now)
            watchAnchors(now)
            findDesk()
        }
        if (hasHead) placePanels(now)
        rig.faceViewer(pose.position)
        lastFrameMs = now
        if (now - lastLogMs > LOG_MS) {
            lastLogMs = now
            PianoLog.i("FRAME n=$frames head=${PianoLog.pos(pose.position)} state=${ui.designState} planes=${planes.all.size} ctrl=${controller.status()} t=${perf?.t?.let { "%.2f".format(Locale.US, it) }}")
        }
    }

    private fun pollTracking(now: Long) {
        val lost = head.lost(now)
        if (lost != lastLost) {
            lastLost = lost
            sink(PianoEvent.TrackingChanged(lost))
        }
        val tracked = controller.hands().isNotEmpty()
        if (tracked != lastController) {
            lastController = tracked
            sink(PianoEvent.ControllerChanged(tracked))
        }
    }

    /** The keyboard as drawn now: the stored one with the live thumbstick turn, end drag or piano drag. */
    private fun shownFrame(): KeyboardFrame? {
        if (ui.designState == "S24" && !ui.pianoPlaced) return null
        var k = livePiano ?: ui.keyboard ?: return null
        liveEnd?.let { (left, at) ->
            val l = if (left) at else k.origin
            val r = if (left) k.right else at
            k = KeyboardFrame.fromEnds(l, r, k.count) ?: k
        }
        if (turning && liveTurn != 0f) k = k.turned(liveTurn)
        return k
    }

    // =========================================================================================
    // input
    // =========================================================================================


    private fun input(now: Long, dt: Float, edges: io.embodit.pianofall.platform.tracking.ControllerInput.Edges, samples: List<io.embodit.pianofall.platform.tracking.HandSample>) {
        val state = ui.designState
        val aim = controller.aiming()
        val rightIndex = samples.firstOrNull { it.right }?.index
        // pause from the controller during a run: any button; on a real piano the trigger too (design S10)
        if (state in PianoUiState.RUN_STATES && (edges.button || (!ui.virtual && (edges.downLeft || edges.downRight)))) {
            PianoLog.i("PAUSE controller button")
            sink(PianoEvent.PauseClicked)
            return
        }
        when (state) {
            "S02", "S03", "S04" -> takeCorner(now, edges, aim, samples, rightIndex)
            "S05" -> {
                stick(now, dt, MAX_TURN_REAL)
                dragEnds(edges, aim)
            }
            "S16" -> if (!ui.virtual) stick(now, dt, MAX_TURN_REAL) else stick(now, dt, MAX_TURN_VIRTUAL)
            "S23" -> moveDesk(now, dt, edges, aim)
            "S24" -> placePiano(now, dt, edges, aim)
        }
        if (state == "S21") {
            val l = samples.any { !it.right }
            val r = samples.any { it.right }
            if ((l to r) != lastHandsSeen) {
                lastHandsSeen = l to r
                sink(PianoEvent.HandsSeen(l, r))
            }
        }
        if (ui.virtual && !ui.pianoHidden && state in PLAYABLE) playVirtual(now, edges, samples)
    }

    /** S02 - S04: the controller tip + trigger, or the right index tip + a left pinch (or 1.5 s still). */
    private fun takeCorner(now: Long, edges: io.embodit.pianofall.platform.tracking.ControllerInput.Edges, aim: io.embodit.pianofall.platform.tracking.ControllerInput.Hand?, samples: List<io.embodit.pianofall.platform.tracking.HandSample>, rightIndex: Vec3?) {
        val hs = controller.hands()
        val pressed = when {
            edges.downRight -> hs.firstOrNull { it.right }
            edges.downLeft -> hs.firstOrNull { !it.right }
            else -> null
        }
        if (pressed != null) {
            take(pressed.tip, "controller")
            return
        }
        val leftPinch = samples.firstOrNull { !it.right }?.pinch ?: 0f
        val pinchDown = leftPinch > PINCH_ON
        if (rightIndex != null && pinchDown && !pinchWasDown) {
            pinchWasDown = true
            take(rightIndex, "hand-pinch")
            return
        }
        if (leftPinch < PINCH_OFF) pinchWasDown = false
        // dwell: the index tip held still 1.5 s (no controller in the hands)
        if (rightIndex != null && hs.isEmpty()) {
            val d = dwellAt
            if (d == null || d.distanceTo(rightIndex) > DWELL_M) {
                dwellAt = rightIndex
                dwellSinceMs = now
            } else if (now - dwellSinceMs >= DWELL_MS && (lastTaken?.distanceTo(rightIndex) ?: 1f) > 0.02f) {
                take(rightIndex, "hand-dwell")
                dwellSinceMs = now + 60_000L
            }
        } else dwellAt = null
        val tip = aim?.tip ?: rightIndex
        if (ui.designState == "S04" && now - lastLiveMs > 100L) {
            lastLiveMs = now
            val l = ui.leftEnd
            sink(PianoEvent.LiveLength(if (l != null && tip != null) KeyboardFrame.span(l, tip) else null))
        }
    }

    private fun take(at: Vec3, source: String) {
        lastTaken = at
        sink(PianoEvent.PointTaken(at, source))
    }

    /** Thumbstick X turns the keyboard (S05: 3°/s at most, dead zone 0.15), written 150 ms after release. */
    private fun stick(now: Long, dt: Float, maxDegPerSec: Float) {
        val x = controller.stickX(now)
        val rate = stickRate(x, maxDegPerSec)
        if (rate != 0f && ui.keyboard != null && grab == null) {
            if (!turning) {
                turning = true
                liveTurn = 0f
            }
            // stick right turns clockwise seen from above
            liveTurn -= Math.toRadians((rate * dt).toDouble()).toFloat()
            neutralSinceMs = -1L
        } else if (turning) {
            if (neutralSinceMs < 0) neutralSinceMs = now else if (now - neutralSinceMs >= STICK_RELEASE_MS) {
                turning = false
                val d = liveTurn
                liveTurn = 0f
                PianoLog.i(String.format(Locale.US, "STICK turn deg=%.2f", Math.toDegrees(d.toDouble())))
                if (d != 0f) {
                    if (ui.designState == "S24" || (ui.virtual && ui.designState == "S16")) ui.keyboard?.let { sink(PianoEvent.PianoPlaced(it.turned(d), false)) } else sink(PianoEvent.KeyboardTurned(d))
                }
            }
        }
    }

    /** S05: point at an end ring and hold the trigger; the end follows at half the hand's travel (design 0.5). */
    private fun dragEnds(edges: io.embodit.pianofall.platform.tracking.ControllerInput.Edges, aim: io.embodit.pianofall.platform.tracking.ControllerInput.Hand?) {
        val k = ui.keyboard ?: return
        val g = grab
        if (g == null) {
            if (aim == null || !(edges.downLeft || edges.downRight)) return
            val ray = aim.ray
            val ends = listOf(true to k.origin, false to k.right)
            val hit = ends.minByOrNull { (_, p) -> rayDistance(ray, p) } ?: return
            if (rayDistance(ray, hit.second) > GRAB_RADIUS) return
            val t = ray.toLevel(k.origin.y) ?: return
            grab = Grab(hit.first, hit.second, ray.at(t), true)
            PianoLog.i("DRAG end start left=${hit.first} by=controller")
            return
        }
        if (!g.byController) return
        val held = aim != null && controller.triggerHeld(aim.right)
        if (held && aim != null) {
            val t = aim.ray.toLevel(k.origin.y) ?: return
            val now = aim.ray.at(t)
            liveEnd = g.left to (g.startEnd + Vec3(now.x - g.startHit.x, 0f, now.z - g.startHit.z) * DRAG_GAIN)
        } else {
            grab = null
            liveEnd?.let { (left, at) -> sink(PianoEvent.EndMoved(left, at)) }
            liveEnd = null
            PianoLog.i("DRAG end done")
        }
    }

    private fun rayDistance(ray: Ray, p: Vec3): Float {
        val t = (p - ray.origin).dot(ray.dir)
        if (t < 0f) return Float.MAX_VALUE
        return (ray.at(t) - p).length
    }

    /** S23: thumbstick turns the virtual desk (45°/s), trigger held on it drags it. */
    private fun moveDesk(now: Long, dt: Float, edges: io.embodit.pianofall.platform.tracking.ControllerInput.Edges, aim: io.embodit.pianofall.platform.tracking.ControllerInput.Hand?) {
        val d = ui.desk?.takeIf { it.virtual } ?: return
        var live = liveDesk ?: d
        val rate = stickRate(controller.stickX(now), MAX_TURN_VIRTUAL)
        if (rate != 0f) {
            live = live.copy(yaw = live.yaw - Math.toRadians((rate * dt).toDouble()).toFloat())
            liveDesk = live
            neutralSinceMs = -1L
        } else if (liveDesk != null && deskGrab == null) {
            if (neutralSinceMs < 0) neutralSinceMs = now else if (now - neutralSinceMs >= STICK_RELEASE_MS) {
                sink(PianoEvent.DeskMoved(live))
                liveDesk = null
            }
        }
        val grab = deskGrab
        if (aim != null) {
            val t = aim.ray.toLevel(d.top)
            val hit = t?.let { aim.ray.at(it) }
            if (grab == null && (edges.downLeft || edges.downRight) && hit != null && d.contains(P2.of(hit))) deskGrab = hit to live
            if (grab != null) {
                if (controller.triggerHeld(aim.right) && hit != null) {
                    val (h0, d0) = grab
                    liveDesk = d0.copy(center = d0.center + Vec3(hit.x - h0.x, 0f, hit.z - h0.z))
                } else {
                    deskGrab = null
                    liveDesk?.let { sink(PianoEvent.DeskMoved(it)) }
                    liveDesk = null
                }
            }
            desk?.drawRay(aim.position, hit ?: aim.ray.at(1.2f))
        } else desk?.drawRay(null, null)
    }

    /**
     * S24 (T22): the ghost piano follows where the controller ray meets the desk, its front edge towards the user,
     * the thumbstick turns it about its own vertical axis; the trigger puts it down. Near a desk edge it snaps.
     * Put down, the trigger held on it drags it again.
     */
    private fun placePiano(now: Long, dt: Float, edges: io.embodit.pianofall.platform.tracking.ControllerInput.Edges, aim: io.embodit.pianofall.platform.tracking.ControllerInput.Hand?) {
        val d = ui.desk ?: return
        val rate = stickRate(controller.stickX(now), MAX_TURN_VIRTUAL)
        if (ui.pianoPlaced) {
            stick(now, dt, MAX_TURN_VIRTUAL)
            val k = ui.keyboard ?: return
            if (aim == null) {
                desk?.drawRay(null, null)
                return
            }
            val t = aim.ray.toLevel(d.top)
            val hit = t?.let { aim.ray.at(it) }
            desk?.drawRay(aim.position, hit ?: aim.ray.at(1.2f))
            val g = pianoGrab
            if (g == null && (edges.downLeft || edges.downRight) && hit != null && onPiano(k, hit)) {
                pianoGrab = hit to k
                PianoLog.i("DRAG piano start")
            } else if (g != null) {
                if (controller.triggerHeld(aim.right) && hit != null) {
                    val (h0, k0) = g
                    val c = k0.toWorld(Vec3(k0.length / 2f, 0f, 0f)) + Vec3(hit.x - h0.x, 0f, hit.z - h0.z)
                    val placed = PianoPlacement.place(Vec3(c.x, d.top, c.z), P2.of(k0.zAxis), k0.count, d)
                    livePiano = placed.frame
                    ghostSnapped = placed.snapped
                } else {
                    pianoGrab = null
                    livePiano?.let { sink(PianoEvent.PianoPlaced(it, ghostSnapped)) }
                    livePiano = null
                }
            }
            return
        }
        if (rate != 0f) ghostYaw -= Math.toRadians((rate * dt).toDouble()).toFloat()
        if (aim == null) {
            desk?.drawRay(null, null)
            if (ghostFrame != null && !ghostPinned) {
                ghostFrame = null
                sink(PianoEvent.GhostChanged(false, false))
            }
            return
        }
        val t = aim.ray.toLevel(d.top)
        val hit = t?.let { aim.ray.at(it) }
        desk?.drawRay(aim.position, hit ?: aim.ray.at(1.2f))
        if (hit == null) {
            ghostFrame = null
            sink(PianoEvent.GhostChanged(false, false))
            return
        }
        val placed = ghostAt(hit, d)
        ghostPinned = false
        ghostFrame = placed.frame
        ghostSnapped = placed.snapped
        val onDesk = d.contains(P2.of(hit))
        sink(PianoEvent.GhostChanged(onDesk, placed.snapped))
        if ((edges.downLeft || edges.downRight) && onDesk) sink(PianoEvent.PianoPlaced(placed.frame, placed.snapped))
    }

    private fun ghostAt(hit: Vec3, d: Desk): PianoPlacement.Placed {
        val toHead = P2(pose.position.x - hit.x, pose.position.z - hit.z).let { if (it.length < 0.05f) P2(0f, 1f) else it.normalized() }
        val facing = P2.dir(yawOf(toHead) + ghostYaw)
        return PianoPlacement.place(Vec3(hit.x, d.top, hit.z), facing, ui.virtualCount, d)
    }

    private fun onPiano(k: KeyboardFrame, p: Vec3): Boolean {
        val l = k.toLocal(p)
        return l.x in -0.04f..(k.length + 0.04f) && l.z in -0.24f..0.04f
    }

    /** Keys of the virtual piano: fingertips on the desk (T23) and the controller (T24). */
    private fun playVirtual(now: Long, edges: io.embodit.pianofall.platform.tracking.ControllerInput.Edges, samples: List<io.embodit.pianofall.platform.tracking.HandSample>) {
        val k = shownFrame() ?: return
        val l = k.layout
        fingers.touchM = if (ui.desk?.virtual == false) FingerPress.TOUCH_REAL_M else FingerPress.TOUCH_VIRTUAL_M
        fingers.assist = ui.settings.assist
        val assist = ui.settings.assist
        val seen = HashSet<Int>()
        for (h in samples) {
            h.tips.forEachIndexed { i, tip ->
                val id = (if (h.right) 5 else 0) + i
                if (tip == null) {
                    fingers.lost(id)?.let { applyFinger(it, now) }
                    return@forEachIndexed
                }
                seen += id
                // keyboard-local, y from the white key tops down to the desk (8 mm below): the press is at the desk
                val local = k.toLocal(tip).let { Vec3(it.x, it.y + PianoPlacement.KEY_TOP_M, it.z) }
                fingers.sample(id, local, now)?.let { applyFinger(it, now) }
            }
        }
        for (id in 0 until 10) if (id !in seen) fingers.lost(id)?.let { applyFinger(it, now) }
        // controller: trigger while the ray points at a key; or the tip touching a key
        for (c in controller.hands()) {
            val down = if (c.right) edges.downRight else edges.downLeft
            val up = if (c.right) edges.upRight else edges.upLeft
            if (down) {
                val t = c.ray.toLevel(k.origin.y, 2.5f)
                val r = t?.let { k.toLocal(c.ray.at(it)) }?.let { KeyAssist.resolve(it.x, it.z, l, assistTargets(), assist.snapWhites) }
                if (r != null) {
                    ctrlKey[c.right] = r.midi
                    keyDown(r.midi, 0.85f, if (c.right) "ctrl-right" else "ctrl-left", now, stray = r.stray, controllerRight = c.right)
                }
            }
            if (up || !controller.triggerHeld(c.right)) ctrlKey.remove(c.right)?.let { keyUp(it) }
            val tipLocal = k.toLocal(c.tip)
            val held = tipKey[c.right]
            if (held == null && tipLocal.y < 0.004f && tipLocal.y > -0.03f) {
                KeyAssist.resolve(tipLocal.x, tipLocal.z, l, assistTargets(), assist.snapWhites)?.let {
                    tipKey[c.right] = it.midi
                    keyDown(it.midi, 0.8f, "ctrl-tip", now, stray = it.stray, controllerRight = c.right)
                }
            } else if (held != null && tipLocal.y > 0.012f) {
                tipKey.remove(c.right)
                keyUp(held)
            }
        }
    }

    private fun applyFinger(c: FingerPress.Change, now: Long) {
        when (c) {
            is FingerPress.Change.On -> keyDown(c.midi, c.velocity, "finger${c.finger}${if (c.snapped) "-snap" else ""}", now, stray = c.stray)
            is FingerPress.Change.Off -> keyUp(c.midi)
        }
    }

    /** MIDI from the digital piano (binder thread -> main): the ViewModel hears it (S05 / S15), the run scores it. */
    private fun onMidi(note: Int, velocity: Int, on: Boolean, atMs: Long) {
        sink(PianoEvent.MidiNote(note, on))
        if (ui.virtual) return
        val m = note + ui.settings.midiOffset
        if (on) keyDown(m, velocity / 127f, "midi", atMs) else keyUp(m)
    }

    /**
     * A key went down (any source). The virtual piano sounds at once (a [stray] press, far from every note the song
     * wants, quieter); the run hears it. [controllerRight] = the controller that pressed it, for the hit buzz.
     */
    fun keyDown(midiNote: Int, velocity: Float, source: String, atMs: Long = SystemClock.elapsedRealtime(), stray: Boolean = false, controllerRight: Boolean? = null) {
        val assist = ui.settings.assist
        if (ui.virtual) {
            audio?.noteOn(midiNote, if (stray) velocity * assist.strayVolume else velocity, atMs)
            piano?.press(midiNote, true)
        }
        val p = perf
        val running = p != null && ui.designState in setOf("S10", "S11")
        val expected = (if (ui.virtual) assistTargets() else p?.pendingKeys)?.joinToString(",") ?: ""
        val e = if (running) p?.noteOn(midiNote, strayIsFree = ui.virtual && stray && !assist.strayIsWrong) else null
        PianoLog.i("PIANO t=${p?.t?.let { "%.3f".format(Locale.US, it) }} src=$source note=$midiNote expected=$expected stray=$stray hit=${(e as? PerfEvent.Hit)?.grade ?: e?.javaClass?.simpleName ?: "-"} latency_ms=${SystemClock.elapsedRealtime() - atMs}")
        if (e != null) perfEvent(e, SystemClock.elapsedRealtime())
        if (e is PerfEvent.Hit || (e is PerfEvent.Waiting && !e.on)) controllerRight?.let { buzz(it) }
    }

    /** The keys the song wants now (virtual piano, during a run): the snapping targets of [KeyAssist]. */
    private fun assistTargets(): Set<Int> {
        val p = perf ?: return emptySet()
        if (!ui.virtual || ui.designState !in setOf("S10", "S11")) return emptySet()
        return p.targets()
    }

    /** Set by the screen from `LocalHandControllerHaptic` (SpatialUI's public haptic instance). */
    var haptic: com.pico.spatial.ui.platform.SpatialHandControllerHaptic? = null

    /** A short buzz on the controller that hit the note (v0.1.2 「反馈要爽」). */
    private fun buzz(right: Boolean) {
        val h = haptic ?: return
        runCatching { h.feedback(com.pico.spatial.ui.platform.HandControllerHapticType.Press, if (right) com.pico.spatial.ui.platform.HandController.Right else com.pico.spatial.ui.platform.HandController.Left) }
            .onFailure { PianoLog.w("HAPTIC failed: $it") }
    }

    fun keyUp(midiNote: Int) {
        if (ui.virtual) {
            audio?.noteOff(midiNote)
            piano?.press(midiNote, false)
        }
    }

    // =========================================================================================
    // the run
    // =========================================================================================

    private fun stepRun(now: Long, dt: Float) {
        val p = perf ?: return
        val running = ui.designState in setOf("S10", "S11")
        if (running) {
            for (e in p.tick(dt)) perfEvent(e, now)
            val beat = if (p.t < 0f) Int.MIN_VALUE else (p.t / p.song.secPerBeat).toInt()
            if (beat != lastBeat) {
                if (lastBeat != Int.MIN_VALUE && ui.settings.metronome) audio?.tick(beat % p.song.beatsPerBar == 0)
                lastBeat = beat
            }
        }
        if ((running || ui.designState == "S12") && now - lastProgressMs >= PROGRESS_MS) {
            lastProgressMs = now
            sink(PianoEvent.Progress(p.progress, maxOf(0f, p.t)))
        }
    }

    private fun perfEvent(e: PerfEvent, now: Long) {
        val m = materials ?: return
        val p = perf ?: return
        when (e) {
            is PerfEvent.Landed -> {
                val n = e.note
                if (n.practice || ui.options.mode == PlayMode.LISTEN) {
                    val black = Keys.isBlack(n.midi)
                    val mat = if (n.hand == HandPart.RIGHT) (if (black) m.rightDeep else m.right) else (if (black) m.leftDeep else m.left)
                    val ms = ((n.end - n.start) / p.rate * 1000f).toLong().coerceAtLeast(MIN_LIT_MS)
                    lights.light(n.midi, mat, now + ms)
                }
            }
            is PerfEvent.AutoOn -> if (ui.virtual) {
                audio?.noteOn(e.midi, e.volume)
                piano?.press(e.midi, true)
            }
            is PerfEvent.AutoOff -> if (ui.virtual) {
                audio?.noteOff(e.midi)
                piano?.press(e.midi, false)
            }
            is PerfEvent.Hit -> {
                lights.flash(e.midi, m.flash, now + HIT_FLASH_MS)
                // the mini bar shows the combo and the grade
                sink(PianoEvent.Perf(e))
            }
            is PerfEvent.Wrong -> {
                lights.flash(e.midi, m.wrong, now + WRONG_FLASH_MS)
                sink(PianoEvent.Perf(e))
            }
            is PerfEvent.Missed -> {
                lights.missedUntil[e.note.midi] = now + MISSED_MS
                sink(PianoEvent.Perf(e))
            }
            is PerfEvent.Waiting -> {
                lights.waiting.fill(false)
                e.keys.forEach { if (it in 0..127) lights.waiting[it] = true }
                sink(PianoEvent.Perf(e))
            }
            is PerfEvent.OctaveHint, is PerfEvent.Finished -> sink(PianoEvent.Perf(e))
        }
        if (e is PerfEvent.Hit) {
            // in WAIT a hit key stops breathing
            if (e.midi in 0..127) lights.waiting[e.midi] = false
        }
    }

    // =========================================================================================
    // drawing
    // =========================================================================================

    private fun draw(now: Long) {
        val m = materials ?: return
        val v = views ?: return
        val kr = keyboardRoot ?: return
        val state = ui.designState
        val frame = shownFrame()
        val virtual = ui.virtual
        val hidden = ui.pianoHidden && virtual
        if (frame != null) kr.setLocal(position = frame.origin, rotation = frame.rotation) else kr.setLocal(position = Vec3.ZERO, rotation = Quat4.IDENTITY)
        v.setLayout(frame, virtual)
        val keysVisible = frame != null && !hidden
        // curtain, bars, countdown
        val curtainOn = keysVisible && state in CURTAIN_STATES
        val opacity = when {
            state == "S18" -> 0.2f
            state in PianoUiState.RUN_STATES -> 1f
            else -> 0.3f
        }
        v.drawCurtain(curtainOn, opacity)
        val p = perf
        if (curtainOn && p != null && state in BAR_STATES) {
            p.visible(visibleNotes)
            v.drawBars(visibleNotes, p.t, p.lookAheadSec, frozen = state == "S12" || state == "S18")
        } else v.drawBars(emptyList(), 0f, 1f, false)
        v.drawCountdown(if (state == "S09" && keysVisible) ui.countdown else 0)
        // key lights: middle C check, waiting, landed, flashes
        if (now < ui.middleCUntilMs) lights.light(Keys.MIDDLE_C, m.outline, ui.middleCUntilMs)
        val breath = 0.45f + 0.55f * (0.5f + 0.5f * sin((now % 1000L) / 1000f * 2f * Math.PI.toFloat()))
        v.drawLights(lights, now, breath, keysVisible && state !in setOf("S03", "S04"))
        v.showOutline(keysVisible && (state in setOf("S05", "S02") || (state == "S16" && now < ui.outlineUntilMs)))
        // end rings
        val ringsOn = frame != null && state in setOf("S05", "S02")
        v.handles.forEachIndexed { i, h -> if (ringsOn && frame != null) h.place(Vec3(if (i == 0) 0f else frame.length, 0f, 0f), if (state == "S02") breath else 1f) else h.show(false) }
        // the standard frames from the left end (S04)
        if (state == "S04") {
            val l = ui.leftEnd
            val tip = controller.aiming()?.tip ?: hands.sample().firstOrNull { it.right }?.index
            val dir = if (l != null && tip != null) Vec3(tip.x - l.x, 0f, tip.z - l.z).takeIf { it.length > 0.05f }?.normalized() else null
            val snapped = ui.liveLength?.let { if (LengthSnap.within(it, ui.count)) ui.count else null }
            v.drawFrames(l?.let { Vec3(it.x, it.y, it.z) }, dir, ui.count, snapped)
            desk?.drawMeasure(l, tip)
            desk?.drawTip(tip)
        } else {
            v.drawFrames(null, null, ui.count, null)
            desk?.drawMeasure(null, null)
            desk?.drawTip(if (state in setOf("S02", "S03")) controller.aiming()?.tip ?: hands.sample().firstOrNull { it.right }?.index else null)
        }
        // virtual piano (or the debug white model under a real keyboard)
        val pv = piano
        if (pv != null) {
            val showPiano = frame != null && !hidden && (virtual || debugWhite)
            pv.setLayout(if (showPiano) frame?.layout else null, debug = !virtual)
            pv.show(showPiano)
            if (showPiano && frame != null) {
                val local = frame.toLocal(pose.position)
                val inside = local.x in -0.05f..(frame.length + 0.05f) && local.z in -0.25f..0.05f && local.y < 0.30f
                pv.setOpacity(if (inside) 0.3f else 1f)
            }
        }
        // desk, ghost, ray
        val dv = desk ?: return
        val d = liveDesk ?: ui.desk
        dv.drawDesk(d, showBox = virtual && state !in setOf("S01", "S03", "S04", "S05"), showOutline = state in setOf("S22", "S23", "S24"), snapped = state == "S24" && (if (ui.pianoPlaced) ghostSnapped && livePiano != null else ghostSnapped))
        dv.drawGhost(if (state == "S24" && !ui.pianoPlaced) ghostFrame else null, ghostSnapped)
        if (state !in setOf("S23", "S24")) dv.drawRay(null, null)
    }

    // =========================================================================================
    // anchors: two ends, drift check (S18)
    // =========================================================================================

    private fun bindAnchors(ids: List<String>) {
        anchorKey = ids
        anchorEntities.values.forEach { runCatching { it.destroy(true) } }
        anchorEntities.clear()
        anchorStill = 0
        anchorPrev = null
        val root = stageRoot ?: return
        for (id in ids) {
            val e = Entity()
            runCatching { e.setName("anchor:${id.take(8)}") }
            val made = runCatching { e.components.set(AnchorComponent(AnchorTarget.createWorldAnchorTarget(UUID.fromString(id)))) }
            if (made.isFailure) {
                PianoLog.w("ANCHOR component failed: ${made.exceptionOrNull()}")
                continue
            }
            root.addChild(e)
            anchorEntities[id] = e
        }
        PianoLog.i("ANCHOR bound n=${anchorEntities.size}")
    }

    private fun anchorPos(id: String): Vec3? {
        val e = anchorEntities[id] ?: return null
        val root = stageRoot ?: return null
        return runCatching { e.convertPositionTo(Vector3.ZERO, root).domain() }.getOrNull()?.takeIf { it.isFinite() && it.length > 1e-4f }
    }

    /**
     * Design `container.notes`: UPDATED anchors move the keyboard (two points, no yaw stored); the two anchors
     * disagreeing with the stored length by more than 10 mm means at least one is wrong -> S18; back when they agree
     * within 5 mm for 1 s.
     */
    private fun watchAnchors(now: Long) {
        val k = ui.keyboard ?: return
        if (ui.aligning || ui.designState in setOf("S22", "S23", "S24")) return
        var gap = 0f
        if (anchorKey.size == 2) {
            val a = anchorPos(anchorKey[0])
            val b = anchorPos(anchorKey[1])
            if (a != null && b != null) {
                val prev = anchorPrev
                anchorPrev = a to b
                if (prev == null || prev.first.distanceTo(a) > 0.0005f || prev.second.distanceTo(b) > 0.0005f) {
                    anchorStill = 0
                } else if (anchorStill < ANCHOR_STILL_FRAMES) anchorStill++
                if (anchorStill >= ANCHOR_STILL_FRAMES) {
                    gap = abs(KeyboardFrame.span(a, b) - k.length)
                    val moved = a.distanceTo(k.origin) > 0.003f || b.distanceTo(k.right) > 0.003f
                    if (moved && gap < 0.005f && now - lastAnchorMoveMs > 1000L) {
                        lastAnchorMoveMs = now
                        KeyboardFrame.fromEnds(a, b, k.count)?.copy(length = k.length)?.let {
                            PianoLog.i(String.format(Locale.US, "ANCHOR follow moved_mm=%.1f", a.distanceTo(k.origin) * 1000f))
                            sink(PianoEvent.AnchorsMoved(it))
                        }
                    }
                }
            }
        }
        gap = maxOf(gap, debugAnchorJumpMm / 1000f)
        val lost = gap > LOST_GAP_M
        if (lost && !positionLost) {
            positionLost = true
            agreeSinceMs = -1L
            PianoLog.i(String.format(Locale.US, "ANCHOR disagree gap_mm=%.1f", gap * 1000f))
            sink(PianoEvent.PositionLost(true))
        } else if (positionLost) {
            if (gap < AGREE_GAP_M) {
                if (agreeSinceMs < 0) agreeSinceMs = now else if (now - agreeSinceMs >= AGREE_MS) {
                    positionLost = false
                    sink(PianoEvent.PositionLost(false))
                }
            } else agreeSinceMs = -1L
        }
    }

    // =========================================================================================
    // desk detection (S22 / S23)
    // =========================================================================================

    private fun findDesk() {
        if (ui.designState !in setOf("S22", "S23") || !hasHead || debugNoDesk) return
        // only a desk the user sits at: its edge within reach (1 m) of the head, horizontally
        val me = P2.of(pose.position)
        val plane = Desks.forPiano(planes.all, pose.position.y).firstOrNull { p ->
            val poly = p.vertices.map { P2.of(it) }
            // and in front of the user (within ±70° of the level gaze), never a cabinet behind the chair
            val ahead = (P2.of(p.centroid) - me).normalized().dot(pose.level()) > 0.34f
            ahead && (Geo2.inside(me, poly) || Geo2.distanceToEdge(me, poly) <= DESK_REACH_M)
        } ?: return
        if (plane.id == deskSent) return
        deskSent = plane.id
        val poly = plane.vertices.map { P2.of(it) }
        val rect = Geo2.minRect(poly) ?: return
        // the desk's "front" is the side that faces the user
        val toHead = P2.of(pose.position) - rect.center
        var yaw = rect.yaw
        var w = rect.hx * 2f
        var dpt = rect.hz * 2f
        val front = P2.dir(yaw)
        val side = P2(front.z, -front.x)
        if (abs(toHead.dot(side)) > abs(toHead.dot(front))) {
            yaw += (Math.PI / 2).toFloat()
            val tmp = w
            w = dpt
            dpt = tmp
        }
        if (toHead.dot(P2.dir(yaw)) < 0f) yaw += Math.PI.toFloat()
        sink(PianoEvent.DeskFound(Desk(rect.center.at(plane.centroid.y), yaw, w, dpt, virtual = false, polygon = poly)))
    }

    // =========================================================================================
    // panels
    // =========================================================================================

    /** Before the keyboard is known: 0.55 m ahead of the eyes, 5° below the level gaze (design `panel.beforeAlign`). */
    private val sidePanelFollow = LazyFollow("side", { h -> restAt(h, -PANEL_DOWN_DEG, PANEL_M) }, windowDeg = 40f, nearM = 0.3f, farM = 1.0f)

    private fun restAt(h: HeadPose, upDeg: Float, dist: Float): Vec3 {
        val f = h.level()
        val u = Math.toRadians(upDeg.toDouble()).toFloat()
        return h.position + f.at(0f) * (dist * cos(u)) + Vec3(0f, dist * sin(u), 0f)
    }

    /** Button "拉到眼前": the panel comes to the current head pose. */
    fun pullPanel() {
        sidePanelFollow.reset()
    }

    private fun placePanels(now: Long) {
        val rig = rig ?: return
        val side = rig[PanelIds.SIDE] ?: return
        val mini = rig[PanelIds.MINI] ?: return
        val k = shownFrame() ?: ui.keyboard
        if (ui.miniBar) {
            side.hide()
            if (k != null) {
                val x = (k.layout.key(Keys.MIDDLE_C)?.cx ?: (k.length / 2f)) - MINI_LEFT_M
                val top = Curtain.top
                val at = k.toWorld(Vec3(x.coerceIn(0.1f, k.length - 0.1f), top.y + MINI_ABOVE_M + PanelSizes.MINI_H * DP_M * PANEL_SCALE / 2f, Curtain.Z - PANEL_BEHIND_M))
                setFacing(mini, k, MINI_TILT_DEG)
                mini.show(at)
            } else {
                mini.fixed = null
                sidePanelFollow.step(pose, now, hold = false)?.let { mini.show(it) } ?: mini.show()
            }
            return
        }
        mini.hide()
        if (ui.panelOnKeys && k != null) {
            val cx = (k.layout.key(Keys.MIDDLE_C)?.cx ?: (k.length / 2f)) + ui.settings.panelSide.offsetM
            val y = PANEL_ON_KEYS_Y
            // behind the curtain (it fades to 30 % while the panel is up): 0.6 - 0.7 m from a seated player's eyes,
            // the distance the design's 14 dp minimum is read at; 3 cm in front of it was 0.3 m and filled the view
            val z = Curtain.Z - PANEL_BEHIND_M
            setFacing(side, k, PANEL_TILT_DEG)
            side.show(k.toWorld(Vec3(cx.coerceIn(0.15f, k.length - 0.15f), y, z)))
            return
        }
        side.fixed = null
        sidePanelFollow.step(pose, now, hold = false)?.let { side.show(it) } ?: side.show()
    }

    private fun setFacing(slot: PanelRig.Slot, k: KeyboardFrame, tiltDeg: Float) {
        val z = k.zAxis
        val f = PanelFacing(atan2(z.x, z.z), Math.toRadians(tiltDeg.toDouble()).toFloat())
        if (slot.fixed != f) slot.fixed = f
    }

    // =========================================================================================
    // system taps and drags (gaze + pinch, the emulator's mouse)
    // =========================================================================================

    fun onSpatialTap(value: SpatialTapValue) {
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        PianoLog.i("TAP target=$name stage=${PianoLog.pos(stage)} state=${ui.designState}")
        val key = piano?.keyOf(target)
        if (key != null && ui.virtual && ui.designState in PLAYABLE) {
            keyDown(key, 0.8f, "tap")
            main.postDelayed({ keyUp(key) }, TAP_HOLD_MS)
            return
        }
        if (ui.designState == "S24" && !ui.pianoPlaced && stage != null) {
            val d = ui.desk ?: return
            if (d.contains(P2.of(stage)) && abs(stage.y - d.top) < 0.08f) {
                val placed = ghostAt(Vec3(stage.x, d.top, stage.z), d)
                sink(PianoEvent.PianoPlaced(placed.frame, placed.snapped))
            } else PianoLog.i("TAP place refused: not on the desk")
        }
    }

    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        val k = ui.keyboard ?: return
        when (phase) {
            "start" -> {
                if (ui.designState != "S05") return
                val target = value.targetEntity ?: return
                val ring = views?.handles?.indexOfFirst { it.owns(target) } ?: -1
                if (ring < 0) return
                val press = start?.let { toStage(it.x, it.y, it.z) } ?: return
                val left = ring == 0
                grab = Grab(left, if (left) k.origin else k.right, press, false).also { g ->
                    val to = press - pose.position
                    g.dir = to.normalized()
                    val pitch = Math.toDegrees(asin(pose.forward.y.coerceIn(-1f, 1f).toDouble())).toFloat()
                    g.depth = DragLine.equivalentDepth(to.length, pitch)
                }
                PianoLog.i("DRAG end start left=$left by=system")
            }
            "move" -> {
                val g = grab?.takeIf { !it.byController } ?: return
                val a = value.dragAmount
                g.total = Vec3(g.total.x + a.x, g.total.y + a.y, g.total.z + a.z)
                val moved = toStage(g.total.x, g.total.y, g.total.z)
                val origin = toStage(0f, 0f, 0f)
                if (moved == null || origin == null) return
                g.hand = moved - origin
                val dir = g.dir ?: return
                val turned = DragLine.turn(dir, (g.hand - g.prevHand), g.depth, pose.right, pose.up, pose.forward)
                g.prevHand = g.hand
                g.dir = turned
                val hit = DragLine.onLevel(pose.position, turned, g.startEnd.y) ?: return
                liveEnd = g.left to (g.startEnd + Vec3(hit.x - g.startHit.x, 0f, hit.z - g.startHit.z) * DRAG_GAIN)
            }
            "end", "cancel" -> {
                val g = grab?.takeIf { !it.byController } ?: return
                grab = null
                if (phase == "end") liveEnd?.let { (left, at) -> sink(PianoEvent.EndMoved(left, at)) }
                liveEnd = null
                PianoLog.i("DRAG end $phase left=${g.left}")
            }
        }
    }

    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
            val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
            probe.convertPositionTo(local, null).domain()
        }.getOrNull()
    }

    // =========================================================================================
    // debug seams
    // =========================================================================================

    fun headPose(): HeadPose? = if (hasHead) pose else null

    fun debugStick(x: Float, ms: Long): String {
        controller.debugHold(x, ms)
        return String.format(Locale.US, "stick x=%.2f ms=%d state=%s", x, ms, ui.designState)
    }

    fun debugSeek(sec: Float) {
        perf?.seek(sec)
        lights.clear()
    }

    /** Debug `fingerPress` / `noteOn`: a key goes down (and up after [holdMs]). */
    fun debugPress(midiNote: Int, holdMs: Long, source: String) {
        keyDown(midiNote, 0.8f, source)
        main.postDelayed({ keyUp(midiNote) }, holdMs)
    }

    /**
     * Debug `fingerNear`: a fingertip strikes [offsetWhites] white widths beside key [midi] (keyboard-local), through the
     * same assist as a real finger. Logs what it resolved to.
     */
    fun debugFingerNear(midi: Int, offsetWhites: Float, holdMs: Long): String {
        val k = shownFrame() ?: return "no keyboard"
        val key = k.layout.key(midi) ?: return "no key $midi"
        val x = key.cx + offsetWhites * k.layout.whiteW
        val r = KeyAssist.resolve(x, -0.03f, k.layout, assistTargets(), ui.settings.assist.snapWhites) ?: return "outside"
        keyDown(r.midi, 0.8f, "debug-finger${if (r.snapped) "-snap" else ""}", stray = r.stray)
        main.postDelayed({ keyUp(r.midi) }, holdMs)
        return "finger x=${"%.4f".format(Locale.US, x)} -> ${r.midi} snapped=${r.snapped} stray=${r.stray} targets=${assistTargets()} assist=${ui.settings.assist.tag}"
    }

    /** Debug `ghost`: the ghost / a placement as if the controller ray met the desk at [at]. */
    fun debugGhost(at: Vec3, put: Boolean) {
        val d = ui.desk ?: return
        val placed = ghostAt(Vec3(at.x, d.top, at.z), d)
        ghostFrame = placed.frame
        ghostSnapped = placed.snapped
        ghostPinned = true
        sink(PianoEvent.GhostChanged(d.contains(P2.of(at)), placed.snapped))
        if (put) sink(PianoEvent.PianoPlaced(placed.frame, placed.snapped))
    }

    fun perfLine(): String = perf?.let { p -> String.format(Locale.US, "PERF song=%s t=%.2f waiting=%s finished=%s pending=%s rate=%.2f score=%s", p.song.id, p.t, p.waiting, p.finished, p.pendingKeys, p.rate, p.result()) } ?: "PERF none"

    fun poseLine(): String = String.format(Locale.US, "POSE hmd pos=%s %s fwd=%s valid=%s support=%s frames=%d", PianoLog.pos(pose.position), PianoLog.quat(pose.rotation), PianoLog.pos(pose.forward), hasHead, head.supportState(), frames)

    fun panelLines(): List<String> = rig?.dump() ?: emptyList()

    fun keyboardLine(): String = shownFrame()?.let { String.format(Locale.US, "KEYBOARD origin=%s x=%s len=%.4f keys=%d right=%s anchors=%s", PianoLog.pos(it.origin), PianoLog.pos(it.xAxis), it.length, it.count.keys, PianoLog.pos(it.right), anchorKey.map { id -> id.take(8) + "@" + PianoLog.pos(anchorPos(id)) }) } ?: "KEYBOARD none"

    companion object {
        const val PANEL_SCALE = 0.885f
        const val PANEL_M = 0.55f
        const val PANEL_DOWN_DEG = 5f
        const val PANEL_ON_KEYS_Y = 0.32f
        const val PANEL_BEHIND_M = 0.22f
        const val PANEL_TILT_DEG = 15f
        const val MINI_LEFT_M = 0.12f
        const val MINI_ABOVE_M = 0.04f
        const val MINI_TILT_DEG = 5f
        const val BUILD_START_MS = 1500L
        const val BUILD_STEP_MS = 150L
        const val LOG_MS = 5_000L
        const val PROGRESS_MS = 250L
        const val MIN_LIT_MS = 120L
        const val HIT_FLASH_MS = 150L
        const val WRONG_FLASH_MS = 300L
        const val MISSED_MS = 400L
        const val TAP_HOLD_MS = 250L
        const val PINCH_ON = 0.85f
        const val PINCH_OFF = 0.5f
        const val DWELL_M = 0.004f
        const val DWELL_MS = 1500L
        const val GRAB_RADIUS = 0.03f
        const val DRAG_GAIN = 0.5f
        const val STICK_RELEASE_MS = 150L
        const val MAX_TURN_REAL = 3f
        const val MAX_TURN_VIRTUAL = 45f
        const val ANCHOR_STILL_FRAMES = 5
        const val LOST_GAP_M = 0.010f
        const val DESK_REACH_M = 1.0f
        const val AGREE_GAP_M = 0.005f
        const val AGREE_MS = 1000L
        val PLAYABLE = setOf("S07", "S08", "S10", "S11", "S12", "S13", "S16", "S24")
        val CURTAIN_STATES = setOf("S07", "S08", "S09", "S10", "S11", "S12", "S13", "S16", "S18", "S21")
        /** Bars show only while the mini bar is up: with the side panel (behind the curtain) they would cross its buttons (D8). */
        val BAR_STATES = setOf("S09", "S10", "S11", "S21")

        /** Dead zone 0.15, square curve, [max] degrees per second at full tilt (sign: stick right +). */
        fun stickRate(x: Float, max: Float): Float {
            val a = abs(x)
            if (a < 0.15f) return 0f
            val k = ((a - 0.15f) / 0.85f).coerceIn(0f, 1f)
            return Math.signum(x) * k * k * max
        }
    }
}
