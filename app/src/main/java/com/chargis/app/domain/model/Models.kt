package com.chargis.app.domain.model

data class GeoPoint(val lat: Double, val lon: Double)

data class VehicleProfile(
    val id: String,
    val displayName: String,
    val batteryKwh: Double,          // 사용 가능 용량
    val efficiencyKmPerKwh: Double,  // 평균 전비
    val maxChargeKw: Double          // 차량 최대 수용 출력
)

enum class DataSource { SDK, MANUAL, SIMULATED }

data class VehicleState(
    val socPercent: Double,          // 0..100
    val rangeKm: Double?,            // 주행가능거리(없으면 null)
    val isDriving: Boolean,          // 주행/정차 구분 (SDK 불가 시 시뮬레이터/수동)
    val source: DataSource
)

data class RouteState(
    val current: GeoPoint,
    val destination: GeoPoint?,
    val remainingDistanceKm: Double,
    val remainingTimeMin: Int?
)

data class ChargerRule(
    val timeLimitMin: Int?,          // null = 제한 없음 또는 미상
    val amountLimitKrw: Int?,
    val note: String? = null
)

data class ChargingStation(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val isRestStop: Boolean,
    val distanceAlongRouteKm: Double, // 현재 위치 기준 경로상 거리
    val detourKm: Double,             // 경로 이탈 추가 거리(휴게소는 보통 0~소량)
    val maxKw: Double,                // 해당 소의 최대 출력
    val totalChargers: Int,
    val availableChargers: Int?,      // null = 실시간 미상
    val expectedWaitMin: Int?,        // 실시간 or 시간대별 통계 기반 추정
    val rule: ChargerRule
)

data class StationLiveStatus(
    val stationId: String,
    val availableChargers: Int?,
    val expectedWaitMin: Int?
)

data class ScoreWeights(
    val arrivalSoc: Double = 0.25,
    val power: Double = 0.25,
    val availability: Double = 0.20,
    val detour: Double = 0.10,
    val wait: Double = 0.20
)

data class RecommendationConfig(
    val safetyMargin: Double = 0.15,          // 필요 에너지에 곱할 (1+margin)
    val minArrivalSocPercent: Double = 10.0,  // 도착 시 최소 잔량
    val maxTargetSocPercent: Double = 80.0,   // 기본 권장 상한(사용자 조정 가능)
    val roundUpStepPercent: Double = 5.0,     // 목표 %를 5% 단위로 올림
    val taperStartSocPercent: Double = 80.0,  // 이 SoC부터 충전 출력 감쇠 (배터리 특성, 사용자 상한과 별개)
    val avgPowerFactor: Double = 0.75,        // ≤80% 구간 평균 출력 계수 (가정, 튜닝 대상)
    val taperFactorAboveCap: Double = 0.35,   // >80% 구간 추가 감쇠 계수 (가정)
    val announceWithinKm: Double = 50.0,      // 추천 지점 N km 전 안내
    val congestionWaitThresholdMin: Int = 20, // 1순위 대기가 이보다 길면 대안 비교 카드
    val weights: ScoreWeights = ScoreWeights()
)

data class Recommendation(
    val station: ChargingStation,
    val arrivalSocPercent: Double,
    val targetSocPercent: Double,
    val chargeMinutes: Int,
    val waitMinutes: Int,
    val timeLimitMin: Int?,
    val willHitTimeLimit: Boolean,
    val socAtTimeLimitPercent: Double?,       // 제한 시각에 도달 가능한 SoC
    val needsAnotherStop: Boolean,            // 목표(상한) 충전으로도 목적지 미도달
    val score: Double,
    val reasonCodes: List<ReasonCode>
)

enum class ReasonCode { MAX_POWER, LOW_WAIT, ENOUGH_CHARGERS, LATEST_SAFE_STOP, NO_DETOUR, TIME_LIMIT_RISK }

sealed interface RecommendationResult {
    /** 현재 SoC로 목적지까지 충분하다. */
    data object NotNeeded : RecommendationResult

    /** 도착 잔량 조건을 만족하는 충전소가 없다. [nearest]는 경고용으로 가장 가까운 충전소(없으면 null). */
    data class NoSafeStop(val nearest: ChargingStation?) : RecommendationResult

    data class Recommended(
        val primary: Recommendation,
        val alternatives: List<Recommendation>,
        val congested: Boolean,
        val latestSafeStopId: String
    ) : RecommendationResult
}

enum class Priority { LOW, NORMAL, HIGH }

enum class VoiceCommand { ACCEPT, NEXT_STOP, START_CHARGING, DEPART }
