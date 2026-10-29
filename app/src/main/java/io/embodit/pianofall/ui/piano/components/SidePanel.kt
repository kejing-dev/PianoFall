package io.embodit.pianofall.ui.piano.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.pianofall.R
import io.embodit.pianofall.domain.HandSel
import io.embodit.pianofall.domain.KeyCount
import io.embodit.pianofall.domain.LanguagePref
import io.embodit.pianofall.domain.Library
import io.embodit.pianofall.domain.PanelSide
import io.embodit.pianofall.domain.PianoMode
import io.embodit.pianofall.domain.PlayMode
import io.embodit.pianofall.domain.SPEEDS
import io.embodit.pianofall.domain.Song
import io.embodit.pianofall.ui.piano.Overlay
import io.embodit.pianofall.ui.piano.PianoEvent
import io.embodit.pianofall.ui.piano.PianoUiState
import io.embodit.pianofall.ui.piano.debug.probe
import io.embodit.pianofall.ui.theme.PianoColors
import io.embodit.pianofall.ui.theme.PianoType
import java.util.Locale

/**
 * P1, the side panel (0.34 x 0.24 m): a top bar (name · 琴 menu · mute · ⚙), the page of the state, and the
 * settings / input-source / piano-menu overlays drawn inside it (design `spatialUiMap.dialog`: no system dialog
 * in a Stage, so nothing can be covered by another panel, D8).
 */
@Composable
internal fun SidePanel(state: PianoUiState?, onEvent: (PianoEvent) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier =
            Modifier
                .size(PanelSizes.SIDE_W.dp, PanelSizes.SIDE_H.dp)
                .clip(shape)
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
                .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state == null) return@Column
        TopBar(state, onEvent)
        when (state.overlay) {
            Overlay.SETTINGS -> SettingsPage(state, onEvent)
            Overlay.INPUT -> InputPage(state, onEvent)
            Overlay.PIANO_MENU -> PianoMenu(state, onEvent)
            Overlay.NONE -> Page(state, onEvent)
        }
    }
}

@Composable
private fun TopBar(state: PianoUiState, onEvent: (PianoEvent) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painter = painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)), tint = Color.Unspecified)
        Text(text = rs(R.string.app_name), style = PianoType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.weight(1f))
        if (state.virtual && state.keyboard != null && state.designState in setOf("S07", "S12", "S16", "S13", "S08")) {
            TopKey(R.drawable.ic_piano, rs(R.string.piano_menu), "pianoMenu", state.overlay == Overlay.PIANO_MENU) { onEvent(PianoEvent.OverlayClicked(Overlay.PIANO_MENU)) }
        }
        TopKey(if (state.settings.muted) R.drawable.ic_mute else R.drawable.ic_volume, if (state.settings.muted) rs(R.string.unmute) else rs(R.string.mute), "mute", state.settings.muted) { onEvent(PianoEvent.MuteClicked) }
        if (state.designState != "S00") TopKey(R.drawable.ic_settings, rs(R.string.settings), "settings", state.overlay == Overlay.SETTINGS) { onEvent(PianoEvent.OverlayClicked(Overlay.SETTINGS)) }
    }
}

