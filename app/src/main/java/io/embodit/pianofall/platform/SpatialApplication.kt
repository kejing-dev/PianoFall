package io.embodit.pianofall.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.pianofall.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
