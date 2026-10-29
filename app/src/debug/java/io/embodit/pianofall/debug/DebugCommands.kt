package io.embodit.pianofall.debug

import android.content.Context
import io.embodit.pianofall.domain.Desk
import io.embodit.pianofall.domain.Grade
import io.embodit.pianofall.domain.HandSel
import io.embodit.pianofall.domain.KeyCount
import io.embodit.pianofall.domain.KeyboardFrame
import io.embodit.pianofall.domain.Library
import io.embodit.pianofall.domain.P2
import io.embodit.pianofall.domain.PianoMode
import io.embodit.pianofall.domain.PianoPlacement
import io.embodit.pianofall.domain.PlaneSurface
import io.embodit.pianofall.domain.PlayMode
import io.embodit.pianofall.domain.Score
import io.embodit.pianofall.domain.Vec3
import io.embodit.pianofall.platform.PianoGraph
import io.embodit.pianofall.platform.PianoLog
import io.embodit.pianofall.ui.piano.Overlay
import io.embodit.pianofall.ui.piano.PianoEvent
import io.embodit.pianofall.ui.piano.PianoViewModel
import io.embodit.pianofall.ui.piano.debug.Probe
import io.embodit.pianofall.ui.piano.scene.PianoScene
import java.util.Locale

/**
 * Test hooks of the debug build (design `verification.simulator`). One log line (tag `PianoFall`) answers every
 * command. Broadcast:
 * `am broadcast -a io.embodit.pianofall.DEBUG -p io.embodit.pianofall --es cmd NAME [--es s TEXT] [--es a NUMBERS]`.
 *
 * Keyboard and desk
 * - `seedKeyboard` a=len,keys,yawDeg: the white-model piano (E8) ahead of the head, aligned (anchors made) - real mode
 * - `virtual` a=keys: a virtual desk and a placed virtual piano ahead (virtual mode)
 * - `desk` a=aheadM,belowM: a TABLE plane ahead (S22 finds it) · `nodesk` s=on|off · `planeNone` (= nodesk on)
 * - `take` a=x,y,z (Stage) or `takeEnd` s=left|right [a=len]: a corner as if touched (S02 / S03 / S04)
 * - `length` a=metres: S04 right end at that length from the left end along the head's right
 * - `rotate` a=deg (turns the keyboard, as the stick) · `stick` a=x,ms · `ghost` a=dx,dz[,put] (S24, metres from the desk centre)
 * - `anchorRemoved` · `anchorJump` a=mm · `anchorBack` · `anchorFail` (next start finds nothing: S17)
 *
 * The run
 * - `state` s=S00 .. S24 (S14 / S15 open the overlays, S20 = S08 without MIDI)
 * - `song` s=id a=hand(0 L,1 R,2 both),mode(0 listen,1 follow,2 wait),speed% -> S08
 * - `noteOn` a=midi[,holdMs] (MIDI / a key) · `fingerPress` a=midi · `seek` a=sec · `handsReady`
 * - `inputDevice` s=name|none · `finish` a=perfect,early,missed,wrong (S13 with that score) · `mute`
 * - `ui` s=<control name>: presses a panel control (the names of `Modifier.probe`)
 *
 * Reading back: `dump` · `pose` · `panels` · `perf` · `keyboard`
 */
internal object DebugCommands {
    private const val NO_HOST = "CMD ignored: no Stage"

