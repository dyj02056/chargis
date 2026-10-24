package com.chargis.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chargis.app.AppContainer

/**
 * 화면과 [TripController]를 이어 주는 안드로이드용 보관함.
 * ViewModel은 화면이 다시 만들어져도(예: 화면 회전) 살아남으므로, 여행 진행 상태를 잃지 않는다.
 * 실제 일은 모두 TripController가 하고, 여기서는 부품을 꽂아 만들어 주기만 한다.
 */
class TripViewModel(container: AppContainer) : ViewModel() {
    val controller = TripController(
        scope = viewModelScope,   // 화면이 완전히 닫히면 함께 멈추는 작업 범위
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
        // 안드로이드가 ViewModel을 만들 때 AppContainer를 넘겨줄 수 있게 하는 "제작 설명서"
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { TripViewModel(container) }
        }
    }
}
