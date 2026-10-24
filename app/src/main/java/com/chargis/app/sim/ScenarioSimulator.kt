package com.chargis.app.sim

import com.chargis.app.domain.engine.ChargeMath
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.domain.model.VehicleProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max
import kotlin.math.min

/** 데모 시나리오(sample_route.json 한 건). stations의 distanceAlongRouteKm는 출발점 기준 거리. */
data class Scenario(
    val id: String,
    val title: String,
    val seed: Int,
    val profile: VehicleProfile,
    val totalDistanceKm: Double,
    val startSoc: Double,
    val speedKmh: Double,
    val startHour: Int,
    val start: GeoPoint,
    val destination: GeoPoint,
    val stations: List<ChargingStation>
)

enum class SimPhase { READY, DRIVING, STOPPED_AT_STATION, CHARGING, FINISHED }

data class SimState(
    val scenarioId: String,
    val phase: SimPhase,
    val traveledKm: Double,
    val soc: Double,
    val simTimeSec: Double,
    val speedMultiplier: Int,
    val paused: Boolean,
    val waypointId: String? = null,
    val stoppedAtId: String? = null,
    val chargeTargetSoc: Double? = null,
    val chargeEndsAtSec: Double? = null    // 충전기 시간 제한이 끊는 시각(없으면 null)
) {
    val isDriving: Boolean get() = phase == SimPhase.DRIVING
}

/**
 * plan.md §10. Fake 포트들을 하나의 가상 시계로 구동한다.
 * [advance]는 결정적이다: 같은 시나리오 + 같은 dt 열 → 같은 상태 열. [run]은 실제 시간에 맞춰 [advance]를 부를 뿐이다.
 */
