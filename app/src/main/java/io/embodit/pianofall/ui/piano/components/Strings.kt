package io.embodit.pianofall.ui.piano.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.pianofall.domain.LanguagePref
import java.util.Locale

/**
 * Copy for the language chosen in the app (follow system / 中文 / EN), switched at run time without
 * restarting the Stage: the screen provides a Resources object for that locale and every panel reads
 * its strings from it (Spatial Ruler). `LocalContext` is not overridden.
 */
val LocalPianoResources = staticCompositionLocalOf<Resources> { error("LocalPianoResources not provided") }

fun localizedResources(context: Context, language: LanguagePref): Resources {
    val tag =
        when (language) {
            LanguagePref.SYSTEM -> return context.resources
            LanguagePref.ZH -> "zh-CN"
            LanguagePref.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalPianoResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalPianoResources.current.getString(id, *args)

/** The panels speak Chinese (the in-app language, or the system's). */
@Composable
@ReadOnlyComposable
fun isZh(): Boolean = LocalPianoResources.current.configuration.locales[0].language == "zh"

/** Panel sizes (design `spatialLayout.panel`: 0.34 x 0.24 m side panel, mini bar), in dp. */
object PanelSizes {
    const val SIDE_W = 480f
    const val SIDE_H = 340f

    const val MINI_W = 300f
    const val MINI_H = 64f

    const val DISABLED_ALPHA = 0.38f
}
