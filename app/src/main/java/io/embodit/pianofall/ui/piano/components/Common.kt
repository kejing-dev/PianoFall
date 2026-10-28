package io.embodit.pianofall.ui.piano.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import io.embodit.pianofall.ui.piano.debug.probe
import io.embodit.pianofall.ui.theme.PianoType

internal val KeyH = 48.dp

/**
 * A worded button, 48 dp high (B19: buttons ≥ 48 dp, icons carry words). [primary] = the interaction accent with
 * dark ink (the confirming action), otherwise the tertiary fill with primary label colour (D13: role pairs only).
 */
@Composable
internal fun Action(text: String, name: String, primary: Boolean = false, width: Dp = 0.dp, icon: Int? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val colors =
        if (primary) ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = InkOnAccent)
        else ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary)
    val w = if (width > 0.dp) width else 140.dp
    Button(
        onClick = onClick,
        modifier = Modifier.height(KeyH).width(w).probe(name),
        size = ButtonDefaults.buttonSize(width = w, height = KeyH),
        contentPadding = PaddingValues(horizontal = 12.dp),
        enabled = enabled,
        colors = colors,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text = text, style = PianoType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Ink on the teal accent: 9.9:1 on #2EC4B6 (D13 measured, ui-audit.md). */
internal val InkOnAccent = Color(0xFF0B1F1D)

/** A segmented choice (hands, mode, speed, key count): the selected item in the accent with dark ink. */
@Composable
internal fun <T> Segments(items: List<Pair<T, String>>, selected: T, name: String, itemWidth: Dp, enabled: (T) -> Boolean = { true }, onPick: (T) -> Unit) {
    SegmentControl(modifier = Modifier.width(itemWidth * items.size + 8.dp).height(KeyH), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEach { (value, text) ->
            val on = enabled(value)
            SegmentItem(
                selected = value == selected,
                onClick = { if (on) onPick(value) },
                modifier = Modifier.width(itemWidth).height(KeyH).probe("$name:$value"),
                textStyle = PianoType.label,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = if (on) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelQuaternary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.interaction,
                        selectedItemContentColor = InkOnAccent,
                    ),
                title = { Text(text = text, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible) },
            )
        }
    }
}

/** A labelled row: the label on the left, the control on the right. */
@Composable
internal fun LabelRow(label: String, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = PianoType.hint, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
        content()
    }
}
