package io.embodit.pianofall.ui.piano.scene

import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Vector3
import io.embodit.pianofall.domain.HandPart
import io.embodit.pianofall.domain.KeyCount
import io.embodit.pianofall.domain.KeyRect
import io.embodit.pianofall.domain.KeyboardFrame
import io.embodit.pianofall.domain.KeyboardLayout
import io.embodit.pianofall.domain.Keys
import io.embodit.pianofall.domain.PlayNote
import io.embodit.pianofall.domain.Quat4
import io.embodit.pianofall.domain.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometry of the overlay in keyboard-local metres (design `spatialLayout`): the curtain stands on the back edge
 * of the white keys' visible part (z = -0.15), 0.40 m high, its top leaning 12° towards the player.
 */
internal object Curtain {
    const val Z = -0.15f
    const val H = 0.40f
    val TILT = Math.toRadians(12.0).toFloat()

    /** A point [h] up the curtain at key position [x], [out] in front of its face. */
    fun at(x: Float, h: Float, out: Float = 0f): Vec3 = Vec3(x, h * cos(TILT) - out * sin(TILT), Z + h * sin(TILT) + out * cos(TILT))

    val rotation: Quat4 = Quat4.axisAngle(Vec3.RIGHT, TILT)

    /** Top edge height and depth (for the mini bar). */
    val top: Vec3
        get() = at(0f, H)
}

/** Lights of the keys this frame: what each key shows (index = MIDI number). */
internal class KeyLights {
    val material = arrayOfNulls<Material>(128)
    val until = LongArray(128)
    val flash = arrayOfNulls<Material>(128)
    val flashUntil = LongArray(128)
    val waiting = BooleanArray(128)
    val missedUntil = LongArray(128)

    fun light(midi: Int, m: Material, untilMs: Long) {
        if (midi !in 0..127) return
        material[midi] = m
        until[midi] = untilMs
    }

    fun flash(midi: Int, m: Material, untilMs: Long) {
        if (midi !in 0..127) return
        flash[midi] = m
        flashUntil[midi] = untilMs
    }

    fun clear() {
        material.fill(null)
        until.fill(0L)
        flash.fill(null)
        flashUntil.fill(0L)
        waiting.fill(false)
        missedUntil.fill(0L)
    }
}

/**
 * Everything that hangs below the keyboard root (E2 - E7): curtain with its landing line, octave C lines and the
 * middle-C dot, the bar pool, key highlights, landing-line segments, key outlines, the two end rings, the five
 * standard-length frames and the countdown plate. Main thread only; [setLayout] when the keyboard changes.
 */
internal class KeyboardViews(private val root: Entity, private val meshes: PianoMeshes, private val m: PianoMaterials, private val digits: List<Material>, private val labels: Map<Int, Material>) {
    private val curtain = Box(root, meshes.cube, m.curtain, "curtain")
    private val landing = Box(root, meshes.cube, m.landing, "landing")
    private val cLines = List(9) { Box(root, meshes.cube, m.cLine, "cLine:$it") }
    private val middleC = Box(root, meshes.cube, m.landing, "middleC")
    private val bars = List(BAR_POOL) { Box(root, meshes.cube, m.right, "bar:$it") }
    private val highlights = List(88) { Box(root, meshes.cube, m.right, "hl:$it") }
    private val segments = List(88) { Box(root, meshes.cube, m.wait, "seg:$it") }
    private val countdown = Box(root, meshes.plate, digits.first(), "countdown")
    private val frames = List(5) { Strip(root, meshes.band, m.dashed, "stdFrame:$it") }
    private val frameLabels = List(5) { Box(root, meshes.plate, labels.values.first(), "stdLabel:$it") }
    val handles = List(2) { EndRing(root, meshes, m, if (it == 0) "handleL" else "handleR") }
    private var outline: Entity? = null
    private var outlineKey: Pair<KeyCount, Int>? = null

    var layout: KeyboardLayout? = null
        private set
    private var keyIndex = IntArray(128) { -1 }
    private var blackTop = 0.012f

    init {
        listOf(curtain, landing, middleC, countdown).forEach { it.show(false) }
        cLines.forEach { it.show(false) }
        bars.forEach { it.show(false) }
        highlights.forEach { it.show(false) }
        segments.forEach { it.show(false) }
        frameLabels.forEach { it.show(false) }
        handles.forEach { it.show(false) }
    }

