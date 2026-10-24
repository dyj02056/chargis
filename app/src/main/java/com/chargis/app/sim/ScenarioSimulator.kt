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

/*
 * 데모용 "가상 고속도로"다. 실제 차와 내비가 없어도 앱을 시연할 수 있도록,
 * 차가 일정한 속도로 달리며 배터리가 줄고, 휴게소에서 멈추고, 충전하면 배터리가 차는 과정을 흉내 낸다.
 * Fake 포트들(data/fake/Fakes.kt)이 모두 이 시뮬레이터의 상태를 읽어서 앱에 전달한다.
 */

/**
 * 데모 시나리오(sample_route.json 한 건). stations의 distanceAlongRouteKm는 출발점 기준 거리.
 * 예) S1: "서울→속초(가상) 210km, 출발 배터리 60%, 시속 100km, 오전 9시 출발"
 */
data class Scenario(
    val id: String,
    val title: String,
    val seed: Int,
    val profile: VehicleProfile,
    val totalDistanceKm: Double,
    val startSoc: Double,
    val speedKmh: Double,          // 가상 차량의 일정한 주행 속도
    val startHour: Int,            // 출발 시각(시). 화면 시계가 여기서부터 흐른다.
    val outsideTempC: Double? = null,   // 바깥 기온(°C). 차량 센서 값처럼 앱에 전달된다
    /** 가상 차량의 "실제" 소비 배율. 앱은 이 값을 모르고, 달리면서 실측으로 알아내야 한다(B3 시연). */
    val consumptionFactor: Double = 1.0,
    val start: GeoPoint,
    val destination: GeoPoint,
    val stations: List<ChargingStation>
)

/** 가상 차량의 상태: 출발 전 / 주행 중 / 휴게소 정차 / 충전 중 / 도착 */
enum class SimPhase { READY, DRIVING, STOPPED_AT_STATION, CHARGING, FINISHED }

/** 시뮬레이터의 한 순간 상태 */
data class SimState(
    val scenarioId: String,
    val phase: SimPhase,
    val traveledKm: Double,                // 출발 후 달린 거리
    val soc: Double,                       // 지금 배터리 %
    val simTimeSec: Double,                // 출발 후 흐른 가상 시간(초)
    val speedMultiplier: Int,              // 시간 배속(60이면 1초에 1분이 흐름)
    val paused: Boolean,
    val waypointId: String? = null,        // 경유지로 등록된 휴게소(도착하면 자동 정차)
    val stoppedAtId: String? = null,       // 지금 멈춰 있는 휴게소
    val chargeTargetSoc: Double? = null,   // 충전 목표 %
    val chargeEndsAtSec: Double? = null    // 충전기 시간 제한이 끊는 시각(없으면 null)
) {
    val isDriving: Boolean get() = phase == SimPhase.DRIVING
}

