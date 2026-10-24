package com.chargis.app.ui

import com.chargis.app.domain.model.ChargerRule
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.OpenHours
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.model.RecommendationResult
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.StationKind
import com.chargis.app.domain.model.VehicleState
import com.chargis.app.domain.model.WaitSource
import com.chargis.app.domain.session.ChargeSession
import com.chargis.app.domain.session.TripPhase
import com.chargis.app.sim.SimPhase
import com.chargis.app.sim.SimState
import kotlinx.serialization.Serializable

/**
 * 여행 한 번 동안 기억해 두는 값들(B5에서 TripController로부터 분리).
 * 컨트롤러는 이 값을 바꾸고, [TripPresenter]는 이 값을 읽어 화면 상태를 만들고,
 * [SavedTrip]은 이 값을 기기에 저장한다. 세 역할이 같은 상자를 공유한다.
 */
class TripMemory {
    var phase = TripPhase.Idle                                // 지금 여행 단계
    // offer = 안내했지만 아직 답을 받지 못한 추천, accepted = 운전자가 수락한 추천
    var offer: RecommendationResult.Recommended? = null
    var accepted: Recommendation? = null
    var lastResult: RecommendationResult? = null
    var lastRoute: RouteState? = null
    var lastVehicle: VehicleState? = null
    var stations: List<ChargingStation> = emptyList()
    var noSafeWarned = false                                  // "갈 수 있는 충전소 없음" 경고를 이미 했는지
    val charges = mutableListOf<Pair<String, ChargeRecord>>() // stationId to record
    var arrivedAtSec: Double? = null                          // 목적지 도착 시각(영수증용)
    var charge: ChargeSession? = null                         // 지금 진행 중인 충전(§8)
    var endedByLimit = false
    var odometerKm = 0.0                                      // 여행 시작 후 달린 거리(전비 실측용, B3)
    var lastEval: EvalPoint? = null                           // 마지막으로 추천을 새로 계산한 시점(계산 간격 조절)

    /** 새 여행을 위해 모두 처음으로 돌린다. */
    fun reset() {
        phase = TripPhase.Idle
        offer = null
        accepted = null
        lastResult = null
        lastEval = null
        noSafeWarned = false
        charges.clear()
        arrivedAtSec = null
        charge = null
        endedByLimit = false
        odometerKm = 0.0
    }
}

/**
 * 추천을 새로 계산한 시점의 기록. 그때의 남은 거리·가상 시각·거절 수와, 그때 받은 충전소 목록·추천 결과(그때 위치 기준 거리)를 담는다.
 * 다음 계산 전까지는 이 목록과 결과에서 달린 거리만 빼서 쓴다.
 */
class EvalPoint(
    val remainingKm: Double,
    val simSec: Double,
    val declinedCount: Int,
    val stations: List<ChargingStation>,
    val result: RecommendationResult
)

// ---- 저장 형식(B5) ----
// 기기에 JSON으로 저장하는 모양. 화면용 객체를 그대로 저장하지 않고, 되살리는 데 꼭 필요한 값만 담는다.
// version을 두어 나중에 형식이 바뀌면 옛 저장본을 무시할 수 있게 한다.

@Serializable
data class SavedTrip(
    val version: Int = VERSION,
    val scenarioId: String,
    val profileId: String,
    val sim: SavedSim,
    val phase: TripPhase,
    val accepted: SavedRec? = null,
    val charges: List<SavedCharge> = emptyList(),
    val handled: List<String> = emptyList(),
    val declined: List<String> = emptyList(),
    val noSafeWarned: Boolean = false,
    val arrivedAtSec: Double? = null,
    val endedByLimit: Boolean = false,
    val charge: SavedChargeSession? = null,
    val odometerKm: Double = 0.0
) {
    companion object {
        const val VERSION = 1
    }
}

@Serializable
data class SavedSim(
    val phase: SimPhase,
    val traveledKm: Double,
    val soc: Double,
    val simTimeSec: Double,
    val waypointId: String? = null,
    val stoppedAtId: String? = null,
    val chargeTargetSoc: Double? = null,
    val chargeEndsAtSec: Double? = null
) {
    fun toState(scenarioId: String, speed: Int) = SimState(
        scenarioId = scenarioId, phase = phase, traveledKm = traveledKm, soc = soc, simTimeSec = simTimeSec,
        speedMultiplier = speed, paused = false, waypointId = waypointId, stoppedAtId = stoppedAtId,
        chargeTargetSoc = chargeTargetSoc, chargeEndsAtSec = chargeEndsAtSec
    )

    companion object {
        fun of(s: SimState) = SavedSim(
            s.phase, s.traveledKm, s.soc, s.simTimeSec, s.waypointId, s.stoppedAtId, s.chargeTargetSoc, s.chargeEndsAtSec
        )
    }
}

