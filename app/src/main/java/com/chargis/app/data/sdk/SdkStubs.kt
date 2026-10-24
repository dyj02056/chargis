package com.chargis.app.data.sdk

import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.VehicleState
import com.chargis.app.port.NaviPort
import com.chargis.app.port.VehiclePort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

// Pleos Connect SDK가 docs/sdk/ 또는 libs/에 들어오기 전까지 실제 호출을 쓰지 않는다(plan.md §0.2-1).
// 시그니처를 추측하지 않으며, 모든 메서드는 "정보 없음"을 돌려준다. AppContainer는 아직 이 클래스들을 쓰지 않는다.
//
// 실제 차량에 연결할 자리만 미리 만들어 둔 "빈 껍데기"다. 차량 SDK(현대차그룹이 제공하는 개발 도구)를 받으면
// 이 클래스들 안을 채우고, AppContainer에서 Fake 대신 이것을 끼우면 된다.
// TODO(SDK)는 "SDK가 오면 할 일"이라는 표시다.

/** TODO(SDK): NaviHelper — getRouteStateInfo/getCurrentLocationInfo/addWaypoint/requestReRoute/addListener 연동. */
class SdkNavi : NaviPort {
    override val routeState: Flow<RouteState?> = emptyFlow()
    override suspend fun addWaypoint(station: ChargingStation): Result<Unit> =
        Result.failure(UnsupportedOperationException("TODO(SDK): NaviHelper.addWaypoint"))
    override suspend fun removeWaypoint(station: ChargingStation): Result<Unit> =
        Result.failure(UnsupportedOperationException("TODO(SDK): NaviHelper 경유지 삭제(제공 여부 확인 필요)"))
    override suspend fun requestReRoute(): Result<Unit> =
        Result.failure(UnsupportedOperationException("TODO(SDK): NaviHelper.requestReRoute"))
}

/** TODO(SDK): Vehicle SDK — SoC, 주행가능거리, 주행/정차 상태(권한 승인 필요). */
class SdkVehicle : VehiclePort {
    override val vehicleState: Flow<VehicleState> = emptyFlow()
    override suspend fun isSupported(): Boolean = false
}
