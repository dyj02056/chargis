package com.chargis.app.data.fake

import android.util.Log
import com.chargis.app.domain.engine.ChargeMath
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.DataSource
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.Priority
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.StationLiveStatus
import com.chargis.app.domain.model.VehicleState
import com.chargis.app.port.ChargerDataPort
import com.chargis.app.port.LocationPort
import com.chargis.app.port.NaviPort
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

    override suspend fun addWaypoint(station: ChargingStation): Result<Unit> =
        if (sim.setWaypoint(station.id)) Result.success(Unit)
        else Result.failure(IllegalStateException("waypoint not on route: ${station.id}"))

    override suspend fun requestReRoute(): Result<Unit> = Result.success(Unit)
}

class FakeVehicle(private val sim: ScenarioSimulator) : VehiclePort {
    override val vehicleState: Flow<VehicleState> = sim.state.map { s ->
        val p = sim.scenario.profile
        VehicleState(
            socPercent = s.soc,
            rangeKm = s.soc / ChargeMath.socPerKm(p),
            isDriving = s.isDriving,
            source = DataSource.SIMULATED
        )
    }

    override suspend fun isSupported(): Boolean = true
}

/** 시나리오 휴게소 목록을 현재 위치 기준 거리로 바꿔 돌려준다. */
class FakeChargerData(private val sim: ScenarioSimulator) : ChargerDataPort {
    override suspend fun stationsAlongRoute(route: RouteState): List<ChargingStation> {
        val sc = sim.scenario
        val traveled = sc.totalDistanceKm - route.remainingDistanceKm
        return sc.stations
            .map { it.copy(distanceAlongRouteKm = it.distanceAlongRouteKm - traveled) }
            .filter { it.distanceAlongRouteKm > 0 }
    }

    override fun liveStatus(stationId: String): Flow<StationLiveStatus?> {
        val s = sim.scenario.stations.firstOrNull { it.id == stationId } ?: return flowOf(null)
        return flowOf(StationLiveStatus(s.id, s.availableChargers, s.expectedWaitMin))
    }
}

class FakeLocation(private val sim: ScenarioSimulator) : LocationPort {
    override val location: Flow<GeoPoint> =
        sim.state.map { sim.positionAt(it.traveledKm) }.distinctUntilChanged()
}

/** 화면 자막 + 로그. [delegate]가 있으면(기본 TextToSpeech) 실제 발화도 맡긴다. */
class FakeTts(private val delegate: TtsPort? = null) : TtsPort {
    private val _caption = MutableStateFlow<String?>(null)
    val caption: StateFlow<String?> = _caption.asStateFlow()

    override suspend fun speak(text: String, priority: Priority) {
        Log.i(TAG, "[$priority] $text")
        _caption.value = text
        delegate?.speak(text, priority)
    }

    private companion object {
        const val TAG = "ChargisTts"
    }
}
