package io.embodit.pianofall.ui.piano.scene

import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.math.Vector3
import io.embodit.pianofall.domain.Desk
import io.embodit.pianofall.domain.KeyboardFrame
import io.embodit.pianofall.domain.Quat4
import io.embodit.pianofall.domain.Vec3

/**
 * Stage-space helpers of the setup flow: the virtual desk (S23), the desk's dashed outline that turns green when
 * the piano snaps to an edge (S24), the ghost piano that follows the controller ray (T22), the ray itself, the
 * tool-tip dot (S02 - S04) and the measuring line from the left end (S04).
 */
internal class DeskViews(private val root: Entity, private val meshes: PianoMeshes, private val m: PianoMaterials) {
    val deskBox = Box(root, meshes.cube, m.desk, "virtualDesk")
    private var outline: Entity? = null
    private var outlineFor: Desk? = null
    private var outlineSnapped = false
    private val ghost = Box(root, meshes.cube, m.ghost, "ghostPiano")
    private val ghostEdge = Strip(root, meshes.band, m.dashed, "ghostEdge")
    private val ray = Strip(root, meshes.band, m.ray, "ray")
    private val tip = Box(root, meshes.cube, m.tip, "tip")
    private val measure = Strip(root, meshes.band, m.landing, "measure")
    private val leftMark = Box(root, meshes.cube, m.tip, "leftMark")

    init {
        deskBox.entity.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(1f, 1f, 1f))), physicsMaterial = PhysicsMaterialResource()))
        deskBox.entity.components.set(InteractableComponent())
        listOf(deskBox, ghost, tip, leftMark).forEach { it.show(false) }
    }

    fun isDesk(e: Entity?): Boolean = e != null && e.id == deskBox.entity.id

    /** The virtual desk box (its top at [Desk.top]) and the dashed outline of whichever desk is used. */
    fun drawDesk(desk: Desk?, showBox: Boolean, showOutline: Boolean, snapped: Boolean) {
        if (desk != null && desk.virtual && showBox) {
            deskBox.place(desk.center - Vec3(0f, Desk.THICK / 2f, 0f), Vec3(desk.width, Desk.THICK, desk.depth), Quat4.axisAngle(Vec3.UP, desk.yaw))
        } else deskBox.show(false)
        if (desk == null || !showOutline) {
            outline?.let { if (it.enabled) it.enabled = false }
            return
        }
        if (desk != outlineFor || snapped != outlineSnapped) {
            outlineFor = desk
            outlineSnapped = snapped
            runCatching { outline?.destroy(true) }
            val pts = desk.outline.map { it.at(desk.top + 0.0015f) }
            val mesh = MeshFactory.polylines("deskEdge", listOf(pts + pts.first()), 0.003f, dash = 0.02f) ?: return
            val e = ModelEntity(mesh, if (snapped) m.snap else m.dashed)
            runCatching { e.setName("deskEdge") }
            root.addChild(e)
            outline = e
        }
        outline?.let { if (!it.enabled) it.enabled = true }
    }

    /** The see-through footprint of the piano before it is put down. */
    fun drawGhost(frame: KeyboardFrame?, snapped: Boolean) {
        if (frame == null) {
            ghost.show(false)
            ghostEdge.hide()
            return
        }
        val depth = 0.22f
        val c = frame.toWorld(Vec3(frame.length / 2f, 0.004f, -depth / 2f + 0.01f))
        ghost.place(c, Vec3(frame.length + 0.06f, 0.02f, depth), frame.rotation)
        ghostEdge.material(if (snapped) m.snap else m.dashed)
        ghostEdge.draw(frame.toWorld(Vec3(-0.03f, 0.0f, 0.01f)), frame.toWorld(Vec3(frame.length + 0.03f, 0.0f, 0.01f)), if (snapped) 0.004f else 0.002f, Vec3.UP)
    }

    fun drawRay(from: Vec3?, to: Vec3?) {
        if (from == null || to == null) ray.hide() else ray.draw(from, to, 0.0025f, Vec3.UP)
    }

    fun drawTip(at: Vec3?) {
        if (at == null) tip.show(false) else tip.place(at, Vec3(0.008f, 0.008f, 0.008f))
    }

    fun drawMeasure(left: Vec3?, to: Vec3?) {
        if (left == null) leftMark.show(false) else leftMark.place(left + Vec3(0f, 0.004f, 0f), Vec3(0.01f, 0.008f, 0.01f))
        if (left == null || to == null) measure.hide() else measure.draw(left + Vec3(0f, 0.003f, 0f), Vec3(to.x, left.y + 0.003f, to.z), 0.0015f, Vec3.UP)
    }

    fun release() {
        runCatching { outline?.destroy(true) }
        outline = null
        outlineFor = null
    }
}