    /** New keyboard geometry: positions the per-key entities (no entity is created). */
    fun setLayout(frame: KeyboardFrame?, virtual: Boolean) {
        blackTop = if (virtual) VIRTUAL_BLACK_TOP else REAL_BLACK_TOP
        val l = frame?.layout
        if (l == null) {
            layout = null
            return
        }
        if (layout?.count == l.count && layout?.length == l.length) return
        layout = l
        keyIndex = IntArray(128) { -1 }
        var i = 0
        for (k in l.all.sortedBy { it.midi }) {
            if (i >= 88) break
            keyIndex[k.midi] = i++
        }
        val len = l.length
        curtain.place(Curtain.at(len / 2f, Curtain.H / 2f, -0.002f), Vec3(len, Curtain.H, 0.001f), Curtain.rotation)
        landing.place(Curtain.at(len / 2f, 0.0015f, 0.001f), Vec3(len, 0.003f, 0.003f), Curtain.rotation)
        val cs = l.all.filter { Math.floorMod(it.midi, 12) == 0 }.sortedBy { it.midi }
        cLines.forEachIndexed { j, b -> cs.getOrNull(j)?.let { k -> b.place(Curtain.at(k.x0, Curtain.H / 2f, 0f), Vec3(0.0012f, Curtain.H, 0.0012f), Curtain.rotation) } ?: b.show(false) }
        l.key(Keys.MIDDLE_C)?.let { middleC.place(Curtain.at(it.cx, 0.012f, 0.002f), Vec3(0.007f, 0.007f, 0.002f), Curtain.rotation) } ?: middleC.show(false)
        for (k in l.all) {
            val idx = keyIndex[k.midi]
            if (idx < 0) continue
            segments[idx].place(Curtain.at(k.cx, 0.004f, 0.003f), Vec3(k.width - 0.002f, 0.008f, 0.003f), Curtain.rotation)
            segments[idx].show(false)
            placeHighlight(highlights[idx], k)
            highlights[idx].show(false)
        }
        rebuildOutline(l)
    }

    private fun placeHighlight(b: Box, k: KeyRect) {
        if (k.black) b.place(Vec3(k.cx, blackTop + 0.001f, -0.095f), Vec3(k.width - 0.002f, 0.002f, 0.080f))
        else b.place(Vec3(k.cx, 0.002f, -0.025f), Vec3(k.width - 0.003f, 0.002f, 0.050f))
    }

    /** E2: 1 mm lines on the gaps between the keys, one mesh per keyboard size. */
    private fun rebuildOutline(l: KeyboardLayout) {
        val key = l.count to (l.length * 1000f).toInt()
        if (key == outlineKey) return
        outlineKey = key
        runCatching { outline?.destroy(true) }
        val lines = ArrayList<List<Vec3>>()
        val d = KeyboardLayout.WHITE_DEPTH
        for (k in l.all.filter { !it.black }) lines += listOf(Vec3(k.x0, 0f, 0f), Vec3(k.x0, 0f, -d))
        lines += listOf(Vec3(l.length, 0f, 0f), Vec3(l.length, 0f, -d))
        lines += listOf(Vec3(0f, 0f, 0f), Vec3(l.length, 0f, 0f))
        lines += listOf(Vec3(0f, 0f, -d), Vec3(l.length, 0f, -d))
        for (k in l.all.filter { it.black }) lines += listOf(Vec3(k.x0, 0f, k.zFront), Vec3(k.x1, 0f, k.zFront), Vec3(k.x1, 0f, k.zBack), Vec3(k.x0, 0f, k.zBack), Vec3(k.x0, 0f, k.zFront))
        val mesh = MeshFactory.polylines("outline", lines, 0.001f) ?: return
        val e = ModelEntity(mesh, m.outline)
        runCatching { e.setName("outline") }
        root.addChild(e)
        e.setLocal(position = Vec3(0f, 0.0015f, 0f))
        outline = e
    }

    fun showOutline(on: Boolean) {
        outline?.let { if (it.enabled != on) it.enabled = on }
    }

    /** E3 / segments: lit keys, flashes, the breathing wait keys and grey missed segments. */
    fun drawLights(lights: KeyLights, nowMs: Long, breath: Float, showHighlights: Boolean) {
        val l = layout ?: return
        for (k in l.all) {
            val idx = keyIndex[k.midi]
            if (idx < 0) continue
            val hl = highlights[idx]
            val seg = segments[idx]
            val midi = k.midi
            val flash = lights.flash[midi]?.takeIf { nowMs < lights.flashUntil[midi] }
            val lit = lights.material[midi]?.takeIf { nowMs < lights.until[midi] }
            val waiting = lights.waiting[midi]
            when {
                !showHighlights -> hl.show(false)
                flash != null -> {
                    hl.material(flash)
                    hl.show(true)
                    hl.entity.setOpacity(1f)
                }
                waiting -> {
                    hl.material(m.wait)
                    hl.show(true)
                    hl.entity.setOpacity(breath)
                }
                lit != null -> {
                    hl.material(lit)
                    hl.show(true)
                    hl.entity.setOpacity(1f)
                }
                else -> hl.show(false)
            }
            when {
                !showHighlights -> seg.show(false)
                waiting -> {
                    seg.material(m.wait)
                    seg.show(true)
                    seg.entity.setOpacity(breath)
                }
                nowMs < lights.missedUntil[midi] -> {
                    seg.material(m.missed)
                    seg.show(true)
                    seg.entity.setOpacity(1f)
                }
                else -> seg.show(false)
            }
        }
    }