    fun run(context: Context, cmd: String, s: String?, a: String?) {
        val host = Probe.host
        if (host == null) {
            PianoLog.w(NO_HOST)
            return
        }
        val vm = host.viewModel()
        val scene = host.scene()
        val nums = a?.split(',')?.mapNotNull { it.trim().toFloatOrNull() } ?: emptyList()
        when (cmd) {
            "state" -> vm.debugJump(s ?: "S01")
            "seedKeyboard" -> seedKeyboard(vm, scene, nums)
            "virtual" -> virtual(vm, scene, nums)
            "desk" -> {
                val h = scene.headPose() ?: return no("no head")
                val ahead = nums.getOrNull(0) ?: 0.55f
                val below = nums.getOrNull(1) ?: 0.45f
                val f = h.level()
                val r = P2(-f.z, f.x)
                val c = P2.of(h.position) + f * ahead
                val y = h.position.y - below
                val pts = listOf(c - r * 0.6f - f * 0.3f, c + r * 0.6f - f * 0.3f, c + r * 0.6f + f * 0.3f, c - r * 0.6f + f * 0.3f).map { it.at(y) }
                PianoGraph.planes.debugPut(PlaneSurface("debug-desk-0000-0000", "TABLE", pts))
                PianoLog.i("CMD desk y=$y ahead=$ahead")
            }
            "nodesk", "planeNone" -> scene.debugNoDesk = s != "off"
            "take" -> vm.onEvent(PianoEvent.PointTaken(Vec3(nums[0], nums[1], nums[2]), "debug"))
            "takeEnd" -> takeEnd(vm, scene, s ?: "left", nums.getOrNull(0))
            "length" -> {
                val l = vm.current().leftEnd ?: return no("no left end")
                val h = scene.headPose() ?: return no("no head")
                val f = h.level()
                val right = Vec3(-f.z, 0f, f.x)
                vm.onEvent(PianoEvent.PointTaken(l + right * (nums.getOrNull(0) ?: 0.81f), "debug-length"))
            }
            "rotate" -> vm.onEvent(PianoEvent.KeyboardTurned(Math.toRadians((nums.getOrNull(0) ?: 2f).toDouble()).toFloat()))
            "stick" -> PianoLog.i("CMD ${scene.debugStick(nums.getOrNull(0) ?: 0f, (nums.getOrNull(1) ?: 1000f).toLong())}")
            "ghost" -> {
                val d = vm.current().desk ?: return no("no desk")
                val f = d.front
                val at = d.center + Vec3(d.right.x, 0f, d.right.z) * (nums.getOrNull(0) ?: 0f) + Vec3(f.x, 0f, f.z) * (nums.getOrNull(1) ?: 0.1f)
                scene.debugGhost(at, (nums.getOrNull(2) ?: 0f) > 0f)
            }
            "anchorRemoved" -> scene.debugAnchorJumpMm = 100f
            "anchorJump" -> scene.debugAnchorJumpMm = nums.getOrNull(0) ?: 15f
            "anchorBack" -> scene.debugAnchorJumpMm = 0f
            "anchorFail" -> PianoGraph.anchors.hideStored = s != "off"
            "song" -> {
                val id = s ?: "twinkle"
                if (Library.song(id) == null) return no("no song $id")
                vm.onEvent(PianoEvent.SongPicked(id))
                nums.getOrNull(0)?.let { vm.onEvent(PianoEvent.HandsPicked(HandSel.entries[listOf(1, 0, 2)[it.toInt().coerceIn(0, 2)]])) }
                nums.getOrNull(1)?.let { vm.onEvent(PianoEvent.ModePicked(PlayMode.entries[it.toInt().coerceIn(0, 2)])) }
                nums.getOrNull(2)?.let { vm.onEvent(PianoEvent.SpeedPicked(it / 100f)) }
            }
            "noteOn" -> {
                val m = (nums.getOrNull(0) ?: 60f).toInt()
                val hold = (nums.getOrNull(1) ?: 200f).toLong()
                if (vm.current().virtual) scene.debugPress(m, hold, "debug") else {
                    vm.onEvent(PianoEvent.MidiNote(m - vm.current().settings.midiOffset, true))
                    scene.debugPress(m, hold, "debug-midi")
                }
            }
            "fingerPress" -> scene.debugPress((nums.getOrNull(0) ?: 60f).toInt(), (nums.getOrNull(1) ?: 200f).toLong(), "debug-finger")
            "fingerNear" -> PianoLog.i("CMD " + scene.debugFingerNear((nums.getOrNull(0) ?: 60f).toInt(), nums.getOrNull(1) ?: 0f, (nums.getOrNull(2) ?: 200f).toLong()))
            "assist" -> vm.onEvent(PianoEvent.SettingsChanged(vm.current().settings.copy(assist = io.embodit.pianofall.domain.AssistLevel.of(s))))
            "seek" -> scene.debugSeek(nums.getOrNull(0) ?: 0f)
            "handsReady" -> vm.onEvent(PianoEvent.HandsSeen(true, true))
            "inputDevice" -> vm.debugMidi(if (s == null || s == "none") null else s)
            "finish" -> {
                val p = nums.getOrNull(0)?.toInt() ?: 20
                val e = nums.getOrNull(1)?.toInt() ?: 3
                val mi = nums.getOrNull(2)?.toInt() ?: 2
                val w = nums.getOrNull(3)?.toInt() ?: 1
                vm.debugFinish(if (s == "none") null else Score(p + e + mi, p, e, mi, w))
            }
            "mute" -> vm.onEvent(PianoEvent.MuteClicked)
            "ui" -> ui(vm, s ?: "")
            "dump" -> dump(vm, scene)
            "pose" -> PianoLog.i(scene.poseLine())
            "panels" -> scene.panelLines().forEach { PianoLog.i(it) }
            "perf" -> PianoLog.i(scene.perfLine())
            "keyboard" -> PianoLog.i(scene.keyboardLine())
            else -> PianoLog.w("CMD unknown '$cmd'")
        }
    }