/** An icon key with its word (B19: an icon alone is not obvious). */
@Composable
private fun TopKey(icon: Int, label: String, name: String, on: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name),
        colors = IconButtonDefaults.iconButtonColors(containerColor = if (on) PicoTheme.colorScheme.fillSecondary else PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
        size = IconButtonDefaults.iconButtonSize(width = 84.dp, height = 40.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = PicoTheme.colorScheme.labelPrimary)
            Text(text = label, style = PianoType.hint, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}

@Composable
private fun ColumnScope.Page(s: PianoUiState, onEvent: (PianoEvent) -> Unit) {
    when (s.designState) {
        "S00" -> Busy(rs(R.string.s00_loading))
        "S01" -> if (s.pickingCount) PickCount(s, onEvent) else Welcome(onEvent)
        "S02" -> Step(rs(R.string.s02_repin), rs(R.string.hand_alt_repin), if (s.controller) rs(R.string.joystick_turn) else null) {
            Action(rs(R.string.cancel), "repinCancel") { onEvent(PianoEvent.RepinCancelClicked) }
            Action(rs(R.string.done), "repinDone", primary = true) { onEvent(PianoEvent.AlignDoneClicked) }
        }
        "S03" -> {
            CornerFigure(right = false)
            Step(rs(R.string.left_end), rs(R.string.hand_alt), null) {
                Action(rs(R.string.back), "back", icon = R.drawable.ic_back) { onEvent(PianoEvent.BackClicked) }
                Action(rs(R.string.pull_here), "pull", icon = R.drawable.ic_eye, width = 150.dp) { onEvent(PianoEvent.PullToEyesClicked) }
            }
        }
        "S04" -> {
            CornerFigure(right = true)
            val live = s.liveLength
            val line = if (live != null) rs(R.string.s04_live, live * 100f, io.embodit.pianofall.domain.LengthSnap.nearest(live).keys) else rs(R.string.s04_wait)
            Step(rs(R.string.right_end), line, null) {
                Action(rs(R.string.redo_left), "redoLeft", width = 160.dp) { onEvent(PianoEvent.BackClicked) }
            }
        }
        "S05" -> FineTune(s, onEvent)
        "S06" -> Mismatch(s, onEvent)
        "S07" -> SongLibrary(s, onEvent)
        "S08" -> Ready(s, onEvent)
        "S12" -> Paused(s, onEvent)
        "S13" -> Finished(s, onEvent)
        "S16" -> WelcomeBack(s, onEvent)
        "S17" -> Step(rs(R.string.s17_lost), null, null) {
            Action(if (s.virtual) rs(R.string.place_again) else rs(R.string.realign), "realign", primary = true, width = 170.dp) { onEvent(PianoEvent.RealignClicked) }
        }
        "S18" -> {
            val repin = s.lostTick > 0 || SystemClock.elapsedRealtime() - s.lostSinceMs >= 5_000L
            Step(rs(R.string.look_back), rs(R.string.s18_hint), null) {
                if (repin && !s.virtual) Action(rs(R.string.repin), "repin", width = 160.dp) { onEvent(PianoEvent.RepinClicked) }
                if (repin && s.virtual) Action(rs(R.string.move_here), "moveHere", width = 160.dp) { onEvent(PianoEvent.MoveHereClicked) }
            }
        }
        "S19" -> Step(rs(R.string.s19_text), null, null) {
            Action(rs(R.string.s19_grant), "grant") { onEvent(PianoEvent.GrantClicked) }
            Action(rs(R.string.s19_without), "without", primary = true, width = 160.dp) { onEvent(PianoEvent.UseWithoutClicked) }
        }
        "S22" -> {
            Busy(rs(R.string.find_desk), small = true)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Action(rs(R.string.use_virtual_desk), "useVirtualDesk", width = 260.dp) { onEvent(PianoEvent.UseVirtualDeskClicked) }
            }
        }
        "S23" -> VirtualDesk(s, onEvent)
        "S24" -> PlacePiano(s, onEvent)
        else -> Unit
    }
}

@Composable
private fun ColumnScope.Busy(text: String, small: Boolean = false) {
    Box(modifier = Modifier.fillMaxWidth().then(if (small) Modifier.height(120.dp) else Modifier.weight(1f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(36.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(36.dp))
            Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = PianoType.body, textAlign = TextAlign.Center)
        }
    }
}

/** One instruction, an optional hint and a third line, then the buttons at the bottom right. */
@Composable
private fun ColumnScope.Step(text: String, hint: String?, hint2: String?, buttons: @Composable () -> Unit) {
    Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = PianoType.instruction, maxLines = 3)
    if (hint != null) Text(text = hint, color = PicoTheme.colorScheme.labelSecondary, style = PianoType.body, maxLines = 2)
    if (hint2 != null) Text(text = hint2, color = PicoTheme.colorScheme.labelSecondary, style = PianoType.hint, maxLines = 1)
    Spacer(modifier = Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) { buttons() }
}

