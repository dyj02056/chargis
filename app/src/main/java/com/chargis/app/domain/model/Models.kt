package com.chargis.app.domain.model

/*
 * 앱 전체에서 쓰는 "데이터 모양"을 모아 둔 파일이다. 일종의 용어 사전이라고 보면 된다.
 *
 * - data class: 값 몇 개를 한 묶음으로 담는 상자. 예) 위도+경도 = GeoPoint
 * - enum class: 정해진 선택지 중 하나만 고르는 값. 예) 데이터 출처는 SDK/MANUAL/SIMULATED 중 하나
 * - Double?, Int?: 물음표가 붙으면 "값이 없을 수도 있음(null)"이라는 뜻이다.
 *
 * 자주 나오는 단위
 * - SoC(State of Charge): 배터리 잔량을 %로 나타낸 값. 0이면 방전, 100이면 완충.
 * - kWh: 배터리에 담긴 에너지의 양(물통 크기). kW: 충전 속도(수도꼭지 세기).
 * - 전비(km/kWh): 1kWh로 몇 km를 가는지. 내연기관차의 연비와 같은 개념.
 */

/** 지도 위의 한 점. lat = 위도, lon = 경도. */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * 차종마다 정해진 고정 사양. 주행 중에 바뀌지 않는다.
 * 예) 배터리 77.4kWh, 전비 5km/kWh, 최대 150kW까지 충전 가능한 차
 */
data class VehicleProfile(
    val id: String,
    val displayName: String,
    val batteryKwh: Double,          // 사용 가능 용량
    val efficiencyKmPerKwh: Double,  // 평균 전비
    val maxChargeKw: Double,         // 차량 최대 수용 출력
    /**
     * 차종별 충전 곡선: SoC(%)마다 차가 받아들이는 최대 출력(kW). SoC 오름차순.
     * 예) [(10, 150), (50, 120), (80, 60), (100, 15)] → 사이 값은 직선으로 잇는다.
     * null이면 "80%까지 평균 출력, 그 위는 감쇠" 두 구간 모델을 쓴다(ChargeMath 참고).
     */
    val chargeCurve: List<CurvePoint>? = null
) {
    /**
     * 실제 소비가 평소보다 [factor]배 많을 때의 사양. 예) 겨울이라 1.2배 → 전비가 1/1.2로 떨어진다.
     * 엔진 계산식은 그대로 두고 전비만 바꿔 끼우기 위한 도우미다.
     */
    fun withConsumptionFactor(factor: Double): VehicleProfile =
        if (factor == 1.0) this else copy(efficiencyKmPerKwh = efficiencyKmPerKwh / factor)
}

/** 충전 곡선의 한 점: 이 SoC(%)에서 차가 받는 최대 출력(kW). */
data class CurvePoint(val socPercent: Double, val kw: Double)

/**
 * 차량 데이터를 어디서 받았는지 표시한다.
 * SDK = 실제 차량에서 읽음, MANUAL = 사용자가 직접 입력, SIMULATED = 데모용 가짜 값
 */
enum class DataSource { SDK, MANUAL, SIMULATED }

/** 지금 이 순간의 차 상태. 주행하면서 계속 바뀐다. */
data class VehicleState(
    val socPercent: Double,          // 0..100
    val rangeKm: Double?,            // 주행가능거리(없으면 null)
    val isDriving: Boolean,          // 주행/정차 구분 (SDK 불가 시 시뮬레이터/수동)
    val source: DataSource,
    val outsideTempC: Double? = null,    // 바깥 기온(°C). 추울수록 전비가 나빠진다. 없으면 null
    val chargePowerKw: Double? = null,   // 충전 중 실제 들어오는 출력(kW). 충전 중이 아니거나 모르면 null
    /** 누적 주행거리(km). 배터리 값과 같은 순간에 읽은 거리라 전비 실측(B3)에 쓴다. 모르면 null */
    val odometerKm: Double? = null
)

/** 내비게이션이 알려 주는 현재 경로 정보. 목적지까지 남은 거리와 시간을 담는다. */
data class RouteState(
    val current: GeoPoint,
    val destination: GeoPoint?,
    val remainingDistanceKm: Double,
    val remainingTimeMin: Int?
)

/**
 * 충전소의 운영 규칙. 급속충전기에는 "1회 40분" 같은 시간 제한이 걸려 있기도 하다(운영사·기기마다 다르다).
 * 제한 시간이 지나면 충전이 자동으로 끊기므로, 앱이 미리 경고해야 한다(F3 기능).
 */