    /** E4: the curtain at [opacity] (30 % while a panel is up). */
    fun drawCurtain(visible: Boolean, opacity: Float) {
        curtain.show(visible)
        landing.show(visible)
        middleC.show(visible)
        cLines.forEach { if (!visible) it.show(false) }
        if (visible && layout != null) {
            val cs = layout!!.all.count { Math.floorMod(it.midi, 12) == 0 }
            cLines.forEachIndexed { j, b ->
                // the octave lines would cross the side panel standing behind the curtain: only while playing
                b.show(j < cs && opacity >= 1f)
                b.entity.setOpacity(opacity)
            }
            curtain.entity.setOpacity(opacity)
            landing.entity.setOpacity(opacity)
        }
    }

    /**
     * E5: the bars of the notes on the curtain. [t] = the song time at the landing line; a note [lookAhead] song
     * seconds ahead is at the top. Bars of the practising hand are teal / amber, the other hand grey.
     */
    fun drawBars(notes: List<PlayNote>, t: Float, lookAhead: Float, frozen: Boolean) {
        val l = layout
        var used = 0
        if (l != null) {
            for (n in notes) {
                if (used >= bars.size) break
                val k = l.key(n.midi) ?: continue
                val y0 = ((n.start - t) / lookAhead * Curtain.H).coerceIn(0f, Curtain.H)
                var y1 = ((n.end - t) / lookAhead * Curtain.H).coerceIn(0f, Curtain.H)
                if (y1 <= 0f || y0 >= Curtain.H) continue
                if (y1 - y0 < MIN_BAR) y1 = minOf(Curtain.H, y0 + MIN_BAR)
                val b = bars[used++]
                val mat =
                    when {
                        !n.practice -> m.other
                        n.hand == HandPart.RIGHT -> if (k.black) m.rightDeep else m.right
                        else -> if (k.black) m.leftDeep else m.left
                    }
                b.material(mat)
                val w = if (k.black) k.width - 0.002f else k.width - 0.002f
                b.place(Curtain.at(k.cx, (y0 + y1) / 2f, 0.004f), Vec3(w, y1 - y0, BAR_THICK), Curtain.rotation)
                b.entity.setOpacity(if (frozen) 0.5f else 1f)
            }
        }
        for (i in used until bars.size) bars[i].show(false)
    }

    /** The countdown digit (3, 2, 1) in the middle of the curtain, or none. */
    fun drawCountdown(n: Int) {
        val l = layout
        if (n !in 1..digits.size || l == null) {
            countdown.show(false)
            return
        }
        countdown.material(digits[n - 1])
        val x = l.key(Keys.MIDDLE_C)?.cx ?: (l.length / 2f)
        countdown.place(Curtain.at(x, Curtain.H * 0.55f, 0.006f), Vec3(0.12f, 0.12f, 1f), Curtain.rotation)
    }

    /** E7: dashed end frames of the five standard lengths along the front edge from the left end (S04). */
    fun drawFrames(origin: Vec3?, xAxis: Vec3?, chosen: KeyCount, snapped: KeyCount?) {
        if (origin == null || xAxis == null) {
            frames.forEach { it.hide() }
            frameLabels.forEach { it.show(false) }
            return
        }
        // in Stage space: the keyboard root is not placed yet in S04, so the frames go under it with an identity pose
        KeyCount.entries.sortedBy { it.stdLength }.forEachIndexed { i, c ->
            val p = origin + xAxis * c.stdLength
            val f = frames[i]
            f.material(if (c == snapped) m.snap else if (c == chosen) m.landing else m.dashed)
            f.draw(p, p + Vec3(0f, if (c == chosen) 0.03f else 0.02f, 0f), if (c == chosen) 0.003f else 0.0018f, xAxis.cross(Vec3.UP))
            val lb = frameLabels[i]
            labels[c.keys]?.let { lb.material(it) }
            val face = Quat4.fromBasis(xAxis, Vec3.UP, xAxis.cross(Vec3.UP).normalized())
            lb.place(p + Vec3(0f, 0.045f + (i % 2) * 0.022f, 0f), Vec3(0.036f, 0.018f, 1f), face)
        }
    }

