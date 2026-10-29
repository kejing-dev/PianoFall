package io.embodit.pianofall.ui.piano.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.LinearProgressIndicator
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.pianofall.R
import io.embodit.pianofall.ui.piano.PianoEvent
import io.embodit.pianofall.ui.piano.PianoUiState
import io.embodit.pianofall.ui.piano.debug.probe
import io.embodit.pianofall.ui.theme.PianoColors
import io.embodit.pianofall.ui.theme.PianoType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * P2, the mini bar above the curtain while playing: song name, progress and ⏸ (S10), "等你按" (S11), the
 * countdown digit (S09), "放下手柄" with the hands seen (S21). ⏸ has to be held 0.6 s (a ring fills): a stray
 * gaze + pinch while playing does not stop the song (design `input.pinchGuard`).
 */
@Composable
internal fun MiniBar(state: PianoUiState?, onEvent: (PianoEvent) -> Unit) {
    Row(
        modifier =
            Modifier
                .size(PanelSizes.MINI_W.dp, PanelSizes.MINI_H.dp)
                .clip(RoundedCornerShape(32.dp))
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state == null) return@Row
        when (state.designState) {
            "S09" -> {
                Text(text = rs(R.string.get_ready), style = PianoType.hint, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.weight(1f))
                Text(text = state.countdown.toString(), style = PianoType.display, color = PicoTheme.colorScheme.labelPrimary)
                Box(Modifier.weight(1f))
            }
            "S21" -> {
                HandDot(R.drawable.ic_hand_left, state.handsLeft, PianoColors.LeftHand)
                HandDot(R.drawable.ic_hand_right, state.handsRight, PianoColors.RightHand)
                Text(text = rs(R.string.hands_ready), style = PianoType.hint, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, modifier = Modifier.weight(1f))
                Text(
                    text = rs(R.string.direct_start),
                    style = PianoType.label,
                    color = PicoTheme.colorScheme.labelPrimary,
                    maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).drawBehindFill(PicoTheme.colorScheme.fillTertiary).clickableNoRipple { onEvent(PianoEvent.DirectStartClicked) }.probe("directStart").padding(8.dp),
                )
            }
            else -> {
                val waiting = state.designState == "S11"
                Box(Modifier.weight(1f)) {
                    if (state.octaveHint != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = rs(R.string.octave), style = PianoType.hint, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2, modifier = Modifier.weight(1f))
                            Text(text = rs(R.string.fix), style = PianoType.label, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.clip(RoundedCornerShape(16.dp)).drawBehindFill(PicoTheme.colorScheme.fillTertiary).clickableNoRipple { onEvent(PianoEvent.FixOctaveClicked) }.probe("fixOctave").padding(6.dp))
                        }
                    } else {
                        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                // the green lives in the breathing keys; the words stay in the label role (D13 contrast)
                                text =
                                    when {
                                        waiting -> "● " + rs(R.string.wait_you)
                                        state.combo >= 2 -> rs(R.string.combo_n, state.combo) + gradeWord(state)
                                        else -> if (isZh()) state.song.zh else state.song.en
                                    },
                                style = if (state.combo >= 2 && !waiting) PianoType.label else PianoType.hint,
                                color = PicoTheme.colorScheme.labelPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().height(4.dp))
                        }
                    }
                }
                MuteKey(state, onEvent)
                HoldPause(onEvent)
            }
        }
    }
}

@Composable
private fun HandDot(icon: Int, seen: Boolean, color: androidx.compose.ui.graphics.Color) {
    Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp), tint = if (seen) color else PicoTheme.colorScheme.labelTertiary)
}

@Composable
private fun MuteKey(state: PianoUiState, onEvent: (PianoEvent) -> Unit) {
    Box(
        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).drawBehindFill(PicoTheme.colorScheme.fillTertiary).clickableNoRipple { onEvent(PianoEvent.MuteClicked) }.probe("miniMute"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painterResource(if (state.settings.muted) R.drawable.ic_mute else R.drawable.ic_volume), contentDescription = if (state.settings.muted) rs(R.string.unmute) else rs(R.string.mute), modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimary)
    }
}

/** ⏸ that fires after a 0.6 s hold; the ring shows how far. */
@Composable
private fun HoldPause(onEvent: (PianoEvent) -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val accent = PicoTheme.colorScheme.interaction
    val fill = PicoTheme.colorScheme.fillTertiary
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .probe("pause")
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var job: Job? = null
                        while (true) {
                            val e = awaitPointerEvent()
                            when (e.type) {
                                PointerEventType.Press -> {
                                    job?.cancel()
                                    job = scope.launch {
                                        val steps = 12
                                        for (i in 1..steps) {
                                            delay(HOLD_MS / steps)
                                            progress = i / steps.toFloat()
                                        }
                                        onEvent(PianoEvent.PauseClicked)
                                        progress = 0f
                                    }
                                }
                                PointerEventType.Release, PointerEventType.Exit -> {
                                    job?.cancel()
                                    progress = 0f
                                }
                                else -> Unit
                            }
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(46.dp)) {
            drawCircle(fill)
            if (progress > 0f) drawArc(accent, -90f, 360f * progress, false, topLeft = Offset(3f, 3f), size = Size(size.width - 6f, size.height - 6f), style = Stroke(width = 5f))
        }
        Icon(painter = painterResource(R.drawable.ic_pause), contentDescription = rs(R.string.pause), modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimary)
    }
}

private const val HOLD_MS = 600L

internal fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

/** " · Perfect" / " · Good" after the combo (early / late count as Good). */
@androidx.compose.runtime.Composable
private fun gradeWord(state: PianoUiState): String =
    when (state.lastGrade) {
        io.embodit.pianofall.domain.Grade.PERFECT -> " · " + rs(R.string.grade_perfect)
        null -> ""
        else -> " · " + rs(R.string.grade_good)
    }