    private fun no(why: String) {
        PianoLog.w("CMD failed: $why")
    }

    /** A keyboard of [len] m (default the standard length) and [keys] keys 0.45 m ahead, 0.40 m below the eyes. */
    private fun seedKeyboard(vm: PianoViewModel, scene: PianoScene, nums: List<Float>) {
        val h = scene.headPose() ?: return no("no head")
        val count = KeyCount.of((nums.getOrNull(1) ?: 88f).toInt()) ?: KeyCount.K88
        val len = nums.getOrNull(0)?.takeIf { it > 0.2f } ?: count.stdLength
        val f = h.level()
        val yaw = Math.toRadians((nums.getOrNull(2) ?: 0f).toDouble()).toFloat()
        val x = Vec3(-f.z, 0f, f.x)
        val c = P2.of(h.position) + f * 0.45f
        val origin = c.at(h.position.y - 0.40f) - x * (len / 2f)
        val frame = KeyboardFrame(origin, x, len, count).turned(yaw)
        scene.debugWhite = true
        vm.debugKeyboard(frame, PianoMode.REAL)
        PianoLog.i(String.format(Locale.US, "CMD seedKeyboard keys=%d len=%.3f origin=%s", count.keys, len, PianoLog.pos(frame.origin)))
    }

    private fun virtual(vm: PianoViewModel, scene: PianoScene, nums: List<Float>) {
        val h = scene.headPose() ?: return no("no head")
        val count = KeyCount.of((nums.getOrNull(0) ?: 61f).toInt()) ?: KeyCount.K61
        val desk = Desk.virtualAhead(h)
        val f = h.level()
        val front = P2.of(desk.center) - f * (desk.depth / 2f - 0.03f)
        val placed = PianoPlacement.place(front.at(desk.top), f * -1f, count, desk)
        scene.debugWhite = false
        vm.debugDesk(desk)
        vm.debugKeyboard(placed.frame, PianoMode.VIRTUAL)
        PianoLog.i("CMD virtual keys=${count.keys} snapped=${placed.snapped} origin=${PianoLog.pos(placed.frame.origin)}")
    }

    private fun takeEnd(vm: PianoViewModel, scene: PianoScene, which: String, len: Float?) {
        val h = scene.headPose() ?: return no("no head")
        val f = h.level()
        val x = Vec3(-f.z, 0f, f.x)
        val st = vm.current()
        val length = len ?: st.count.stdLength
        val c = P2.of(h.position) + f * 0.45f
        val left = c.at(h.position.y - 0.40f) - x * (length / 2f)
        val at = if (which == "left") left else (st.leftEnd ?: left) + x * length
        vm.onEvent(PianoEvent.PointTaken(at, "debug-$which"))
    }

