package com.chargis.app.data.fake

import android.util.Log
import com.chargis.app.domain.engine.ChargeMath
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.DataSource
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.Priority
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.StationLiveStatus
import com.chargis.app.domain.model.UserSettings
import com.chargis.app.domain.model.VehicleState
import com.chargis.app.port.ChargerDataPort
import com.chargis.app.port.LocationPort
import com.chargis.app.port.NaviPort
import com.chargis.app.port.SettingsStore
import com.chargis.app.port.TripStore
import com.chargis.app.port.TtsPort
import com.chargis.app.port.VehiclePort
import com.chargis.app.sim.ScenarioSimulator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/*
 * 포트(port/Ports.kt)의 "가짜" 구현체들이다. 실제 차량·내비 대신 시뮬레이터 값을 읽어 앱에 넘겨준다.
 * 앱의 나머지 코드는 이것이 가짜인지 모른다. 그래서 나중에 진짜 SDK 구현체로 바꿔 끼워도 앱 코드는 그대로다.
 * `map { }`은 시뮬레이터 상태가 바뀔 때마다 그 값을 포트가 약속한 모양으로 바꿔 내보낸다는 뜻이다.
 */

/** 가짜 내비. 남은 거리·시간을 시뮬레이터의 달린 거리로 계산한다. */
class FakeNavi(private val sim: ScenarioSimulator) : NaviPort {
    override val routeState: Flow<RouteState?> = sim.state.map { s ->
        val sc = sim.scenario
        val remaining = (sc.totalDistanceKm - s.traveledKm).coerceAtLeast(0.0)
        RouteState(
            current = sim.positionAt(s.traveledKm),
            destination = sc.destination,
            remainingDistanceKm = remaining,
            remainingTimeMin = (remaining / sc.speedKmh * 60).toInt()
        )
    }

    // 시나리오 밖 충전소(공공데이터)도 받는다. 시뮬레이터가 그 충전소를 경로에 올려 두고 거기서 멈춘다.
    override suspend fun addWaypoint(station: ChargingStation): Result<Unit> =
        if (sim.setWaypoint(station)) Result.success(Unit)
        else Result.failure(IllegalStateException("waypoint not on route: ${station.id}"))

    override suspend fun removeWaypoint(station: ChargingStation): Result<Unit> {
        sim.clearWaypoint(station.id)
        return Result.success(Unit)
    }

    // 데모에서는 경로가 한 줄뿐이라 다시 찾을 것이 없다. 항상 성공으로 답한다.
    override suspend fun requestReRoute(): Result<Unit> = Result.success(Unit)
}

/** 가짜 차량. 배터리 %와 주행 여부를 시뮬레이터에서 읽는다. 데이터 출처는 SIMULATED로 표시한다. */
class FakeVehicle(private val sim: ScenarioSimulator) : VehiclePort {
    override val vehicleState: Flow<VehicleState> = sim.state.map { s ->
        val p = sim.scenario.profile
        VehicleState(
            socPercent = s.soc,
            rangeKm = s.soc / (ChargeMath.socPerKm(p) * sim.scenario.consumptionFactor),   // 주행가능거리 = 남은 % ÷ 1km당 %
            isDriving = s.isDriving,
            source = DataSource.SIMULATED,
            outsideTempC = sim.scenario.outsideTempC,
            chargePowerKw = sim.chargePowerKw(s),
            odometerKm = s.traveledKm
        )
    }

    override suspend fun isSupported(): Boolean = true
}

/**
 * 시나리오 충전소 목록을 현재 위치 기준 거리로 바꿔 돌려준다.
 * 예) 출발점에서 120km 지점 충전소, 지금 80km 달림 → "40km 앞". 이미 지나친 곳은 뺀다.
 */
class FakeChargerData(private val sim: ScenarioSimulator) : ChargerDataPort {
    override suspend fun stationsAlongRoute(route: RouteState): List<ChargingStation> {
        val sc = sim.scenario
        val traveled = sc.totalDistanceKm - route.remainingDistanceKm
        return sc.stations
            .map { it.copy(distanceAlongRouteKm = it.distanceAlongRouteKm - traveled) }
            .filter { it.distanceAlongRouteKm > 0 }
    }

    // 실시간 혼잡도 대신 시나리오 파일에 적힌 고정값을 한 번 내보낸다.
    override fun liveStatus(stationId: String): Flow<StationLiveStatus?> {
        val s = sim.scenario.stations.firstOrNull { it.id == stationId } ?: return flowOf(null)
        return flowOf(StationLiveStatus(s.id, s.availableChargers, s.expectedWaitMin))
    }
}

/** 가짜 GPS. 위치가 실제로 바뀔 때만 새 값을 내보낸다(distinctUntilChanged). */
class FakeLocation(private val sim: ScenarioSimulator) : LocationPort {
    override val location: Flow<GeoPoint> =
        sim.state.map { sim.positionAt(it.traveledKm) }.distinctUntilChanged()
}

/** 화면 자막 + 로그. [delegate]가 있으면(기본 TextToSpeech) 실제 발화도 맡긴다. */
class FakeTts(private val delegate: TtsPort? = null) : TtsPort {
    private val _caption = MutableStateFlow<String?>(null)
    val caption: StateFlow<String?> = _caption.asStateFlow()

    override suspend fun speak(text: String, priority: Priority) {
        // 개발자가 확인할 수 있게 로그(Logcat)에 남기고, 화면 자막으로 띄우고, 실제 소리도 낸다.
        Log.i(TAG, "[$priority] $text")
        _caption.value = text
        delegate?.speak(text, priority)
    }

    private companion object {
        const val TAG = "ChargisTts"
    }
}

/** 메모리에만 두는 설정 저장소. 테스트와 미리보기용. */
class InMemorySettingsStore(initial: UserSettings = UserSettings()) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<UserSettings> = state.asStateFlow()
    override suspend fun update(transform: (UserSettings) -> UserSettings) = state.update(transform)
}

/** 메모리에만 두는 여행 저장소. 테스트에서 "앱을 껐다 켜기"를 흉내 낼 때 쓴다. */
class InMemoryTripStore(var saved: String? = null) : TripStore {
    override suspend fun load(): String? = saved
    override suspend fun save(json: String?) {
        saved = json
    }
}