// ---------------------------------------------------------------------------------------------
// S01
// ---------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.Welcome(onEvent: (PianoEvent) -> Unit) {
    Text(text = rs(R.string.s01_line), color = PicoTheme.colorScheme.labelSecondary, style = PianoType.body, maxLines = 1)
    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ModeCard(R.drawable.ic_piano, rs(R.string.have_piano), rs(R.string.have_piano_hint), "havePiano", Modifier.weight(1f)) { onEvent(PianoEvent.HavePianoClicked) }
        ModeCard(R.drawable.ic_desk, rs(R.string.no_piano), rs(R.string.no_piano_hint), "noPiano", Modifier.weight(1f)) { onEvent(PianoEvent.NoPianoClicked) }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Action(rs(R.string.pull_here), "pull", icon = R.drawable.ic_eye, width = 150.dp) { onEvent(PianoEvent.PullToEyesClicked) }
    }
}

@Composable
private fun ModeCard(icon: Int, title: String, hint: String, name: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(18.dp))
                .spatialHoverEffect()
                .clickable(onClick = onClick)
                .probe(name)
                .then(Modifier)
                .padding(2.dp),
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).backgroundTint().padding(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(44.dp), tint = PicoTheme.colorScheme.labelPrimary)
                Text(text = title, style = PianoType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
                Text(text = hint, style = PianoType.hint, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3)
            }
        }
    }
}

/** A card's fill: the tertiary fill role (D13: the label roles keep ≥ 4.5:1 on it). */
@Composable
private fun Modifier.backgroundTint(): Modifier = drawBehindFill(PicoTheme.colorScheme.fillTertiary)

internal fun Modifier.drawBehindFill(c: Color): Modifier = this.drawBehind { drawRect(c) }

@Composable
private fun ColumnScope.PickCount(s: PianoUiState, onEvent: (PianoEvent) -> Unit) {
    Text(text = rs(R.string.welcome), color = PicoTheme.colorScheme.labelPrimary, style = PianoType.instruction, maxLines = 2)
    Text(text = rs(R.string.count_hint), color = PicoTheme.colorScheme.labelSecondary, style = PianoType.body, maxLines = 2)
    Segments(KeyCount.real.map { it to "${it.keys}" }, s.count, "count", 82.dp) { onEvent(PianoEvent.CountPicked(it)) }
    Text(text = rs(R.string.count_whites, s.count.keys, s.count.whites), color = PicoTheme.colorScheme.labelSecondary, style = PianoType.hint, maxLines = 1)
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        Action(rs(R.string.back), "back", icon = R.drawable.ic_back) { onEvent(PianoEvent.BackClicked) }
        Action(rs(R.string.start_align), "startAlign", primary = true, width = 170.dp) { onEvent(PianoEvent.StartAlignClicked) }
    }
}

/** S03 / S04 figure: a keyboard strip, the corner to touch lit. */
@Composable
private fun CornerFigure(right: Boolean) {
    val line = PicoTheme.colorScheme.labelSecondary
    Canvas(modifier = Modifier.fillMaxWidth().height(34.dp)) {
        val w = size.width
        val h = size.height
        val n = 14
        val kw = w / n
        for (i in 0..n) drawLine(line, Offset(i * kw, 4f), Offset(i * kw, h - 2f), strokeWidth = 1.5f)
        drawLine(line, Offset(0f, h - 2f), Offset(w, h - 2f), strokeWidth = 2f)
        val pattern = intArrayOf(1, 1, 0, 1, 1, 1, 0)
        for (i in 0 until n - 1) if (pattern[i % 7] == 1) drawRoundRect(line, topLeft = Offset((i + 0.68f) * kw, 4f), size = Size(kw * 0.64f, h * 0.55f), cornerRadius = CornerRadius(2f))
        drawCircle(PianoColors.LeftHand, radius = 7f, center = Offset(if (right) w - 3f else 3f, h - 3f))
    }
}

// ---------------------------------------------------------------------------------------------
// S05 / S06 / S16
// ---------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.FineTune(s: PianoUiState, onEvent: (PianoEvent) -> Unit) {
    Text(text = rs(R.string.finetune), color = PicoTheme.colorScheme.labelPrimary, style = PianoType.instruction, maxLines = 2)
    Text(text = rs(R.string.joystick), color = PicoTheme.colorScheme.labelSecondary, style = PianoType.body, maxLines = 2)
    s.keyboard?.let { Text(text = rs(R.string.s05_size, it.count.keys, it.length * 100f), color = PicoTheme.colorScheme.labelSecondary, style = PianoType.hint, maxLines = 1) }
    if (s.midiName != null) {
        val ok = s.rangeNote != null && s.rangeNote != "transposed"
        val text = when {
            s.rangeNote == "transposed" -> rs(R.string.midi_transposed)
            ok -> rs(R.string.midi_range_ok)
            else -> rs(R.string.midi_range)
        }
        Text(text = text, color = if (ok) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary, style = PianoType.hint, maxLines = 1)
    }
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        Action(rs(R.string.check_c), "checkC", width = 170.dp) { onEvent(PianoEvent.MiddleCClicked) }
        Action(rs(R.string.done), "alignDone", primary = true) { onEvent(PianoEvent.AlignDoneClicked) }
    }
}