data class ChargerRule(
    val timeLimitMin: Int?,          // null = 제한 없음 또는 미상
    val amountLimitKrw: Int?,        // 1회 충전 금액 제한(원). null = 없음 또는 미상
    val note: String? = null         // 화면에 그대로 보여 줄 추가 안내 문구
)

/**
 * 충전소가 있는 시설의 종류. 공공데이터의 시설 구분 코드(kindDetail)로 정한다.
 * 고속도로 휴게소와 졸음쉼터는 본선에서 바로 들어가고, 나머지는 보통 IC나 교차로로 경로를 벗어나야 한다.
 * 경로를 얼마나 벗어나는지는 종류가 아니라 [ChargingStation.detourKm]으로 판단한다.
 */
enum class StationKind {
    HIGHWAY_REST_STOP, // 고속도로 휴게소
    LOCAL_REST_STOP,   // 국도·지방도 휴게소
    SHELTER,           // 졸음쉼터 등 쉼터
    COMMERCIAL,        // 마트·주유소·음식점·숙박 등 상업시설
    PUBLIC,            // 관공서·공영주차장 등 공공시설
    OTHER;             // 관광지·공연장 등 그 밖의 시설

    /** 휴게소·쉼터처럼 이동 중에 들르려고 만든 곳인지. 아니면 엔진이 대안 후보로만 쓰도록 가산점을 붙인다. */
    val isRoadside: Boolean get() = this == HIGHWAY_REST_STOP || this == LOCAL_REST_STOP || this == SHELTER
}

/**
 * 하루 중 이용 가능 시간(0시부터 분). 예) 09:00~22:00 → OpenHours(540, 1320).
 * [closeMin]이 [openMin]보다 작으면 자정을 넘기는 시간대다. 예) 18:00~02:00
 */
data class OpenHours(val openMin: Int, val closeMin: Int) {
    /** [startMin]부터 [endMin]까지(0시부터 분, 1440을 넘어도 된다) 계속 열려 있는지. */
    fun covers(startMin: Int, endMin: Int): Boolean {
        if (this == ALL_DAY) return true
        if (endMin - startMin >= DAY) return false
        val s = Math.floorMod(startMin, DAY)
        val e = s + (endMin - startMin)
        val close = if (closeMin <= openMin) closeMin + DAY else closeMin
        // 시작이 오늘 열린 시간대 안이거나, 어제 열려 자정을 넘긴 시간대 안이면 된다.
        return (s >= openMin && e <= close) || (s + DAY >= openMin && e + DAY <= close)
    }

    companion object {
        private const val DAY = 24 * 60
        val ALL_DAY = OpenHours(0, DAY)
        private val HOURS = Regex("""(\d{1,2})\s*(?::\s*(\d{2})|시)\s*~\s*(\d{1,2})\s*(?::\s*(\d{2})|시)""")

        /** "24시간 이용가능" → 하루 종일, "09:00~22:00"·"9시~22시" → 그 시간대. 알아볼 수 없으면 null(미상). */
        fun parse(text: String?): OpenHours? {
            if (text.isNullOrBlank()) return null
            if (text.contains("24시간")) return ALL_DAY
            val m = HOURS.find(text) ?: return null
            val (h1, m1, h2, m2) = m.destructured
            val open = h1.toInt() * 60 + (m1.toIntOrNull() ?: 0)
            val close = h2.toInt() * 60 + (m2.toIntOrNull() ?: 0)
            if (open !in 0..DAY || close !in 0..DAY) return null
            return if ((open == 0 && close == DAY) || open == close) ALL_DAY else OpenHours(open % DAY, close % DAY)
        }
    }
}

