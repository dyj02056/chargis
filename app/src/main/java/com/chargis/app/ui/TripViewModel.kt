package com.chargis.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chargis.app.AppContainer

class TripViewModel(container: AppContainer) : ViewModel() {
    val controller = TripController(
        scope = viewModelScope,
        navi = container.navi,
        vehicle = container.vehicle,
        chargerData = container.chargerData,
        announcer = container.announcer,
        phrases = container.phrases,
        demo = container.simulator,
        scenarios = container.catalog.scenarios,
        config = container.config,
        captions = container.tts.caption
    )

    val state = controller.state

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { TripViewModel(container) }
        }
    }
}
