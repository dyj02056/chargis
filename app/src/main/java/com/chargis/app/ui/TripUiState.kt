package com.chargis.app.ui

import com.chargis.app.domain.model.DataSource
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.model.UserSettings
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
    /**
     * "46km 앞 B 휴게소, 50%까지 충전" 추천 카드 (docs/screenshots/03-recommend.png)
     * [etaClock]: 이 계획대로 가면 목적지 도착 시각. [fasterThanNextMin]: 다음 후보보다 몇 분 빨리 도착하는지(없으면 null)
     */
    data class Recommend(
        val rec: Recommendation,
        val etaClock: String = "",
        val fasterThanNextMin: Int? = null
    ) : DriveCard
    /**
     * 붐비지 않았다면 골랐을 곳이 붐빌 때 대안과 나란히 비교하는 카드 (10-congested.png)
     * [primaryEta]/[alternativeEta]: 각각 그곳에서 충전하면 목적지에 도착하는 시각
     */
    data class Congested(
        val primary: Recommendation,
        val alternative: Recommendation,
        val primaryEta: String = "",
        val alternativeEta: String = ""
    ) : DriveCard
    /** 수락한 충전소로 가는 중 (04-navigating.png) */
    data class Navigating(
        val stationName: String,
        val distanceKm: Double,
        val targetSoc: Double,
        val arrivalSoc: Double,
        val chargeMinutes: Int,
        val timeLimitMin: Int?,
        val timeLimitKnown: Boolean = true,  // false면 제한 칸에 "없음" 대신 "정보 없음"
        /** 배터리가 차가워 더 걸리는 충전 시간(분). 예열 안내에 쓴다 */
        val coldExtraMinutes: Int = 0,
        /** 예열 상태: true 켜짐, false 꺼짐, null 모름 */
        val preconditioning: Boolean? = null,
        /** 예열을 권할 때인지(차가 지원하고, 차가운 배터리로 충전이 눈에 띄게 늦어지고, 도착까지 30분 이내) */
        val suggestPreconditioning: Boolean = false,
        /** 앱에서 예열을 켤 수 있는지. false면 버튼 없이 "차량 화면에서 켜면" 안내만 */
        val canStartPreconditioning: Boolean = true
    ) : DriveCard
    /**
     * 평소 주행 상태(남은 거리, 충전 계획) (02-drive-status.png)
     * [plannedName]/[plannedKm]: 충전이 필요하지만 아직 안내 전인 1순위 충전소.
     */
    data class Status(
        val chargePlanned: Boolean,
        val plannedName: String? = null,
        val plannedKm: Double? = null,
        val plannedTarget: Double? = null,
        val plannedMinutes: Int? = null,
        /** 두 번 들르는 계획이면 두 번째 충전소 이름과 목표 %(B2) */
        val nextStopName: String? = null,
        val nextStopTarget: Double? = null
    ) : DriveCard
    /** 안전하게 갈 수 있는 충전소가 없다는 경고 카드 */
    data class NoSafeStop(val nearestName: String?, val nearestKm: Double?) : DriveCard
}

/** 충전소에 서 있을 때(도착·충전 중·완료) 화면에 보여 줄 숫자들 (05~07 스크린샷) */
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
    val endedByLimit: Boolean = false,
    /** 지금 들어오는 출력(kW). 충전 중이 아니거나 모르면 null (F2) */
    val chargePowerKw: Double? = null,
    /** 이 충전기·차종의 예상 출력 곡선: SoC 0~100%마다 kW (F2 그래프) */
    val powerCurve: List<CurveSample> = emptyList(),
    /** 목표를 5% 올리거나 내릴 수 있는지 (F2) */
    val canRaiseTarget: Boolean = false,
    val canLowerTarget: Boolean = false,
    /** 목표가 80%를 넘어 충전이 크게 느려지는 구간에 들어가는지 (F2 경고) */
    val targetInTaper: Boolean = false,
    /** 두 번 들르는 계획이면 다음 충전소 이름과 목표 %(B2) */
    val nextStopName: String? = null,
    val nextStopTarget: Double? = null,
    /** 시간 제한 정보를 확인했는지. false면 제한 칸에 "정보 없음"(제한 없음과 구분) */
    val timeLimitKnown: Boolean = true,
    /** 지금 %에서 목표까지 충전하는 예상 요금(원). 요금을 모르면 null */
    val costKrw: Int? = null,
    /** 배터리 %를 운전자가 직접 넣는 중인지. true면 값을 고치는 버튼을 보여 준다 */
    val manualSoc: Boolean = false,
    /** 배터리가 차가워 충전이 늦어지는 중인지(예상 출력 곡선이 평소보다 낮다) */
    val coldBattery: Boolean = false
)

