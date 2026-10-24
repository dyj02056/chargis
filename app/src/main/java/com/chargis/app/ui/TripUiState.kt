package com.chargis.app.ui

import com.chargis.app.domain.model.DataSource
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.session.TripPhase

/** 주행 중 화면에 올라가는 카드 1장(plan.md §9.2). */
sealed interface DriveCard {
    data class Recommend(val rec: Recommendation) : DriveCard
    data class Congested(val primary: Recommendation, val alternative: Recommendation) : DriveCard
    data class Navigating(
        val stationName: String,
        val distanceKm: Double,
        val targetSoc: Double,
        val arrivalSoc: Double,
        val chargeMinutes: Int,
        val timeLimitMin: Int?
    ) : DriveCard
    /** [plannedName]/[plannedKm]: 충전이 필요하지만 아직 안내 전인 1순위 휴게소. */
    data class Status(
        val chargePlanned: Boolean,
        val plannedName: String? = null,
        val plannedKm: Double? = null,
        val plannedTarget: Double? = null,
        val plannedMinutes: Int? = null
    ) : DriveCard
    data class NoSafeStop(val nearestName: String?, val nearestKm: Double?) : DriveCard
}

data class StopInfo(
    val stationName: String,
    val currentSoc: Double,
    val targetSoc: Double,
    val minutesToTarget: Int,
    val timeLimitMin: Int?,
    val minutesToLimit: Int?,
    val departAt: String
)

/** 경로 띠에 찍히는 휴게소 표시. 지나친 곳도 지우지 않고 상태로 남긴다. */
enum class MarkState { AHEAD, TARGET, CHARGED, DECLINED, PASSED }

data class RouteMark(val id: String, val name: String, val fraction: Float, val state: MarkState)

data class ChargeRecord(
    val stationName: String,
    val fromSoc: Double,
    val toSoc: Double,
    val minutes: Int
)

/** 도착 시 인쇄하는 정산 영수증. */
data class TripReceipt(
    val departAt: String,
    val startSoc: Double,
    val arriveAt: String?,
    val endSoc: Double?,
    val distanceKm: Double,
    val charges: List<ChargeRecord>,
    val declinedNames: List<String>
)

data class ScenarioOption(
    val id: String,
    val title: String,
    val distanceKm: Double,
    val startSoc: Double,
    val departAt: String
)

data class DevState(
    val clock: String = "",
    val traveledKm: Double = 0.0,
    val speedMultiplier: Int = 1,
    val paused: Boolean = false
)

data class TripUiState(
    val phase: TripPhase = TripPhase.Idle,
    val isDriving: Boolean = false,
    val socPercent: Double = 0.0,
    val dataSource: DataSource = DataSource.SIMULATED,
    val remainingKm: Double = 0.0,
    val scenarioTitle: String = "",
    val clock: String = "",
    val eta: String = "",
    /** 표 번호: 시나리오 + 발권 시각(예: S1-0900). */
    val ticketNo: String = "",
    val departAt: String = "",
    val card: DriveCard = DriveCard.Status(chargePlanned = false),
    val stop: StopInfo? = null,
    val candidates: List<Recommendation> = emptyList(),
    val routeMarks: List<RouteMark> = emptyList(),
    val progress: Float = 0f,
    val receipt: TripReceipt? = null,
    val scenarios: List<ScenarioOption> = emptyList(),
    val dev: DevState = DevState(),
    val caption: String? = null
)
