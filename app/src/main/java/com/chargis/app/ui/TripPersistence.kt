package com.chargis.app.ui

import com.chargis.app.domain.engine.AnnouncementGate
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.session.TripPhase
import com.chargis.app.sim.Scenario
import com.chargis.app.sim.SimState
import kotlinx.serialization.json.Json

/**
 * 여행 저장·복원(B5, TripController에서 분리). [TripMemory]를 저장 형식([SavedTrip])으로 바꾸고, 되살릴 때는 거꾸로 채운다.
 * 기기에 쓰고 읽는 일(TripStore)과 시뮬레이터·충전 세션을 되살리는 일은 컨트롤러가 맡고, 여기서는 값 옮기기만 한다.
 */
class TripPersistence(private val m: TripMemory, private val gate: AnnouncementGate) {

    /** 출발 전이나 도착 후에는 저장할 것이 없다(null = 지운다). */
    fun shouldClear(): Boolean = m.phase == TripPhase.Idle || m.phase == TripPhase.Finished

    /** 지금 여행을 JSON 글자로. [manualSoc]는 배터리를 직접 넣는 중일 때의 추정값(아니면 null). */
    fun encode(scenario: Scenario, sim: SimState, manualSoc: Double?): String =
        json.encodeToString(SavedTrip.serializer(), snapshot(scenario, sim, manualSoc))

    private fun snapshot(scenario: Scenario, sim: SimState, manualSoc: Double?): SavedTrip {
        // 결정 대기 중에 꺼졌다면, 다시 켰을 때 같은 추천을 다시 안내할 수 있게 "주행 중"으로 저장한다.
        val awaiting = m.phase == TripPhase.AwaitingDecision
        val offeredId = m.offer?.primary?.station?.id
        return SavedTrip(
            scenarioId = scenario.id,
            profileId = scenario.profile.id,
            sim = SavedSim.of(sim),
            phase = if (awaiting) TripPhase.Driving else m.phase,
            // 충전소는 시뮬레이터가 들고 있는 출발점 기준 값을 먼저 쓴다(공공데이터 충전소도 수락할 때 올라가 있다).
            accepted = m.accepted?.let { r ->
                val routeStation = scenario.stations.firstOrNull { it.id == r.station.id }
                SavedRec.of(r, routeStation?.let { SavedStation.of(it, 0.0) } ?: SavedStation.of(r.station, sim.traveledKm))
            },
            charges = m.charges.map { SavedCharge.of(it.first, it.second) },
            handled = gate.handledIds.filter { !(awaiting && it == offeredId) || it in gate.declinedIds },
            declined = gate.declinedIds.toList(),
            noSafeWarned = m.noSafeWarned,
            arrivedAtSec = m.arrivedAtSec,
            endedByLimit = m.endedByLimit,
            charge = m.charge?.let { SavedChargeSession(it.targetSoc, it.startSec, it.fromSoc, it.firedAlerts.toList()) },
            odometerKm = m.odometerKm,
            elevationSoc = m.elevationSoc,
            preconditionAdvised = m.preconditionAdvised,
            manualSoc = manualSoc
        )
    }

    /** 저장본을 읽는다. 형식이 다르거나 버전이 맞지 않으면 null(컨트롤러가 지운다). */
    fun decode(text: String): SavedTrip? =
        runCatching { json.decodeFromString(SavedTrip.serializer(), text) }.getOrNull()?.takeIf { it.version == SavedTrip.VERSION }

    /**
     * 저장본의 여행 기억을 [TripMemory]와 안내 기록에 되살린다. 수락한 충전소는 [scenario] 목록에서 다시 찾아 지금 위치 기준 거리로 바꾸고,
     * 시나리오에 없는 충전소(공공데이터)는 저장해 둔 정보로 되살려 [addStation]으로 시뮬레이터에 다시 올린다.
     */
    fun restoreMemory(saved: SavedTrip, scenario: Scenario, addStation: (ChargingStation) -> Unit): Recommendation? {
        m.reset()
        m.phase = saved.phase
        m.noSafeWarned = saved.noSafeWarned
        m.arrivedAtSec = saved.arrivedAtSec
        m.endedByLimit = saved.endedByLimit
        m.odometerKm = saved.odometerKm
        m.elevationSoc = saved.elevationSoc
        m.preconditionAdvised = saved.preconditionAdvised
        saved.charges.forEach { m.charges += it.toRecord() }
        gate.restore(saved.handled, saved.declined)
        m.accepted = saved.accepted?.let { r ->
            val st = scenario.stations.firstOrNull { it.id == r.stationId }
                ?: r.station?.toStation()?.also(addStation)
            st?.let { r.toRecommendation(it.copy(distanceAlongRouteKm = it.distanceAlongRouteKm - saved.sim.traveledKm)) }
        }
        return m.accepted
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