@Composable
private fun ColumnScope.Mismatch(s: PianoUiState, onEvent: (PianoEvent) -> Unit) {
    val (len, a, b) = s.mismatch ?: return
    Text(text = rs(R.string.s06_text, len * 100f, a.keys, a.stdLength * 100f, b.keys, b.stdLength * 100f), color = PicoTheme.colorScheme.labelPrimary, style = PianoType.instruction, maxLines = 3)
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        Action(rs(R.string.remeasure), "remeasure") { onEvent(PianoEvent.RemeasureClicked) }
        Action(rs(R.string.keys_n, a.keys), "pickA", primary = true, width = 110.dp) { onEvent(PianoEvent.CountPicked(a)) }
        Action(rs(R.string.keys_n, b.keys), "pickB", primary = true, width = 110.dp) { onEvent(PianoEvent.CountPicked(b)) }
    }
}

@Composable
private fun ColumnScope.WelcomeBack(s: PianoUiState, onEvent: (PianoEvent) -> Unit) {
    if (s.virtual) {
        Step(rs(R.string.s16_virtual), rs(R.string.joystick_turn), null) {
            Action(rs(R.string.place_again), "placeAgain", width = 120.dp) { onEvent(PianoEvent.PlaceAgainClicked) }
            Action(rs(R.string.move_here), "moveHere", width = 120.dp) { onEvent(PianoEvent.MoveHereClicked) }
            Action(rs(R.string.go_on), "keyboardOk", primary = true, width = 120.dp) { onEvent(PianoEvent.KeyboardOkClicked) }
        }
    } else {
        Step(rs(R.string.s16_real), rs(R.string.joystick_turn), null) {
            Action(rs(R.string.repin), "repin", width = 120.dp) { onEvent(PianoEvent.RepinClicked) }
            Action(rs(R.string.s16_no), "realign", width = 120.dp) { onEvent(PianoEvent.RealignClicked) }
            Action(rs(R.string.s16_yes), "keyboardOk", primary = true, width = 140.dp) { onEvent(PianoEvent.KeyboardOkClicked) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// S07 library
// ---------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.SongLibrary(s: PianoUiState, onEvent: (PianoEvent) -> Unit) {
    val current = s.libraryLevel
    val levels = Library.byLevel
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Segments(levels.keys.map { it to "${rs(R.string.level_n, it)} ${"♪".repeat(it)}" }, current, "level", 104.dp) { lv -> onEvent(PianoEvent.LevelPicked(lv)) }
    }
    val songs = levels[current] ?: emptyList()
    Column(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        songs.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { song -> SongCard(song, s, Modifier.weight(1f)) { onEvent(PianoEvent.SongPicked(song.id)) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (songs.size <= 2) Spacer(Modifier.weight(1f))
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (s.virtual) Action(rs(R.string.place_again), "placeAgain", width = 150.dp) { onEvent(PianoEvent.PlaceAgainClicked) }
        else Action(rs(R.string.realign_keyboard), "realign", width = 190.dp) { onEvent(PianoEvent.RealignClicked) }
    }
}

@Composable
private fun SongCard(song: Song, s: PianoUiState, modifier: Modifier, onClick: () -> Unit) {
    val zh = isZh()
    val mem = s.songs[song.id]
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .spatialHoverEffect()
                .clickable(onClick = onClick)
                .probe("song:${song.id}")
                .backgroundTint()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = if (zh) song.zh else song.en, style = PianoType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val hands = if (song.hasLeft) rs(R.string.hands_both) else rs(R.string.hands_right)
            Text(text = "${if (zh) song.composerZh else song.composerEn} · ${fmtTime(song.lengthSec)} · $hands", style = PianoType.hint, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (mem != null && mem.bestStars > 0) Text(text = "★".repeat(mem.bestStars), style = PianoType.hint, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

internal fun fmtTime(sec: Float): String {
    val t = sec.toInt().coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", t / 60, t % 60)
}

// ---------------------------------------------------------------------------------------------
// S08 ready (with S20)
// ---------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.Ready(s: PianoUiState, onEvent: (PianoEvent) -> Unit) {
    val song = s.song
    val zh = isZh()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = if (zh) song.zh else song.en, style = PianoType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (s.showLegend || !s.settings.legendSeen) Legend()
    }
    LabelRow(rs(R.string.hands)) {
        Segments(listOf(HandSel.LEFT to rs(R.string.hand_left), HandSel.RIGHT to rs(R.string.hand_right), HandSel.BOTH to rs(R.string.hand_both)), s.options.hands, "hands", 92.dp, enabled = { it == HandSel.RIGHT || song.hasLeft }) { onEvent(PianoEvent.HandsPicked(it)) }
    }
    LabelRow(rs(R.string.mode)) {
        Segments(listOf(PlayMode.LISTEN to rs(R.string.mode_listen), PlayMode.FOLLOW to rs(R.string.mode_auto), PlayMode.WAIT to rs(R.string.mode_wait)), s.options.mode, "mode", 108.dp, enabled = { it != PlayMode.WAIT || !s.waitUnavailable }) { onEvent(PianoEvent.ModePicked(it)) }
    }
    LabelRow(rs(R.string.speed)) {
        Segments(SPEEDS.map { it to "${(it * 100).toInt()}%" }, s.options.speed, "speed", 69.dp) { onEvent(PianoEvent.SpeedPicked(it)) }
    }
    if (s.waitUnavailable) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = rs(R.string.wait_off), style = PianoType.hint, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, modifier = Modifier.weight(1f))
            Action(rs(R.string.go_connect), "goConnect", width = 96.dp) { onEvent(PianoEvent.OverlayClicked(Overlay.INPUT)) }
            Action(rs(R.string.to_virtual), "toVirtual", width = 110.dp) { onEvent(PianoEvent.ToVirtualClicked) }
        }
    } else {
        val input = when {
            s.virtual -> rs(R.string.input_virtual)
            else -> rs(R.string.input_midi, s.midiName ?: "")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = input, style = PianoType.hint, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (!s.virtual) Action(rs(R.string.change), "changeInput", width = 72.dp) { onEvent(PianoEvent.OverlayClicked(Overlay.INPUT)) }
        }
    }
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        Action(rs(R.string.back), "back", icon = R.drawable.ic_back) { onEvent(PianoEvent.BackClicked) }
        Action(rs(R.string.start), "start", primary = true, width = 160.dp) { onEvent(PianoEvent.StartClicked) }
    }
}

/** The first run's legend: a hand shape and the bar colour for each hand (B19). */
@Composable
internal fun Legend() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painter = painterResource(R.drawable.ic_hand_right), contentDescription = null, modifier = Modifier.size(18.dp), tint = PianoColors.RightHand)
        Box(Modifier.size(width = 6.dp, height = 18.dp).clip(RoundedCornerShape(3.dp)).drawBehindFill(PianoColors.RightHand))
        Text(text = rs(R.string.hand_right), style = PianoType.hint, color = PicoTheme.colorScheme.labelSecondary)
        Icon(painter = painterResource(R.drawable.ic_hand_left), contentDescription = null, modifier = Modifier.size(18.dp), tint = PianoColors.LeftHand)
        Box(Modifier.size(width = 6.dp, height = 18.dp).clip(RoundedCornerShape(3.dp)).drawBehindFill(PianoColors.LeftHand))
        Text(text = rs(R.string.hand_left), style = PianoType.hint, color = PicoTheme.colorScheme.labelSecondary)
    }
}

