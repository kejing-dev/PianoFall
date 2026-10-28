package io.embodit.pianofall.ui.piano

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalStageUpperLimbRenderModelManager
import com.pico.spatial.ui.platform.ability.UpperLimbRenderMode
import io.embodit.pianofall.platform.PianoLog
import io.embodit.pianofall.ui.piano.components.PianoStage
import io.embodit.pianofall.ui.piano.components.LocalPianoResources
import io.embodit.pianofall.ui.piano.components.localizedResources
import io.embodit.pianofall.ui.piano.debug.Probe
import io.embodit.pianofall.ui.piano.scene.PianoFrameSystem
import io.embodit.pianofall.ui.piano.scene.PianoScene

/**
 * The one screen of the app, in a Mixed Stage (Full Space). It wires the ViewModel (state), the
 * [PianoScene] (entities, per-frame work, taps and drags) and [PianoStage] (the panels), and owns nothing.
 */
@Composable
fun PianoScreen() {
    val context = LocalContext.current
    val viewModel: PianoViewModel = viewModel(factory = PianoViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { PianoScene(viewModel::onEvent) }
    // 拉到眼前 also re-places the panel at the current head pose (the scene owns the panels)
    val onEvent: (PianoEvent) -> Unit =
        remember(viewModel, scene) {
            { e ->
                if (e is PianoEvent.PullToEyesClicked) scene.pullPanel()
                viewModel.onEvent(e)
            }
        }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(scene, lifecycle) {
        // the one per-frame system; every start has its stop in onDispose
        registerSystem<PianoFrameSystem>()
        Probe.host =
            object : Probe.Host {
                override fun viewModel(): PianoViewModel = viewModel

                override fun scene(): PianoScene = scene
            }
        // in the background every tracker stops (design `container.lifecycle`)
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> scene.pause()
                    Lifecycle.Event.ON_START -> scene.resume()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        PianoLog.i("STAGE enter")
        onDispose {
            lifecycle.removeObserver(observer)
            Probe.host = null
            unregisterSystem<PianoFrameSystem>()
            scene.detach()
            PianoLog.i("STAGE disposed")
        }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    // the real hands over the virtual keys (design `container.upperLimbRenderMode`: hands must be seen to play)
    val limbs = LocalStageUpperLimbRenderModelManager.current
    LaunchedEffect(limbs) {
        val r = runCatching { limbs.setUpperLimbRenderMode(UpperLimbRenderMode.Visible) }
        PianoLog.i("LIMBS visible result=${r.getOrNull() ?: r.exceptionOrNull()}")
    }

    // the in-app language switches at run time: panels read their copy from this local
    val resources = remember(state.settings.language, context) { localizedResources(context, state.settings.language) }
    CompositionLocalProvider(LocalPianoResources provides resources) {
        PianoStage(state = state, scene = scene, onEvent = onEvent)
    }
}