/**
 * plan.md §10. Fake 포트들을 하나의 가상 시계로 구동한다.
 * [advance]는 결정적이다: 같은 시나리오 + 같은 dt 열 → 같은 상태 열. [run]은 실제 시간에 맞춰 [advance]를 부를 뿐이다.
 *
 * "결정적"이란 무작위 요소가 없어서 몇 번을 돌려도 결과가 똑같다는 뜻이다.
 * 그래서 테스트에서는 실제로 기다리지 않고 advance(60.0)처럼 시간을 직접 밀어 넣어 결과를 확인할 수 있다.
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

    /** 출발. 출발 전(READY) 상태일 때만 주행을 시작한다. */
    fun start() = _state.update { if (it.phase == SimPhase.READY) it.copy(phase = SimPhase.DRIVING) else it }

    // 시간 배속 설정(최소 1배)
    fun setSpeed(multiplier: Int) = _state.update { it.copy(speedMultiplier = multiplier.coerceAtLeast(1)) }

    fun togglePause() = _state.update { it.copy(paused = !it.paused) }

    /** F4 데모: 경유지로 등록된 휴게소에 도착하면 자동으로 정차한다. */
    fun setWaypoint(stationId: String): Boolean {
        val station = scenario.stations.firstOrNull { it.id == stationId } ?: return false
        // 이미 지나친 휴게소는 경유지로 등록할 수 없다.
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

    // 충전을 멈추고 휴게소 정차 상태로 돌아간다.
    fun stopCharging() = _state.update {
        if (it.phase == SimPhase.CHARGING) it.copy(phase = SimPhase.STOPPED_AT_STATION, chargeTargetSoc = null, chargeEndsAtSec = null) else it
    }

    // 휴게소에서 다시 출발한다.
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

    /** 가상 시간을 [dtSec]초만큼 앞으로 돌린다. 상태에 따라 달리거나, 시간만 흐르거나, 충전한다. */
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

    /**
     * 실제 시간 루프. [tickMillis]마다 배속만큼 가상 시간을 진행한다.
     * 예) 0.2초마다, 60배속이면 가상 시간 12초씩 진행
     */
    suspend fun run(tickMillis: Long = 200) {
        while (true) {
            delay(tickMillis)
            advance(tickMillis / 1000.0 * _state.value.speedMultiplier)
        }
    }

    /** 달린 거리로 지도 위치를 구한다. 데모에서는 출발지와 목적지를 직선으로 잇고 그 위를 비율만큼 이동한다. */
    fun positionAt(traveledKm: Double): GeoPoint {
        val t = (traveledKm / scenario.totalDistanceKm).coerceIn(0.0, 1.0)
        val a = scenario.start
        val b = scenario.destination
        return GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    // [dKm]만큼 달린다. 경유지나 목적지를 넘어가지 않도록 그 지점에서 멈춘다.
    private fun advanceDriving(dKm: Double, dtSec: Double) {
        val s = _state.value
        val socPerKm = ChargeMath.socPerKm(scenario.profile) * scenario.consumptionFactor
        val wpKm = waypointKm(s)
        val stopKm = wpKm ?: scenario.totalDistanceKm
        val traveled = min(s.traveledKm + dKm, stopKm)
        val used = traveled - s.traveledKm
        // 달린 거리만큼 배터리를 줄인다(0% 아래로는 내려가지 않음).
        val soc = max(0.0, s.soc - used * socPerKm)
        // 도중에 멈췄다면 실제로 달린 비율만큼만 시간이 흐른 것으로 친다.
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

    // [dtSec]초 동안 충전한다. 목표에 닿거나 충전기 시간 제한이 끝나면 충전을 멈춘다.
    private fun advanceCharging(s: SimState, dtSec: Double) {
        val station = scenario.stations.first { it.id == s.stoppedAtId }
        val target = s.chargeTargetSoc ?: return
        // 시간 제한이 이번 진행 중에 끝나면 딱 그 시각까지만 충전한다.
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

    /** 충전 중이면 지금 들어오는 출력(kW), 아니면 null. 가짜 차량이 차량 데이터처럼 내보낸다. */
    fun chargePowerKw(s: SimState = _state.value): Double? {
        if (s.phase != SimPhase.CHARGING) return null
        val station = scenario.stations.firstOrNull { it.id == s.stoppedAtId } ?: return null
        return ChargeMath.powerKw(s.soc, station.maxKw, scenario.profile, config)
    }

    /** 충전 중 목표 %를 바꾼다(F2). 지금보다 높아야 한다. */
    fun setChargeTarget(targetSoc: Double) = _state.update {
        if (it.phase == SimPhase.CHARGING && targetSoc > it.soc) it.copy(chargeTargetSoc = targetSoc) else it
    }

    /** 저장해 둔 상태로 되돌린다(B5 여행 복원). 일시정지 여부와 배속은 지금 값을 유지한다. */
    fun restore(next: Scenario, saved: SimState) {
        scenario = next
        _state.value = saved.copy(speedMultiplier = _state.value.speedMultiplier, paused = false)
    }

    /** 차종을 바꾼다(F5 설정). 같은 시나리오를 다른 차로 달린다. 출발 전에만 쓴다. */
    fun useProfile(profile: VehicleProfile) {
        scenario = scenario.copy(profile = profile)
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
        const val JUMP_GUARD_KM = 0.5   // 건너뛰기를 경유지·목적지 0.5km 앞에서 멈춰, 도착 과정은 직접 보이게 한다.
    }
}