/** 경로 위에 있는 충전소 한 곳의 정보. 추천 엔진이 이 목록을 비교해 1순위를 고른다. */
data class ChargingStation(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val kind: StationKind,            // 시설 종류(휴게소, 쉼터, 상업시설 등)
    val distanceAlongRouteKm: Double, // 현재 위치 기준 경로상 거리(경로에서 빠지는 지점까지)
    val detourKm: Double,             // 경로에서 충전소까지 편도 거리. 다시 경로로 돌아오므로 엔진은 왕복으로 센다(휴게소는 0)
    val maxKw: Double,                // 해당 소의 최대 출력
    val totalChargers: Int,           // 전체 충전기 대수
    val availableChargers: Int?,      // null = 실시간 미상
    val expectedWaitMin: Int?,        // 지금 이 순간의 대기(실시간). null = 미상
    val rule: ChargerRule,
    /**
     * 시간대별 평소 대기(분) 24칸. 0번 = 0시대, 13번 = 13시대. null = 통계 없음.
     * 도착 시각의 대기를 예측할 때 실시간 값과 섞어 쓴다(WaitForecast).
     */
    val waitByHour: List<Int>? = null,
    val openHours: OpenHours? = null, // 이용 가능 시간. null = 미상(엔진은 열려 있다고 본다)
    val parkingFree: Boolean? = null  // 주차 무료 여부. null = 미상
) {
    /** 휴게소(고속도로·국도)인지. 화면 문구를 고를 때 쓴다. */
    val isRestStop: Boolean get() = kind == StationKind.HIGHWAY_REST_STOP || kind == StationKind.LOCAL_REST_STOP
}

/** 충전소의 실시간 상황(빈 충전기 수, 대기 시간). 주기적으로 새 값이 들어온다. */
data class StationLiveStatus(
    val stationId: String,
    val availableChargers: Int?,
    val expectedWaitMin: Int?
)

/**
 * 추천 계산에 쓰는 기준값 모음. 숫자를 코드 곳곳에 흩어 두지 않고 여기 한 곳에서 바꿀 수 있게 했다.
 * 괄호 안 "가정"이라고 적힌 값은 실제 차량 데이터로 다듬어야 하는 추정치다.
 */
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
    // ---- 총 소요 시간 비교(B1) ----
    val defaultSpeedKmh: Double = 100.0,      // 내비가 남은 시간을 주지 않을 때 쓰는 평균 속도
    val stopOverheadMin: Double = 3.0,        // 한 번 들를 때마다 드는 시간(진출입·주차·결제) (가정)
    // ---- 휴게소 밖 충전소 ----
    val offRouteKm: Double = 0.5,             // 편도 우회가 이보다 길면 "경로를 벗어나는" 충전소로 본다
    val offRouteAccessMin: Double = 5.0,      // 경로를 벗어날 때 더 드는 시간(IC 요금소·신호·주차장 찾기) (가정)
    val nonRoadsidePenaltyMin: Double = 5.0,  // 휴게소·쉼터가 아닌 곳에 더하는 순위 가산(분). 휴게소를 먼저 고르게 한다
    val minOpenAfterArrivalMin: Int = 30,     // 도착 후 이만큼은 열려 있어야 후보로 둔다(이용 가능 시간을 알 때만)
    val unknownWaitMin: Int = 10,             // 대기를 전혀 모를 때 셈에 넣는 대기 (가정)
    val comfortArrivalSocPercent: Double = 20.0, // 도착 잔량이 이보다 낮으면 불안 비용을 더한다
    val lowSocPenaltyMinPerPercent: Double = 0.5, // 위 기준에서 1% 모자랄 때마다 더하는 분
    val limitRiskPenaltyMin: Double = 15.0,   // 시간 제한 안에 목표를 못 채우는 계획에 더하는 분
    val unreachablePenaltyMin: Double = 60.0, // 두 번 들러도 목적지까지 못 가는 계획에 더하는 분
    val switchMinutesGap: Double = 5.0,       // 수락한 충전소를 바꾸려면 이만큼 빨라야 한다(히스테리시스)
    // ---- 충전 곡선(B3) ----
    val stationDeliveryFactor: Double = 0.92, // 충전기가 표시 출력의 몇 %를 실제로 내주는지 (가정)
    // ---- 대기 예측(B4) ----
    val liveWaitHalfLifeMin: Double = 45.0    // 실시간 대기를 믿는 정도가 반으로 줄어드는 시간
)

/**
 * 추천 한 번에 쓰는 "지금 상황" 값. 엔진이 시계나 센서를 직접 읽지 않도록 밖에서 넣어 준다.
 * [minuteOfDay]: 지금 시각(0시부터 분). 도착 시각의 대기를 예측하는 데 쓴다. 모르면 null.
 * [consumptionFactor]: 실제 소비 ÷ 사양 소비. 1.2면 사양보다 20% 더 쓴다(ConsumptionCalibrator).
 */
data class RecommendContext(
    val minuteOfDay: Int? = null,
    val consumptionFactor: Double = 1.0
)

/** 대기 시간 값을 어디서 얻었는지. 화면에 "예측" 같은 꼬리표를 붙일 때 쓴다. */
enum class WaitSource { LIVE, BLENDED, FORECAST, UNKNOWN }

