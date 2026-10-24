package com.chargis.app.ui

import com.chargis.app.domain.model.DataSource
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.session.TripPhase

/*
 * 화면에 그릴 내용을 담는 상자들이다. TripController가 값을 채우고, 화면 코드는 이 값을 읽어 그리기만 한다.
 * 계산과 그리기를 나눠 두면, 화면 디자인을 바꿔도 계산 로직은 건드리지 않아도 된다.
 */

/**
 * 주행 중 화면에 올라가는 카드 1장(plan.md §9.2).
 * 운전 중에는 한 번에 카드 하나만 보여 줘서 운전자가 한눈에 읽을 수 있게 한다.
 */
sealed interface DriveCard {
    /** "46km 앞 B 휴게소, 50%까지 충전" 추천 카드 (docs/screenshots/03-recommend.png) */
    data class Recommend(val rec: Recommendation) : DriveCard
    /** 1순위가 붐빌 때 대안과 나란히 비교하는 카드 (10-congested.png) */
    data class Congested(val primary: Recommendation, val alternative: Recommendation) : DriveCard
    /** 수락한 휴게소로 가는 중 (04-navigating.png) */
    data class Navigating(
        val stationName: String,
        val distanceKm: Double,
        val targetSoc: Double,
        val arrivalSoc: Double,
        val chargeMinutes: Int,
        val timeLimitMin: Int?
    ) : DriveCard
    /**
     * 평소 주행 상태(남은 거리, 충전 계획) (02-drive-status.png)
     * [plannedName]/[plannedKm]: 충전이 필요하지만 아직 안내 전인 1순위 휴게소.
     */
    data class Status(
        val chargePlanned: Boolean,
        val plannedName: String? = null,
        val plannedKm: Double? = null,
        val plannedTarget: Double? = null,
        val plannedMinutes: Int? = null
    ) : DriveCard
    /** 안전하게 갈 수 있는 충전소가 없다는 경고 카드 */
    data class NoSafeStop(val nearestName: String?, val nearestKm: Double?) : DriveCard
}

/** 휴게소에 서 있을 때(도착·충전 중·완료) 화면에 보여 줄 숫자들 (05~07 스크린샷) */
data class StopInfo(
    val stationName: String,
    val currentSoc: Double,
    val targetSoc: Double,
    val minutesToTarget: Int,
    val timeLimitMin: Int?,
    val minutesToLimit: Int?,
    val departAt: String,               // 출발 예정 시각 "14:20"
    /** 제한 시각에 도달할 것으로 보이는 SoC. 제한 없음이면 null. */
    val predictedSocAtLimit: Double? = null,
    /** 제한 안에 목표에 닿지 못할 것으로 보이면 true. */
    val willHitLimit: Boolean = false,
    /** 충전기 시간 제한으로 충전이 끝났으면 true(Done 화면용). */
    val endedByLimit: Boolean = false
)

/**
 * 경로 띠에 찍히는 휴게소 표시. 지나친 곳도 지우지 않고 상태로 남긴다.
 * AHEAD = 앞에 있음, TARGET = 지금 향하는 곳, CHARGED = 충전함, DECLINED = 거절함, PASSED = 지나침
 */
enum class MarkState { AHEAD, TARGET, CHARGED, DECLINED, PASSED }

/** 경로 띠 위 휴게소 하나. [fraction]은 전체 경로 중 위치(0 = 출발, 1 = 도착). */
data class RouteMark(val id: String, val name: String, val fraction: Float, val state: MarkState)

/** 충전 한 번의 기록. 영수증에 한 줄로 찍힌다. */
data class ChargeRecord(
    val stationName: String,
    val fromSoc: Double,
    val toSoc: Double,
    val minutes: Int,
    val endedByLimit: Boolean = false
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

/** 첫 화면에서 고를 수 있는 데모 시나리오 하나 */
data class ScenarioOption(
    val id: String,
    val title: String,
    val distanceKm: Double,
    val startSoc: Double,
    val departAt: String
)

/** 개발자 오버레이에 보여 줄 값(시연용 시계, 배속, 일시정지 여부) */
data class DevState(
    val clock: String = "",
    val traveledKm: Double = 0.0,
    val speedMultiplier: Int = 1,
    val paused: Boolean = false
)

/**
 * 화면 전체 상태. 이 값 하나만 있으면 지금 화면을 똑같이 다시 그릴 수 있다.
 * 모든 항목에 기본값이 있어서 앱을 막 켰을 때도 빈 화면을 그릴 수 있다.
 */
data class TripUiState(
    val phase: TripPhase = TripPhase.Idle,
    val isDriving: Boolean = false,             // true면 주행 화면, false면 정차 화면
    val socPercent: Double = 0.0,
    val dataSource: DataSource = DataSource.SIMULATED,
    val remainingKm: Double = 0.0,
    val scenarioTitle: String = "",
    val clock: String = "",                     // 지금 시각
    val eta: String = "",                       // 목적지 도착 예정 시각
    /** 표 번호: 시나리오 + 발권 시각(예: S1-0900). */
    val ticketNo: String = "",
    val departAt: String = "",
    val card: DriveCard = DriveCard.Status(chargePlanned = false),
    val stop: StopInfo? = null,
    val candidates: List<Recommendation> = emptyList(),  // 정차 화면 비교 표에 보여 줄 후보들
    val routeMarks: List<RouteMark> = emptyList(),
    val progress: Float = 0f,                   // 경로 진행률(0~1)
    val receipt: TripReceipt? = null,
    val scenarios: List<ScenarioOption> = emptyList(),
    val dev: DevState = DevState(),
    val caption: String? = null                 // 화면 아래 음성 안내 자막
)