    /** `ui s=<name>`: the panel controls by their probe names. */
    private fun ui(vm: PianoViewModel, name: String) {
        val st = vm.current()
        val parts = name.split(':')
        val e: PianoEvent? =
            when (parts[0]) {
                "havePiano" -> PianoEvent.HavePianoClicked
                "noPiano" -> PianoEvent.NoPianoClicked
                "count" -> KeyCount.of(parts.getOrNull(1)?.removePrefix("K")?.toIntOrNull() ?: 88)?.let { PianoEvent.CountPicked(it) }
                "startAlign" -> PianoEvent.StartAlignClicked
                "back" -> PianoEvent.BackClicked
                "pull" -> PianoEvent.PullToEyesClicked
                "checkC" -> PianoEvent.MiddleCClicked
                "alignDone", "repinDone" -> PianoEvent.AlignDoneClicked
                "repinCancel" -> PianoEvent.RepinCancelClicked
                "remeasure" -> PianoEvent.RemeasureClicked
                "pickA" -> st.mismatch?.second?.let { PianoEvent.CountPicked(it) }
                "pickB" -> st.mismatch?.third?.let { PianoEvent.CountPicked(it) }
                "keyboardOk" -> PianoEvent.KeyboardOkClicked
                "realign" -> PianoEvent.RealignClicked
                "repin" -> PianoEvent.RepinClicked
                "level" -> PianoEvent.LevelPicked(parts.getOrNull(1)?.toIntOrNull() ?: 1)
                "song" -> PianoEvent.SongPicked(parts.getOrNull(1) ?: "twinkle")
                "start" -> PianoEvent.StartClicked
                "directStart" -> PianoEvent.DirectStartClicked
                "pause" -> PianoEvent.PauseClicked
                "continue" -> PianoEvent.ContinueClicked
                "restart" -> PianoEvent.RestartClicked
                "backBar" -> PianoEvent.BackBarClicked
                "library" -> PianoEvent.LibraryClicked
                "again" -> PianoEvent.AgainClicked
                "faster" -> PianoEvent.FasterClicked
                "tryFollow" -> PianoEvent.TryFollowClicked
                "fixOctave" -> PianoEvent.FixOctaveClicked
                "toVirtual" -> PianoEvent.ToVirtualClicked
                "goConnect", "changeInput", "inputSource" -> PianoEvent.OverlayClicked(Overlay.INPUT)
                "settings", "closeSettings" -> PianoEvent.OverlayClicked(Overlay.SETTINGS)
                "pianoMenu", "closeMenu" -> PianoEvent.OverlayClicked(Overlay.PIANO_MENU)
                "mute", "miniMute" -> PianoEvent.MuteClicked
                "useVirtualDesk" -> PianoEvent.UseVirtualDeskClicked
                "deskUp" -> PianoEvent.DeskHeightClicked(1)
                "deskDown" -> PianoEvent.DeskHeightClicked(-1)
                "deskUp5" -> PianoEvent.DeskHeightClicked(5)
                "deskDown5" -> PianoEvent.DeskHeightClicked(-5)
                "deskDone" -> PianoEvent.DeskDoneClicked
                "placeAgain" -> PianoEvent.PlaceAgainClicked
                "pianoDone" -> PianoEvent.PianoDoneClicked
                "moveHere" -> PianoEvent.MoveHereClicked
                "hidePiano" -> PianoEvent.HidePianoClicked
                "vcount" -> KeyCount.of(parts.getOrNull(1)?.removePrefix("K")?.toIntOrNull() ?: 61)?.let { PianoEvent.VirtualCountPicked(it) }
                "hands" -> HandSel.entries.firstOrNull { it.name == parts.getOrNull(1) }?.let { PianoEvent.HandsPicked(it) }
                "mode" -> PlayMode.entries.firstOrNull { it.name == parts.getOrNull(1) }?.let { PianoEvent.ModePicked(it) }
                "speed" -> parts.getOrNull(1)?.toFloatOrNull()?.let { PianoEvent.SpeedPicked(it) }
                "grant" -> PianoEvent.GrantClicked
                "without" -> PianoEvent.UseWithoutClicked
                "lang" -> {
                    val l = io.embodit.pianofall.domain.LanguagePref.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: io.embodit.pianofall.domain.LanguagePref.EN
                    PianoEvent.SettingsChanged(st.settings.copy(language = l))
                }
                else -> null
            }
        if (e == null) no("ui '$name' unknown") else vm.onEvent(e)
    }

    private fun dump(vm: PianoViewModel, scene: PianoScene) {
        val s = vm.current()
        val shown = when (s.overlay) {
            Overlay.SETTINGS -> "S14"
            Overlay.INPUT -> "S15"
            else -> if (s.designState == "S08" && s.waitUnavailable) "S08/S20" else s.designState
        }
        PianoLog.i("DUMP state=$shown mode=${s.mode.tag} keys=${s.keyboard?.count?.keys} song=${s.songId} opts=${s.options} midi=${s.midiName} muted=${s.settings.muted} lang=${s.settings.language} overlay=${s.overlay} placed=${s.pianoPlaced} desk=${s.desk?.let { PianoLog.pos(it.center) + " v=" + it.virtual }} score=${s.score} progress=${s.progress} anchors=${s.anchorIds.size}")
        PianoLog.i(scene.keyboardLine())
        PianoLog.i(scene.perfLine())
    }

    @Suppress("unused")
    private fun grade(g: Grade) = g.name
}