/**
 * 수락한 추천. 되살릴 때 충전소를 시나리오 목록에서 먼저 찾고, 없으면(공공데이터 충전소) [station]으로 되살린다.
 * [station]이 없는 옛 저장본은 시나리오 충전소만 되살린다.
 */
@Serializable
data class SavedRec(
    val stationId: String,
    val arrivalSoc: Double,
    val targetSoc: Double,
    val chargeMinutes: Int,
    val waitMinutes: Int,
    val timeLimitMin: Int? = null,
    val willHitTimeLimit: Boolean = false,
    val socAtTimeLimit: Double? = null,
    val needsAnotherStop: Boolean = false,
    val waitSource: WaitSource = WaitSource.UNKNOWN,
    val etaMinutes: Double = 0.0,
    val costMinutes: Double = 0.0,
    val station: SavedStation? = null
) {
    fun toRecommendation(station: ChargingStation) = Recommendation(
        station = station,
        arrivalSocPercent = arrivalSoc,
        targetSocPercent = targetSoc,
        chargeMinutes = chargeMinutes,
        waitMinutes = waitMinutes,
        timeLimitMin = timeLimitMin,
        willHitTimeLimit = willHitTimeLimit,
        socAtTimeLimitPercent = socAtTimeLimit,
        needsAnotherStop = needsAnotherStop,
        reasonCodes = emptyList(),
        waitSource = waitSource,
        etaMinutes = etaMinutes,
        costMinutes = costMinutes
    )

    companion object {
        /** [station]은 출발점 기준 거리로 바꾼 충전소 정보. */
        fun of(r: Recommendation, station: SavedStation) = SavedRec(
            r.station.id, r.arrivalSocPercent, r.targetSocPercent, r.chargeMinutes, r.waitMinutes, r.timeLimitMin,
            r.willHitTimeLimit, r.socAtTimeLimitPercent, r.needsAnotherStop, r.waitSource, r.etaMinutes, r.costMinutes,
            station
        )
    }
}

/** 충전소 한 곳(출발점 기준 거리). 시나리오에 없는 충전소를 되살릴 때 쓴다. */
@Serializable
data class SavedStation(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val kind: StationKind,
    val routeKm: Double,
    val detourKm: Double,
    val maxKw: Double,
    val totalChargers: Int,
    val timeLimitMin: Int? = null,
    val timeLimitKnown: Boolean = true,
    val note: String? = null,
    val openMin: Int? = null,
    val closeMin: Int? = null,
    val parkingFree: Boolean? = null
) {
    fun toStation() = ChargingStation(
        id = id,
        name = name,
        location = GeoPoint(lat, lon),
        kind = kind,
        distanceAlongRouteKm = routeKm,
        detourKm = detourKm,
        maxKw = maxKw,
        totalChargers = totalChargers,
        availableChargers = null,
        expectedWaitMin = null,
        rule = ChargerRule(timeLimitMin = timeLimitMin, amountLimitKrw = null, note = note, timeLimitKnown = timeLimitKnown),
        openHours = if (openMin != null && closeMin != null) OpenHours(openMin, closeMin) else null,
        parkingFree = parkingFree
    )

    companion object {
        fun of(s: ChargingStation, traveledKm: Double) = SavedStation(
            s.id, s.name, s.location.lat, s.location.lon, s.kind, traveledKm + s.distanceAlongRouteKm, s.detourKm,
            s.maxKw, s.totalChargers, s.rule.timeLimitMin, s.rule.timeLimitKnown, s.rule.note,
            s.openHours?.openMin, s.openHours?.closeMin, s.parkingFree
        )
    }
}

@Serializable
data class SavedCharge(
    val stationId: String,
    val stationName: String,
    val fromSoc: Double,
    val toSoc: Double,
    val minutes: Int,
    val endedByLimit: Boolean,
    val baselineMinutes: Int
) {
    fun toRecord() = stationId to ChargeRecord(stationName, fromSoc, toSoc, minutes, endedByLimit, baselineMinutes)

    companion object {
        fun of(id: String, r: ChargeRecord) = SavedCharge(id, r.stationName, r.fromSoc, r.toSoc, r.minutes, r.endedByLimit, r.baselineMinutes)
    }
}

@Serializable
data class SavedChargeSession(
    val targetSoc: Double,
    val startSec: Double,
    val fromSoc: Double,
    val fired: List<com.chargis.app.domain.session.AlertType> = emptyList()
)