    fun hideAll() {
        drawCurtain(false, 1f)
        bars.forEach { it.show(false) }
        highlights.forEach { it.show(false) }
        segments.forEach { it.show(false) }
        countdown.show(false)
        showOutline(false)
        frames.forEach { it.hide() }
        frameLabels.forEach { it.show(false) }
        handles.forEach { it.show(false) }
    }

    fun release() {
        runCatching { outline?.destroy(true) }
        outline = null
        outlineKey = null
    }

    companion object {
        const val BAR_POOL = 160
        const val MIN_BAR = 0.008f
        const val BAR_THICK = 0.006f
        const val REAL_BLACK_TOP = 0.012f
        const val VIRTUAL_BLACK_TOP = 0.006f
    }
}

/** E6: an end ring (18 mm bright ring + centre dot, 5 mm over the key corner) with a grab collider. */
internal class EndRing(parent: Entity, meshes: PianoMeshes, m: PianoMaterials, name: String) {
    val entity: Entity = Entity()
    private val ring = ModelEntity(meshes.ring, m.outline)
    private val dot = ModelEntity(meshes.disc, m.outline)
    private var on = true

    init {
        runCatching { entity.setName(name) }
        runCatching { ring.setName("$name:ring") }
        runCatching { dot.setName("$name:dot") }
        parent.addChild(entity)
        entity.addChild(ring)
        entity.addChild(dot)
        ring.setLocal(scale = Vec3(0.018f, 1f, 0.018f))
        dot.setLocal(scale = Vec3(0.004f, 1f, 0.004f))
        entity.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(0.04f, 0.03f, 0.04f))), physicsMaterial = PhysicsMaterialResource()))
        entity.components.set(InteractableComponent())
    }

    fun owns(e: Entity): Boolean = e.id == entity.id || e.id == ring.id || e.id == dot.id

    fun place(local: Vec3, pulse: Float) {
        entity.setLocal(position = local + Vec3(0f, 0.005f, 0f))
        entity.setOpacity(pulse)
        show(true)
    }

    fun show(visible: Boolean) {
        if (visible != on) {
            entity.enabled = visible
            on = visible
        }
    }
}

/**
 * The free side panel's grab bar (D21): a pale bar under the panel's bottom edge, with a generous collider.
 * Dragged by the system gesture (gaze + pinch, controller ray + trigger); placed in Stage metres.
 */
internal class PanelHandle(parent: Entity, meshes: PianoMeshes, m: PianoMaterials) {
    val entity: Entity = Entity()
    private val bar = ModelEntity(meshes.cube, m.dashed)
    private var on = true

    init {
        runCatching { entity.setName("panelHandle") }
        runCatching { bar.setName("panelHandle:bar") }
        parent.addChild(entity)
        entity.addChild(bar)
        bar.setLocal(scale = Vec3(WIDTH_M, HEIGHT_M, 0.006f))
        entity.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(WIDTH_M + 0.06f, HEIGHT_M + 0.04f, 0.04f))), physicsMaterial = PhysicsMaterialResource()))
        entity.components.set(InteractableComponent())
        show(false)
    }

    fun owns(e: Entity): Boolean = e.id == entity.id || e.id == bar.id

    fun place(at: Vec3, rotation: Quat4) {
        entity.setLocal(position = at, rotation = rotation)
        show(true)
    }

    fun show(visible: Boolean) {
        if (visible != on) {
            entity.enabled = visible
            on = visible
        }
    }

    companion object {
        const val WIDTH_M = 0.14f
        const val HEIGHT_M = 0.012f
        /** Gap between the panel's bottom edge and the bar's centre. */
        const val BELOW_M = 0.028f
    }
}

/**
 * The 1:1 virtual piano (design `virtualPiano`): flat lacquer body, keys only 8 mm above the desk (black keys
 * 6 mm higher), every key a tap target. The same builder makes the debug white-model piano (E8) under a real
 * keyboard on the emulator. Keyboard-local: y = 0 is the white key tops.
 */
internal class VirtualPianoView(parent: Entity, private val meshes: PianoMeshes, private val m: PianoMaterials) {
    val root: Entity = Entity()
    private val keys = List(88) { Box(root, meshes.cube, m.ivory, "vkey:$it") }
    private val body = List(6) { Box(root, meshes.cube, m.lacquer, "vbody:$it") }
    private var midiOf = IntArray(88) { -1 }
    private val keyIds = HashMap<Long, Int>()
    private var layout: KeyboardLayout? = null
    private var debugLook = false
    private val down = BooleanArray(128)
    private var on = true