/** 출력 곡선 그래프의 한 점 */
data class CurveSample(val socPercent: Float, val kw: Float)

/**
 * 경로 띠에 찍히는 충전소 표시. 지나친 곳도 지우지 않고 상태로 남긴다.
 * AHEAD = 앞에 있음, TARGET = 지금 향하는 곳, CHARGED = 충전함, DECLINED = 거절함, PASSED = 지나침
 */
enum class MarkState { AHEAD, TARGET, CHARGED, DECLINED, PASSED }

/** 경로 띠 위 충전소 하나. [fraction]은 전체 경로 중 위치(0 = 출발, 1 = 도착). */
data class RouteMark(val id: String, val name: String, val fraction: Float, val state: MarkState)

/** 충전 한 번의 기록. 영수증에 한 줄로 찍힌다. */
data class ChargeRecord(
    val stationName: String,
    val fromSoc: Double,
    val toSoc: Double,
    val minutes: Int,
    val endedByLimit: Boolean = false,
    /** 같은 충전기에서 습관처럼 80%(또는 이번에 채운 %)까지 채웠다면 걸렸을 분 (F4 비교용) */
    val baselineMinutes: Int = minutes,
    /** 이번 충전 요금(원). 충전소 요금을 모르면 null */
    val costKrw: Int? = null
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
) {
    /** 이번 여행에서 충전에 쓴 분 */
    val chargeMinutesTotal: Int get() = charges.sumOf { it.minutes }
    /** "80%까지 채웠다면"보다 아낀 분(F4). 음수가 되지 않게 0으로 묶는다. */
    val savedMinutes: Int get() = charges.sumOf { (it.baselineMinutes - it.minutes).coerceAtLeast(0) }
    /** 요금을 아는 충전의 요금 합(원). 아는 충전이 하나도 없으면 null */
    val costKrwTotal: Int? get() = charges.mapNotNull { it.costKrw }.takeIf { it.isNotEmpty() }?.sum()
    /** 요금을 모르는 충전이 섞여 있는지(합계 옆에 "일부 정보 없음"을 붙인다) */
    val costPartlyUnknown: Boolean get() = charges.any { it.costKrw == null } && costKrwTotal != null
}

/** 설정 화면에서 고를 차종 하나 */
data class ProfileOption(val id: String, val name: String, val batteryKwh: Double, val maxChargeKw: Double)

/**
 * 전비 보정 상태(B3). [factor]는 실제 소비 ÷ 사양 소비, [observedKm]는 실측한 거리.
 * 예) factor 1.2, observedKm 25 → "실측 25km · 사양보다 20% 더 씀"
 */
data class EnergyInfo(val factor: Double, val observedKm: Double)

/**
 * 음성 명령 상태(F5). [enabled]: 설정에서 켰는지, [listening]: 지금 말을 듣는 중인지,
 * [hints]: 지금 말할 수 있는 명령(예: "수락", "다음")
 */
data class VoiceUi(val enabled: Boolean = false, val listening: Boolean = false, val hints: List<String> = emptyList())

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
    val caption: String? = null,                // 화면 아래 음성 안내 자막
    val energy: EnergyInfo? = null,             // 실측 전비 보정 상태(B3)
    val settings: UserSettings = UserSettings(),// 운전자 설정(F5)
    val profiles: List<ProfileOption> = emptyList(),
    val profileName: String = "",               // 지금 고른 차종 이름
    val voice: VoiceUi = VoiceUi(),             // 음성 명령 상태(F5)
    val uxRestricted: Boolean = false,          // 차가 움직여 주행 중 화면 제한이 걸렸는지(CarUxRestrictions, B6). 이때 정차 화면은 가린다
    val restored: Boolean = false,              // 저장해 둔 여행을 이어서 보여 주는 중인지(B5)
    val loading: Boolean = true                 // 저장해 둔 여행을 아직 불러오는 중인지. 그동안은 발권 화면을 그리지 않는다
)