/** 두 번째로 들를 충전소 계획(B2). 첫 번째 충전소에서 출발한 뒤 다시 추천 엔진이 확인한다. */
data class PlannedStop(
    val station: ChargingStation,
    val arrivalSocPercent: Double,
    val targetSocPercent: Double,
    val chargeMinutes: Int,
    val waitMinutes: Int
)

/**
 * 추천 엔진이 충전소 한 곳을 계산한 결과.
 * "B 휴게소에 도착하면 25%, 50%까지 충전하면 12분" 같은 안내 문장이 모두 여기서 나온다.
 */
data class Recommendation(
    val station: ChargingStation,
    val arrivalSocPercent: Double,            // 이 충전소에 도착했을 때 예상 배터리 %
    val targetSocPercent: Double,             // "OO%까지 충전하세요"의 OO
    val chargeMinutes: Int,                   // 목표까지 충전하는 데 걸리는 분
    val waitMinutes: Int,                     // 도착했을 때 예상 대기(분). 모르면 0
    val timeLimitMin: Int?,                   // 충전소 시간 제한(분)
    val willHitTimeLimit: Boolean,            // 목표까지 충전하기 전에 시간 제한에 걸리는지
    val socAtTimeLimitPercent: Double?,       // 제한 시각에 도달 가능한 SoC
    val needsAnotherStop: Boolean,            // 이 충전소 다음에 한 번 더 들러야 한다(또는 그래도 못 감)
    val reasonCodes: List<ReasonCode>,        // 화면에 보여 줄 "추천 이유" 배지 목록
    val waitSource: WaitSource = WaitSource.UNKNOWN, // 대기 값의 출처(실시간/예측)
    val etaMinutes: Double = 0.0,             // 지금부터 목적지 도착까지 걸리는 분(이 계획대로 갈 때)
    val costMinutes: Double = 0.0,            // 순위용 비용(분) = etaMinutes + 위험 가산. 낮을수록 좋다
    val nextStop: PlannedStop? = null         // 두 번 들르는 계획이면 두 번째 충전소
)

/**
 * 추천 이유 배지의 종류.
 * FASTEST_ARRIVAL = 후보 중 목적지에 가장 빨리 도착, MAX_POWER = 후보 중 가장 빠른 충전, LOW_WAIT = 대기가 짧음, ENOUGH_CHARGERS = 빈 충전기 넉넉,
 * LATEST_SAFE_STOP = 안전하게 갈 수 있는 마지막 충전소, NO_DETOUR = 경로 이탈 없음,
 * TIME_LIMIT_RISK = 시간 제한 안에 목표까지 못 채울 위험
 */
enum class ReasonCode { FASTEST_ARRIVAL, MAX_POWER, LOW_WAIT, ENOUGH_CHARGERS, LATEST_SAFE_STOP, NO_DETOUR, TIME_LIMIT_RISK }

/**
 * 추천 엔진의 최종 답. 세 가지 경우 중 정확히 하나가 나온다.
 * (sealed interface = "이 셋 말고 다른 경우는 없다"고 컴파일러에게 약속하는 문법)
 */
sealed interface RecommendationResult {
    /** 현재 SoC로 목적지까지 충분하다. */
    data object NotNeeded : RecommendationResult

    /** 도착 잔량 조건을 만족하는 충전소가 없다. [nearest]는 경고용으로 가장 가까운 충전소(없으면 null). */
    data class NoSafeStop(val nearest: ChargingStation?) : RecommendationResult

    /** 충전소를 추천한다. 1순위([primary])와 대안 몇 곳, 혼잡 여부를 함께 담는다. */
    data class Recommended(
        val primary: Recommendation,
        val alternatives: List<Recommendation>,
        val congested: Boolean,              // 붐비지 않았다면 골랐을 1순위가 붐벼서 대안 비교 화면을 띄워야 하는지
        val latestSafeStopId: String         // 늦어도 여기서는 충전해야 하는 충전소의 id
    ) : RecommendationResult
}

/** 음성 안내의 우선순위. HIGH는 다른 안내를 끊고 먼저 말한다(예: 시간 제한 경고). */
enum class Priority { LOW, NORMAL, HIGH }

/** 운전자가 말로 할 수 있는 명령. 수락 / 다음 후보 / 충전 시작 / 출발 */
enum class VoiceCommand { ACCEPT, NEXT_STOP, START_CHARGING, DEPART }