    init {
        runCatching { root.setName("virtualPiano") }
        parent.addChild(root)
        for (k in keys) {
            k.entity.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(1f, 1f, 1f))), physicsMaterial = PhysicsMaterialResource()))
            k.entity.components.set(InteractableComponent())
            keyIds[k.entity.id] = -1
        }
        show(false)
    }

    /** The key a tapped entity belongs to, or null. */
    fun keyOf(e: Entity?): Int? {
        e ?: return null
        val i = keys.indexOfFirst { it.entity.id == e.id }
        return if (i >= 0) midiOf[i].takeIf { it >= 0 } else null
    }

    fun setLayout(l: KeyboardLayout?, debug: Boolean) {
        if (l == null) {
            show(false)
            layout = null
            return
        }
        if (layout === l && debugLook == debug) return
        layout = l
        debugLook = debug
        midiOf = IntArray(88) { -1 }
        val sorted = l.all.sortedBy { it.midi }
        keys.forEachIndexed { i, b ->
            val k = sorted.getOrNull(i)
            if (k == null) {
                b.show(false)
                return@forEachIndexed
            }
            midiOf[i] = k.midi
            b.material(keyMaterial(k.black, false))
            placeKey(b, k, false)
        }
        val len = l.length
        val back = -KeyboardLayout.WHITE_DEPTH
        val bodyMat = if (debug) m.debugBlack else m.lacquer
        body.forEach { it.material(bodyMat) }
        if (debug) {
            // the white model only stands in for a real keyboard: no body around it
            body.forEach { it.show(false) }
        } else {
            body[0].place(Vec3(len / 2f, -0.004f, 0.005f), Vec3(len + 0.06f, 0.008f, 0.01f)) // front strip 1 cm
            body[1].place(Vec3(-0.015f, 0.002f, (back - 0.02f + 0.01f) / 2f), Vec3(0.03f, 0.02f, -back + 0.03f)) // left cheek
            body[2].place(Vec3(len + 0.015f, 0.002f, (back - 0.02f + 0.01f) / 2f), Vec3(0.03f, 0.02f, -back + 0.03f)) // right cheek
            body[3].place(Vec3(len / 2f, 0.017f, back - 0.01f), Vec3(len + 0.06f, 0.05f, 0.02f)) // back board 5 cm
            body[4].place(Vec3(len / 2f, 0.042f, back - 0.0005f), Vec3(len + 0.06f, 0.0012f, 0.0012f)) // the board's lit edge
            body[4].material(m.lacquerEdge)
            // the dark key bed: the gaps between the white keys read against it, not against the desk
            body[5].place(Vec3(len / 2f, -0.0082f, back / 2f), Vec3(len, 0.0012f, -back))
        }
    }

    private fun keyMaterial(black: Boolean, pressed: Boolean): Material =
        if (debugLook) (if (black) m.debugBlack else m.debugWhite)
        else if (black) (if (pressed) m.ebonyDown else m.ebony) else (if (pressed) m.ivoryDown else m.ivory)

    private fun placeKey(b: Box, k: KeyRect, pressed: Boolean) {
        val sink = if (pressed) PRESS_SINK else 0f
        if (k.black) b.place(Vec3(k.cx, 0.003f - sink, (k.zFront + k.zBack) / 2f), Vec3(k.width, 0.012f, k.zFront - k.zBack))
        else b.place(Vec3(k.cx, -0.004f - sink, -KeyboardLayout.WHITE_DEPTH / 2f), Vec3(k.width - 0.0026f, 0.0075f, KeyboardLayout.WHITE_DEPTH))
    }

    /** Pressed keys sink to the desk and darken 15 %. */
    fun press(midi: Int, isDown: Boolean) {
        if (midi !in 0..127 || down[midi] == isDown) return
        down[midi] = isDown
        val l = layout ?: return
        val k = l.key(midi) ?: return
        val i = midiOf.indexOf(midi)
        if (i < 0) return
        keys[i].material(keyMaterial(k.black, isDown))
        placeKey(keys[i], k, isDown)
    }

    fun releaseAll() {
        for (mi in 0..127) if (down[mi]) press(mi, false)
    }

    fun show(visible: Boolean) {
        if (visible != on) {
            root.enabled = visible
            on = visible
        }
    }

    fun setOpacity(o: Float) = root.setOpacity(o)

    companion object {
        const val PRESS_SINK = 0.005f
    }
}