// ---------------------------------------------------------------------------------------------
// S12 / S13
// ---------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.Paused(s: PianoUiState, onEvent: (PianoEvent) -> Unit) {
    val zh = isZh()
    Text(text = "${rs(R.string.paused)} · ${if (zh) s.song.zh else s.song.en}", style = PianoType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    LabelRow(rs(R.string.speed)) {
        Segments(SPEEDS.map { it to "${(it * 100).toInt()}%" }, s.options.speed, "speed", 69.dp) { onEvent(PianoEvent.SpeedPicked(it)) }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Action(rs(R.string.restart), "restart", width = 130.dp) { onEvent(PianoEvent.RestartClicked) }
        Action(rs(R.string.back_bar), "backBar", width = 150.dp) { onEvent(PianoEvent.BackBarClicked) }
        Action(rs(R.string.library), "library", width = 130.dp) { onEvent(PianoEvent.LibraryClicked) }
    }
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        if (!s.virtual) Action(rs(R.string.repin), "repin", width = 150.dp) { onEvent(PianoEvent.RepinClicked) }
        Action(rs(R.string.go_on), "continue", primary = true, width = 160.dp) { onEvent(PianoEvent.ContinueClicked) }
    }
}

@Composable
private fun ColumnScope.Finished(s: PianoUiState, onEvent: (PianoEvent) -> Unit) {
    Text(text = rs(R.string.finished), style = PianoType.instruction, color = PicoTheme.colorScheme.labelPrimary)
    val score = s.score
    val best = s.songs[s.songId]?.bestAccuracy ?: -1f
    when {
        score != null && s.options.mode == PlayMode.FOLLOW -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(text = "${(score.accuracy * 100).toInt()}%", style = PianoType.display, color = PicoTheme.colorScheme.labelPrimary)
                Column {
                    Text(text = "★".repeat(score.stars) + "☆".repeat(3 - score.stars), style = PianoType.title, color = PicoTheme.colorScheme.labelPrimary)
                    Text(text = if (s.newBest) rs(R.string.new_best) else rs(R.string.best, (best * 100).toInt()), style = PianoType.hint, color = PicoTheme.colorScheme.labelSecondary)
                }
            }
            Text(text = rs(R.string.score_counts, score.perfect, score.earlyLate, score.missed, score.wrong), style = PianoType.body, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
        }
        score != null && s.options.mode == PlayMode.WAIT ->
            Text(text = rs(R.string.wait_result, score.wrong, score.meanWaitSec), style = PianoType.body, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        else -> {
            Text(text = rs(R.string.time_only, fmtTime(s.song.lengthSec / s.options.speed)), style = PianoType.body, color = PicoTheme.colorScheme.labelSecondary)
            if (s.options.mode != PlayMode.LISTEN) Text(text = rs(R.string.no_score), style = PianoType.hint, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        }
    }
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        Action(rs(R.string.other_song), "library", width = 150.dp) { onEvent(PianoEvent.LibraryClicked) }
        if (s.options.mode == PlayMode.LISTEN) {
            Action(rs(R.string.again), "again", width = 120.dp) { onEvent(PianoEvent.AgainClicked) }
            Action(rs(R.string.try_follow), "tryFollow", primary = true, width = 150.dp) { onEvent(PianoEvent.TryFollowClicked) }
        } else {
            if (s.options.speed < 1f) Action(rs(R.string.faster), "faster", width = 140.dp) { onEvent(PianoEvent.FasterClicked) }
            Action(rs(R.string.again), "again", primary = true, width = 120.dp) { onEvent(PianoEvent.AgainClicked) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// S23 / S24
// ---------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.VirtualDesk(s: PianoUiState, onEvent: (PianoEvent) -> Unit) {
    Text(text = rs(R.string.s23_title), style = PianoType.instruction, color = PicoTheme.colorScheme.labelPrimary)
    val below = s.desk?.let { d -> io.embodit.pianofall.platform.PianoGraph.head.pose()?.let { h -> ((h.position.y - d.top) * 100f).toInt() } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Action("−", "deskDown", width = 64.dp) { onEvent(PianoEvent.DeskHeightClicked(-1)) }
        Text(text = if (below != null) rs(R.string.desk_below_eyes, below) else "", style = PianoType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(190.dp), textAlign = TextAlign.Center)
        Action("+", "deskUp", width = 64.dp) { onEvent(PianoEvent.DeskHeightClicked(1)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Action(rs(R.string.lower5), "deskDown5", width = 120.dp) { onEvent(PianoEvent.DeskHeightClicked(-5)) }
        Action(rs(R.string.higher5), "deskUp5", width = 120.dp) { onEvent(PianoEvent.DeskHeightClicked(5)) }
    }
    Text(text = rs(R.string.s23_hint), style = PianoType.hint, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        Action(rs(R.string.desk_done), "deskDone", primary = true, width = 180.dp) { onEvent(PianoEvent.DeskDoneClicked) }
    }
}

@Composable
private fun ColumnScope.PlacePiano(s: PianoUiState, onEvent: (PianoEvent) -> Unit) {
    Text(text = if (s.pianoPlaced) rs(R.string.s24_placed) else rs(R.string.place_piano), style = PianoType.instruction, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
    LabelRow(rs(R.string.keys)) {
        Segments(KeyCount.virtual.map { it to rs(R.string.keys_n, it.keys) }, s.virtualCount, "vcount", 96.dp) { onEvent(PianoEvent.VirtualCountPicked(it)) }
    }
    Text(text = rs(R.string.s24_hint), style = PianoType.hint, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
    val status = when {
        s.pianoPlaced && s.snapped -> rs(R.string.snapped)
        s.pianoPlaced -> ""
        !s.controller -> rs(R.string.s24_no_controller)
        !s.ghostOnDesk -> rs(R.string.s24_off_desk)
        s.snapped -> rs(R.string.snapped)
        else -> ""
    }
    if (status.isNotEmpty()) Text(text = status, style = PianoType.hint, color = if (s.snapped) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary, maxLines = 1)
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        Action(rs(R.string.back), "back", icon = R.drawable.ic_back) { onEvent(PianoEvent.BackClicked) }
        if (s.pianoPlaced) {
            Action(rs(R.string.place_again), "placeAgain", width = 120.dp) { onEvent(PianoEvent.PlaceAgainClicked) }
            Action(rs(R.string.done), "pianoDone", primary = true) { onEvent(PianoEvent.PianoDoneClicked) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// overlays: settings (S14), input source (S15), the piano menu
// ---------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.SettingsPage(s: PianoUiState, onEvent: (PianoEvent) -> Unit) {
    val st = s.settings
    fun set(f: (io.embodit.pianofall.domain.Settings) -> io.embodit.pianofall.domain.Settings) = onEvent(PianoEvent.SettingsChanged(f(st)))
    Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabelRow(rs(R.string.set_piano)) {
            Segments(listOf(PianoMode.REAL to rs(R.string.real_piano), PianoMode.VIRTUAL to rs(R.string.virtual_piano)), s.mode, "pianoMode", 120.dp) { onEvent(PianoEvent.PianoModeClicked(it)) }
        }
        LabelRow(rs(R.string.set_language)) {
            Segments(listOf(LanguagePref.ZH to "中文", LanguagePref.EN to "English"), if (st.language == LanguagePref.SYSTEM) (if (isZh()) LanguagePref.ZH else LanguagePref.EN) else st.language, "lang", 92.dp) { l -> set { it.copy(language = l) } }
        }
        LabelRow(rs(R.string.set_volume)) {
            Segments(listOf(0.3f, 0.5f, 0.7f, 1f).map { it to "${(it * 100).toInt()}%" }, listOf(0.3f, 0.5f, 0.7f, 1f).minByOrNull { kotlin.math.abs(it - st.volume) }!!, "volume", 69.dp) { v -> set { it.copy(volume = v) } }
        }
        LabelRow(rs(R.string.set_assist)) {
            Segments(io.embodit.pianofall.domain.AssistLevel.entries.map { it to rs(assistName(it)) }, st.assist, "assist", 80.dp) { v -> set { it.copy(assist = v) } }
        }
        Text(text = rs(R.string.assist_hint), style = PianoType.hint, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        LabelRow(rs(R.string.set_metronome)) { Switch(checked = st.metronome, onCheckedChange = { on -> set { it.copy(metronome = on) } }, modifier = Modifier.probe("metronome")) }
        LabelRow(rs(R.string.set_lookahead)) {
            Segments(listOf(2, 3, 4).map { it to rs(R.string.seconds_n, it) }, st.lookAheadSec, "lookahead", 69.dp) { v -> set { it.copy(lookAheadSec = v) } }
        }
        LabelRow(rs(R.string.set_side)) {
            Segments(listOf(PanelSide.LEFT to rs(R.string.side_left), PanelSide.CENTER to rs(R.string.side_center), PanelSide.RIGHT to rs(R.string.side_right)), st.panelSide, "side", 80.dp) { v -> set { it.copy(panelSide = v) } }
        }
        if (!s.virtual) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(rs(R.string.input_source), "inputSource", width = 150.dp) { onEvent(PianoEvent.OverlayClicked(Overlay.INPUT)) }
            Action(rs(R.string.realign), "realign", width = 150.dp) { onEvent(PianoEvent.RealignClicked) }
        } else Action(rs(R.string.place_again), "placeAgain", width = 150.dp) { onEvent(PianoEvent.PlaceAgainClicked) }
        Text(text = rs(R.string.about_title), style = PianoType.title, color = PicoTheme.colorScheme.labelPrimary)
        Text(text = rs(R.string.about_samples), style = PianoType.hint, color = PicoTheme.colorScheme.labelSecondary)
        Text(text = rs(R.string.about_songs), style = PianoType.hint, color = PicoTheme.colorScheme.labelSecondary)
        Library.all.forEach { song ->
            Text(text = "${if (isZh()) song.zh else song.en} — ${song.source}", style = PianoType.hint, color = PicoTheme.colorScheme.labelSecondary)
        }
        Text(text = rs(R.string.about_fonts), style = PianoType.hint, color = PicoTheme.colorScheme.labelSecondary)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Action(rs(R.string.close), "closeSettings", primary = true, width = 120.dp) { onEvent(PianoEvent.OverlayClicked(Overlay.SETTINGS)) }
    }
}

@Composable
private fun ColumnScope.InputPage(s: PianoUiState, onEvent: (PianoEvent) -> Unit) {
    Text(text = rs(R.string.input_source), style = PianoType.instruction, color = PicoTheme.colorScheme.labelPrimary)
    val midiLine = when {
        !s.midiAvailable -> rs(R.string.midi_unsupported)
        s.midiName != null -> rs(R.string.midi_connected, s.midiName)
        else -> rs(R.string.midi_none)
    }
    Text(text = rs(R.string.midi_item), style = PianoType.title, color = if (s.midiAvailable) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary)
    Text(text = midiLine, style = PianoType.body, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
    if (s.midiName != null) {
        Text(text = rs(R.string.no_charge), style = PianoType.hint, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
        Text(text = if (s.midiTestOk) rs(R.string.midi_test_ok) else rs(R.string.midi_test), style = PianoType.body, color = if (s.midiTestOk) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary)
    }
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        Action(rs(R.string.input_none), "inputNone", width = 200.dp) { onEvent(PianoEvent.OverlayClicked(Overlay.INPUT)) }
        Action(rs(R.string.ok), "inputOk", primary = true, width = 110.dp) { onEvent(PianoEvent.OverlayClicked(Overlay.INPUT)) }
    }
}

@Composable
private fun ColumnScope.PianoMenu(s: PianoUiState, onEvent: (PianoEvent) -> Unit) {
    Text(text = rs(R.string.piano_menu_title), style = PianoType.instruction, color = PicoTheme.colorScheme.labelPrimary)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Action(rs(R.string.move_here), "moveHere", width = 240.dp, icon = R.drawable.ic_eye) { onEvent(PianoEvent.MoveHereClicked) }
        Action(if (s.pianoHidden) rs(R.string.show) else rs(R.string.hide), "hidePiano", width = 240.dp) { onEvent(PianoEvent.HidePianoClicked) }
        Action(rs(R.string.place_again), "placeAgain", width = 240.dp) { onEvent(PianoEvent.PlaceAgainClicked) }
    }
    Spacer(Modifier.weight(1f))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Action(rs(R.string.close), "closeMenu", width = 120.dp) { onEvent(PianoEvent.OverlayClicked(Overlay.PIANO_MENU)) }
    }
}

private fun assistName(a: io.embodit.pianofall.domain.AssistLevel): Int =
    when (a) {
        io.embodit.pianofall.domain.AssistLevel.EASY -> R.string.assist_easy
        io.embodit.pianofall.domain.AssistLevel.STANDARD -> R.string.assist_standard
        io.embodit.pianofall.domain.AssistLevel.PRECISE -> R.string.assist_precise
    }