class ScenarioSimulator(
    initial: Scenario,
    private val config: RecommendationConfig = RecommendationConfig()
) {
    var scenario: Scenario = initial
        private set

    private val _state = MutableStateFlow(initialState(initial, speed = 1))
    val state: StateFlow<SimState> = _state.asStateFlow()

    /** 시나리오를 바꾸고 출발 전 상태로 되돌린다. 배속은 유지한다. */
    fun load(next: Scenario) {
        scenario = next
        _state.value = initialState(next, _state.value.speedMultiplier)
    }

    fun start() = _state.update { if (it.phase == SimPhase.READY) it.copy(phase = SimPhase.DRIVING) else it }

    fun setSpeed(multiplier: Int) = _state.update { it.copy(speedMultiplier = multiplier.coerceAtLeast(1)) }

    fun togglePause() = _state.update { it.copy(paused = !it.paused) }

    /** F4 데모: 경유지로 등록된 휴게소에 도착하면 자동으로 정차한다. */
    fun setWaypoint(stationId: String): Boolean {
        val station = scenario.stations.firstOrNull { it.id == stationId } ?: return false
        if (station.distanceAlongRouteKm <= _state.value.traveledKm) return false
        _state.update { it.copy(waypointId = stationId) }
        return true
    }

    /** [limitMinutes]가 있으면 그 시간이 지나면 충전기가 스스로 끊는다(목표 미도달이어도). */
    fun startCharging(targetSoc: Double, limitMinutes: Int? = null) = _state.update {
        if (it.phase == SimPhase.STOPPED_AT_STATION && targetSoc > it.soc) {
            it.copy(
                phase = SimPhase.CHARGING,
                chargeTargetSoc = targetSoc,
                chargeEndsAtSec = limitMinutes?.let { m -> it.simTimeSec + m * 60.0 }
            )
        } else it
    }

    fun stopCharging() = _state.update {
        if (it.phase == SimPhase.CHARGING) it.copy(phase = SimPhase.STOPPED_AT_STATION, chargeTargetSoc = null, chargeEndsAtSec = null) else it
    }

    fun depart() = _state.update {
        if (it.phase == SimPhase.STOPPED_AT_STATION) {
            it.copy(phase = SimPhase.DRIVING, stoppedAtId = null, chargeTargetSoc = null, chargeEndsAtSec = null)
        } else it
    }

    /** 개발자 오버레이: 주행 중 [km]만큼 앞으로 건너뛴다(경유지/목적지 직전에서 멈춤). */
    fun jumpAhead(km: Double) {
        val s = _state.value
        if (s.phase != SimPhase.DRIVING) return
        val stopAt = waypointKm(s) ?: scenario.totalDistanceKm
        val target = min(s.traveledKm + km, stopAt - JUMP_GUARD_KM)
        if (target <= s.traveledKm) return
        advanceDriving(target - s.traveledKm, (target - s.traveledKm) / scenario.speedKmh * 3600.0)
    }

    fun advance(dtSec: Double) {
        val s = _state.value
        if (s.paused || dtSec <= 0) return
        when (s.phase) {
            SimPhase.READY, SimPhase.FINISHED -> Unit
            SimPhase.DRIVING -> advanceDriving(scenario.speedKmh * dtSec / 3600.0, dtSec)
            SimPhase.STOPPED_AT_STATION -> _state.value = s.copy(simTimeSec = s.simTimeSec + dtSec)
            SimPhase.CHARGING -> advanceCharging(s, dtSec)
        }
    }

    /** 실제 시간 루프. [tickMillis]마다 배속만큼 가상 시간을 진행한다. */
    suspend fun run(tickMillis: Long = 200) {
        while (true) {
            delay(tickMillis)
            advance(tickMillis / 1000.0 * _state.value.speedMultiplier)
        }
    }

    fun positionAt(traveledKm: Double): GeoPoint {
        val t = (traveledKm / scenario.totalDistanceKm).coerceIn(0.0, 1.0)
        val a = scenario.start
        val b = scenario.destination
        return GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    private fun advanceDriving(dKm: Double, dtSec: Double) {
        val s = _state.value
        val socPerKm = ChargeMath.socPerKm(scenario.profile)
        val wpKm = waypointKm(s)
        val stopKm = wpKm ?: scenario.totalDistanceKm
        val traveled = min(s.traveledKm + dKm, stopKm)
        val used = traveled - s.traveledKm
        val soc = max(0.0, s.soc - used * socPerKm)
        val time = s.simTimeSec + if (used < dKm && dKm > 0) dtSec * used / dKm else dtSec
        _state.value = when {
            wpKm != null && traveled >= wpKm -> s.copy(
                phase = SimPhase.STOPPED_AT_STATION, traveledKm = traveled, soc = soc, simTimeSec = time,
                stoppedAtId = s.waypointId, waypointId = null
            )
            traveled >= scenario.totalDistanceKm -> s.copy(
                phase = SimPhase.FINISHED, traveledKm = traveled, soc = soc, simTimeSec = time
            )
            else -> s.copy(traveledKm = traveled, soc = soc, simTimeSec = time)
        }
    }

    private fun advanceCharging(s: SimState, dtSec: Double) {
        val station = scenario.stations.first { it.id == s.stoppedAtId }
        val target = s.chargeTargetSoc ?: return
        val step = s.chargeEndsAtSec?.let { minOf(dtSec, it - s.simTimeSec) } ?: dtSec
        val soc = ChargeMath.socAfterMinutes(s.soc, step / 60.0, station.maxKw, scenario.profile, config)
        val now = s.simTimeSec + step
        val cutOff = s.chargeEndsAtSec != null && now >= s.chargeEndsAtSec
        _state.value = when {
            soc >= target -> s.copy(phase = SimPhase.STOPPED_AT_STATION, soc = target, simTimeSec = now, chargeTargetSoc = null, chargeEndsAtSec = null)
            cutOff -> s.copy(phase = SimPhase.STOPPED_AT_STATION, soc = soc, simTimeSec = now, chargeTargetSoc = null, chargeEndsAtSec = null)
            else -> s.copy(soc = soc, simTimeSec = now)
        }
    }

    private fun waypointKm(s: SimState): Double? =
        s.waypointId?.let { id -> scenario.stations.firstOrNull { it.id == id }?.distanceAlongRouteKm }

    private fun initialState(sc: Scenario, speed: Int) = SimState(
        scenarioId = sc.id,
        phase = SimPhase.READY,
        traveledKm = 0.0,
        soc = sc.startSoc,
        simTimeSec = 0.0,
        speedMultiplier = speed,
        paused = false
    )

    private companion object {
        const val JUMP_GUARD_KM = 0.5
    }
}
