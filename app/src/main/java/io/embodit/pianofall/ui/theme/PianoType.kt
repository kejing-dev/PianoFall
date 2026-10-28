package io.embodit.pianofall.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme

/**
 * Type roles of the design (`typography`, read at 0.6 - 0.8 m: body ≥ 14 dp, buttons ≥ 16 dp): PicoTheme roles in
 * three sizes, Noto Sans.
 */
object PianoType {
    /** 20/26 SemiBold: the one instruction of a step. */
    val instruction: TextStyle
        @Composable get() = PicoTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)

    /** 17/22 SemiBold: song names, panel titles. */
    val title: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)

    /** 16/20 Medium: button labels. */
    val label: TextStyle
        @Composable get() = PicoTheme.typography.labelLarge.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)

    /** 15/21 Regular: explanations. */
    val body: TextStyle
        @Composable get() = PicoTheme.typography.bodyLargeMultiline.copy(fontSize = 15.sp, lineHeight = 21.sp)

    /** 14/18 Medium: small print, card details. */
    val hint: TextStyle
        @Composable get() = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)

    /** The big numbers: countdown, accuracy. */
    val display: TextStyle
        @Composable get() = PicoTheme.typography.displaySmall.copy(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")
}
