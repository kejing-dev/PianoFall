package io.embodit.pianofall.platform

import android.content.Context
import io.embodit.pianofall.data.PianoStore
import io.embodit.pianofall.data.PrefsPianoStore
import io.embodit.pianofall.platform.anchor.WorldAnchorGateway
import io.embodit.pianofall.platform.sense.PlaneTracker
import io.embodit.pianofall.platform.tracking.ControllerInput
import io.embodit.pianofall.platform.tracking.HandInput
import io.embodit.pianofall.platform.tracking.HeadTracker

/**
 * Process-wide platform services. The scene starts and stops the sensing services (every start has a
 * stop: `PianoScene.attach` / `pause` / `detach`); the ViewModel only sees interfaces and lambdas.
 */
object PianoGraph {
    val anchors = WorldAnchorGateway()
    val planes = PlaneTracker()
    val head = HeadTracker()
    val controller = ControllerInput()
    val hands = HandInput()

    @Volatile private var store: PianoStore? = null

    fun store(context: Context): PianoStore = store ?: synchronized(this) { store ?: PrefsPianoStore(context).also { store = it } }
}
