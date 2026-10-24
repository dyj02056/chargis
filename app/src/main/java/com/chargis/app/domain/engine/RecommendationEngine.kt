package com.chargis.app.domain.engine

import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.PlannedStop
import com.chargis.app.domain.model.ReasonCode
import com.chargis.app.domain.model.RecommendContext
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.domain.model.RecommendationResult
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.VehicleProfile
import com.chargis.app.domain.model.VehicleState
import com.chargis.app.domain.model.WaitSource
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * plan.md §7.2 경로상 충전소 추천(F1) + 목표 충전량(F2).
 * 무상태 순수 함수다. 재안내 금지/히스테리시스는 [AnnouncementGate]가 맡는다.
 *
 * 이 앱의 두뇌에 해당한다. 경로, 배터리, 충전소 목록을 넣으면 "어느 충전소에서 몇 %까지"를 답한다.
 * "무상태 순수 함수"란 같은 입력을 넣으면 언제나 같은 답이 나오고, 이전 호출을 기억하지 않는다는 뜻이다.
 * 덕분에 테스트하기 쉽다.
 *
 * 순위 기준(B1)은 "목적지에 언제 도착하나" 하나다. 후보마다
 *   주행 시간(왕복 우회 포함) + 도착 시각의 예상 대기(B4) + 충전 시간 + 들르는 데 드는 시간(경로를 벗어나면 진출입 시간 추가)
 * 을 더해 목적지 도착까지 걸리는 분(etaMinutes)을 구하고, 불안 요소(도착 잔량 부족, 시간 제한 위험)를
 * 분 단위 가산점으로 더한 costMinutes가 가장 작은 곳을 고른다. 그래서 "C 휴게소가 12분 빨리 도착"처럼
 * 이유를 숫자 하나로 설명할 수 있다.
 *
 * 충전소는 휴게소만이 아니다. 졸음쉼터, 국도변·IC 근처 충전소, 상업·공공시설도 후보가 된다.
 * 휴게소·쉼터가 아닌 곳에는 순위 가산([RecommendationConfig.nonRoadsidePenaltyMin])을 붙여, 휴게소가 붐비거나
 * 닿지 못할 때처럼 확실히 나을 때만 1순위가 되게 한다. 이용 가능 시간을 아는 곳은 도착 시각에 닫혀 있으면 뺀다.
 *
 * 한 번 들러서 목적지까지 못 가면 두 번째 충전소까지 함께 계획한다(B2). 첫 충전소에서 몇 %까지 채울지는
 * 두 충전소의 충전 속도와 대기를 비교해 전체 시간이 가장 짧아지는 값으로 고른다.
 */
class RecommendationEngine(private val config: RecommendationConfig = RecommendationConfig()) {

    /**
     * 추천을 계산한다. 아래 1~7번 순서로 진행한다.
     * 결과는 NotNeeded(충전 불필요), NoSafeStop(갈 수 있는 충전소 없음), Recommended(추천) 중 하나다.
     * [context]에는 지금 시각과 실측 소비 배율을 넣는다(없으면 기본값).
     */
    fun recommend(
        route: RouteState,
        vehicle: VehicleState,
        stations: List<ChargingStation>,
        profile: VehicleProfile,
        context: RecommendContext = RecommendContext()
    ): RecommendationResult {
        // 실측 소비가 사양과 다르면 전비를 바꾼 사양으로 모든 계산을 한다(B3).
        val p = profile.withConsumptionFactor(context.consumptionFactor)
        val soc = vehicle.socPercent

        // 1. 충전 필요 여부
        // 지금 배터리로 목적지까지(안전 마진 포함) 갈 수 있으면 추천할 필요가 없다.
        if (ChargeMath.requiredSoc(route.remainingDistanceKm, p, config) <= soc) {
            return RecommendationResult.NotNeeded
        }

        // 2. 하드 조건 필터
        // 이미 지나친 곳, 목적지보다 먼 곳, 도착할 때 닫혀 있는 곳은 뺀다.
        val speed = speedKmh(route)
        val ahead = stations.filter {
            it.distanceAlongRouteKm > 0 && it.distanceAlongRouteKm < route.remainingDistanceKm &&
                isOpenOnArrival(it, context, speed)
        }.sortedBy { it.distanceAlongRouteKm }
        // 도착했을 때 최소 잔량(10%)이 남는 곳만 "안전한" 후보로 남긴다.
        val safe = ahead.filter { arrivalSoc(soc, it, p) >= config.minArrivalSocPercent }
        if (safe.isEmpty()) {
            // 안전한 곳이 하나도 없으면, 그나마 배터리가 0이 되기 전에 닿는 가장 가까운 곳을 경고용으로 알려 준다.
            val nearest = ahead
                .filter { arrivalSoc(soc, it, p) > 0 }
                .minByOrNull { it.distanceAlongRouteKm + it.detourKm }
            return RecommendationResult.NoSafeStop(nearest)
        }
        // 안전한 후보 중 가장 먼 곳 = "늦어도 여기서는 충전해야 하는" 마지막 충전소
        val latestSafe = safe.maxBy { it.distanceAlongRouteKm }

        // 3~5. 후보마다 가장 빠른 충전 계획(한 번 또는 두 번 들르기)을 세운다.
        val trip = Trip(route, soc, p, context, speed)
        val plans = safe.mapNotNull { planFrom(it, ahead, trip) }
        // 안전 마진 덕분에 어느 충전소에서도 충전할 필요가 없는 경우
        if (plans.isEmpty()) return RecommendationResult.NotNeeded

        // 6. 정렬: 비용(분)이 작은 순서. 이유 배지는 전체 후보를 보고 붙인다.
        val ranked = withReasons(plans.sortedBy { it.rec.costMinutes }, latestSafe.id, p)

        // 7. 혼잡 판단
        // 대기를 빼고 봤을 때 1순위(=붐비지 않았다면 골랐을 곳)가 많이 붐비면, 그곳과 대안을 나란히 보여 주고 운전자가 고르게 한다.
        val ideal = ranked.minBy { it.rec.costMinutes - it.assumedWait }
        val primary = if (ideal.rec.waitMinutes > config.congestionWaitThresholdMin) ideal.rec else ranked.first().rec
        val alternatives = ranked.map { it.rec }.filter { it !== primary }.take(MAX_ALTERNATIVES)
        return RecommendationResult.Recommended(
            primary = primary,
            alternatives = alternatives,
            congested = primary.waitMinutes > config.congestionWaitThresholdMin,
            latestSafeStopId = latestSafe.id
        )
    }

    // 계산에 공통으로 쓰는 이번 추천의 조건 묶음
    private class Trip(
        val route: RouteState,
        val soc: Double,
        val profile: VehicleProfile,
        val context: RecommendContext,
        val speedKmh: Double
    )

    // 후보 하나의 계획과, 비용에 넣은 대기(모르는 대기는 가정값)를 함께 들고 다닌다.
    private class Plan(val rec: Recommendation, val assumedWait: Double)

    // 내비가 남은 시간을 주면 그 평균 속도를, 아니면 기본 속도를 쓴다.
    private fun speedKmh(route: RouteState): Double {
        val min = route.remainingTimeMin ?: return config.defaultSpeedKmh
        if (min <= 0 || route.remainingDistanceKm <= 0) return config.defaultSpeedKmh
        return (route.remainingDistanceKm / (min / 60.0)).coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH)
    }

    private fun driveMinutes(km: Double, trip: Trip) = km / trip.speedKmh * 60.0

    // 충전소까지 가는 거리 = 경로상 거리 + 경로에서 충전소까지 편도 거리
    private fun arrivalSoc(soc: Double, station: ChargingStation, profile: VehicleProfile) =
        ChargeMath.arrivalSoc(soc, station.distanceAlongRouteKm + station.detourKm, profile)

    // 충전소에서 목적지까지 = 경로로 돌아오는 거리 + 남은 경로
    private fun kmAfter(station: ChargingStation, route: RouteState) =
        station.detourKm + route.remainingDistanceKm - station.distanceAlongRouteKm

    // 한 번 들를 때 드는 시간. 경로를 벗어나는 곳은 IC 요금소·신호 등 진출입 시간을 더한다.
    private fun stopMinutes(station: ChargingStation) =
        config.stopOverheadMin + if (station.detourKm > config.offRouteKm) config.offRouteAccessMin else 0.0

    // 휴게소·쉼터가 아닌 곳에 붙이는 순위 가산. 도착 시간(eta)에는 넣지 않고 비용에만 넣는다.
    private fun kindPenalty(station: ChargingStation) =
        if (station.kind.isRoadside) 0.0 else config.nonRoadsidePenaltyMin

    /**
     * 이용 가능 시간을 아는 충전소가 도착 시각부터 [RecommendationConfig.minOpenAfterArrivalMin]분 동안 열려 있는지.
     * 시간을 모르거나 지금 시각을 모르면 열려 있다고 본다(빼면 후보가 너무 줄어든다).
     */
    private fun isOpenOnArrival(station: ChargingStation, context: RecommendContext, speedKmh: Double): Boolean {
        val hours = station.openHours ?: return true
        val now = context.minuteOfDay ?: return true
        val arrival = now + ((station.distanceAlongRouteKm + station.detourKm) / speedKmh * 60.0).roundToInt()
        return hours.covers(arrival, arrival + config.minOpenAfterArrivalMin)
    }

    /**
     * [s1]을 첫 충전소로 하는 가장 빠른 계획. 여기서 충전할 필요가 없으면 null.
     * 후보 계획: (가) 여기서만 충전, (나) 여기서 조금 + 뒤 충전소 [ahead]에서 한 번 더.
     */
    private fun planFrom(s1: ChargingStation, ahead: List<ChargingStation>, trip: Trip): Plan? {
        val p = trip.profile
        val arrival1 = arrivalSoc(trip.soc, s1, p)
        // 경로를 벗어난 충전소라면 경로로 돌아오는 거리까지 채워야 한다.
        val needed = ChargeMath.requiredSoc(kmAfter(s1, trip.route), p, config)
        val options = mutableListOf<Plan>()

        // (가) 한 번만 들르기: 목적지까지 필요한 %를 5% 단위로 올려 "50%까지"처럼 기억하기 쉬운 숫자로 만든다.
        if (needed <= config.maxTargetSocPercent) {
            val target = min(ChargeMath.ceilToStep(needed, config.roundUpStepPercent), config.maxTargetSocPercent)
            // 도착 잔량이 이미 목표 이상이면 이 충전소에서는 충전할 이유가 없다.
            if (target <= arrival1) return null
            options += build(s1, arrival1, target, needed, next = null, trip)
        }

        // (나) 두 번 들르기: 뒤 충전소마다, 거기까지 안전하게 가는 %부터 상한까지 5%씩 올려 보며 가장 빠른 조합을 찾는다.
        for (s2 in ahead) {
            if (s2.distanceAlongRouteKm <= s1.distanceAlongRouteKm) continue
            // s1에서 경로로 돌아오고, 경로를 따라가다, s2로 빠지는 거리
            val legKm = s1.detourKm + s2.distanceAlongRouteKm - s1.distanceAlongRouteKm + s2.detourKm
            val needToS2 = ChargeMath.requiredSoc(legKm, p, config)
            val needAfterS2 = ChargeMath.requiredSoc(kmAfter(s2, trip.route), p, config)
            if (needAfterS2 > config.maxTargetSocPercent) continue // 세 번 이상 들러야 하는 조합은 다루지 않는다
            val target2 = min(ChargeMath.ceilToStep(needAfterS2, config.roundUpStepPercent), config.maxTargetSocPercent)
            var t1 = max(
                ChargeMath.ceilToStep(needToS2, config.roundUpStepPercent),
                ChargeMath.ceilToStep(arrival1 + STEP_EPS, config.roundUpStepPercent)
            )
            while (t1 <= config.maxTargetSocPercent + STEP_EPS) {
                val arrival2 = t1 - legKm * ChargeMath.socPerKm(p)
                // 두 번째에서 충전할 필요가 없으면 (가)와 같은 계획이므로 건너뛴다.
                if (target2 > arrival2) options += build(s1, arrival1, t1, needToS2, next = Leg(s2, arrival2, target2), trip)
                t1 += config.roundUpStepPercent
            }
        }

        // 두 번 들러도 목적지까지 못 가면, 예전처럼 상한까지 채우고 "한 번 더 충전 필요"로 표시한다.
        if (options.isEmpty()) {
            if (config.maxTargetSocPercent <= arrival1) return null
            options += build(s1, arrival1, config.maxTargetSocPercent, needed, next = null, trip, unreachable = true)
        }
        return options.minBy { it.rec.costMinutes }
    }

    // 두 번째 충전소의 도착 잔량과 목표
    private class Leg(val station: ChargingStation, val arrivalSoc: Double, val targetSoc: Double)

    /**
     * 계획 하나를 [Recommendation]으로 만든다: 충전 시간, 시간 제한, 도착 시각의 대기, 목적지 도착까지 걸리는 분, 비용.
     * [needed]는 이 충전소에서 최소한 채워야 하는 %(시간 제한 안에 그만큼도 못 채우면 위험으로 본다).
     */
    private fun build(
        s1: ChargingStation,
        arrival1: Double,
        target1: Double,
        needed: Double,
        next: Leg?,
        trip: Trip,
        unreachable: Boolean = false
    ): Plan {
        val p = trip.profile
        val minutes1 = ChargeMath.chargeMinutes(arrival1, target1, s1.maxKw, p, config)
        // 시간 제한 확인: 제한 시간 동안 충전하면 몇 %까지 차는지 계산한다.
        val limit = s1.rule.timeLimitMin
        val socAtLimit = limit?.let { ChargeMath.socAfterMinutes(arrival1, it.toDouble(), s1.maxKw, p, config) }
        val willHitLimit = limit != null && minutes1 > limit
        // 제한 시간 안에 필요한 만큼도 못 채우면 한 번 더 들러야 한다.
        val limitShortfall = socAtLimit != null && needed > socAtLimit

        val toS1 = driveMinutes(s1.distanceAlongRouteKm + s1.detourKm, trip)
        val wait1 = waitAt(s1, toS1, trip)
        // 우회는 갔다가 돌아오므로 두 번 센다.
        var eta = driveMinutes(trip.route.remainingDistanceKm + 2 * s1.detourKm, trip) +
            wait1.assumed + minutes1 + stopMinutes(s1)
        var penalty = lowSocPenalty(arrival1) + kindPenalty(s1) +
            (if (willHitLimit) config.limitRiskPenaltyMin else 0.0) +
            (if (unreachable) config.unreachablePenaltyMin else 0.0)

        // 두 번째 충전소: 첫 충전소에서 출발한 뒤의 도착 시각으로 대기를 예측한다.
        var assumedWait = wait1.assumed
        val planned = next?.let { leg ->
            val s2 = leg.station
            val minutes2 = ChargeMath.chargeMinutes(leg.arrivalSoc, leg.targetSoc, s2.maxKw, p, config)
            val toS2 = toS1 + wait1.assumed + minutes1 + stopMinutes(s1) +
                driveMinutes(s1.detourKm + s2.distanceAlongRouteKm - s1.distanceAlongRouteKm + s2.detourKm, trip)
            val wait2 = waitAt(s2, toS2, trip)
            assumedWait += wait2.assumed
            eta += driveMinutes(2 * s2.detourKm, trip) + wait2.assumed + minutes2 + stopMinutes(s2)
            penalty += lowSocPenalty(leg.arrivalSoc) + kindPenalty(s2)
            s2.rule.timeLimitMin?.let { if (minutes2 > it) penalty += config.limitRiskPenaltyMin }
            PlannedStop(s2, leg.arrivalSoc, leg.targetSoc, ceil(minutes2).toInt(), wait2.shown)
        }

        val rec = Recommendation(
            station = s1,
            arrivalSocPercent = arrival1,
            targetSocPercent = target1,
            chargeMinutes = ceil(minutes1).toInt(),
            waitMinutes = wait1.shown,
            timeLimitMin = limit,
            willHitTimeLimit = willHitLimit,
            socAtTimeLimitPercent = socAtLimit,
            needsAnotherStop = planned != null || unreachable || limitShortfall,
            reasonCodes = emptyList(),   // 이유는 전체 후보를 본 뒤(withReasons) 채운다.
            waitSource = wait1.source,
            etaMinutes = eta,
            costMinutes = eta + penalty,
            nextStop = planned
        )
        return Plan(rec, assumedWait)
    }

    // 화면에 보여 줄 대기(모르면 0)와 계산에 넣을 대기(모르면 가정값), 그리고 출처
    private class Wait(val shown: Int, val assumed: Double, val source: WaitSource)

    /** 지금부터 [minutesAhead]분 뒤 [station]에 도착했을 때의 대기(B4). */
    private fun waitAt(station: ChargingStation, minutesAhead: Double, trip: Trip): Wait {
        val arrivalMinute = trip.context.minuteOfDay?.let { it + minutesAhead.roundToInt() }
        val est = WaitForecast.predict(station, minutesAhead, arrivalMinute, config.liveWaitHalfLifeMin)
        return Wait(est.minutes ?: 0, (est.minutes ?: config.unknownWaitMin).toDouble(), est.source)
    }

    // 도착 잔량이 넉넉하지 않을수록(기본 20% 미만) 불안 비용을 분 단위로 더한다.
    private fun lowSocPenalty(arrival: Double) =
        max(0.0, config.comfortArrivalSocPercent - arrival) * config.lowSocPenaltyMinPerPercent

    /** 화면에 보여 줄 "왜 여기를 추천했는지" 배지를 붙인다. 비교가 필요한 배지는 전체 후보를 보고 정한다. */
    private fun withReasons(plans: List<Plan>, latestSafeId: String, profile: VehicleProfile): List<Plan> {
        val recs = plans.map { it.rec }
        val power = { r: Recommendation -> ChargeMath.peakPowerKw(r.station.maxKw, profile) }
        val maxPower = recs.maxOf(power)
        val fastest = recs.minOf { it.etaMinutes }
        val knownWaits = recs.filter { it.waitSource != WaitSource.UNKNOWN }.map { it.waitMinutes }
        val minWait = knownWaits.minOrNull()
        return plans.map { plan ->
            val r = plan.rec
            val s = r.station
            val availability = availabilityRatio(s)
            val reasons = buildList {
                if (recs.size > 1 && r.etaMinutes <= fastest + 1e-9) add(ReasonCode.FASTEST_ARRIVAL)
                // "가장 ~" 배지는 비교할 후보가 둘 이상일 때만 붙인다(혼자면 가장 빠른 것도 아니다).
                if (recs.size > 1 && power(r) >= maxPower) add(ReasonCode.MAX_POWER)
                if (r.waitSource != WaitSource.UNKNOWN && ((recs.size > 1 && r.waitMinutes == minWait) || r.waitMinutes <= LOW_WAIT_MIN)) {
                    add(ReasonCode.LOW_WAIT)
                }
                if (availability != null && availability >= ENOUGH_RATIO) add(ReasonCode.ENOUGH_CHARGERS)
                if (s.id == latestSafeId) add(ReasonCode.LATEST_SAFE_STOP)
                if (s.detourKm <= config.offRouteKm) add(ReasonCode.NO_DETOUR)
                if (r.willHitTimeLimit && (r.socAtTimeLimitPercent ?: 0.0) < r.targetSocPercent) {
                    add(ReasonCode.TIME_LIMIT_RISK)
                }
            }
            Plan(r.copy(reasonCodes = reasons), plan.assumedWait)
        }
    }

    // 빈 충전기 비율(0~1). 예) 6대 중 3대 비어 있으면 0.5. 정보가 없으면 null.
    private fun availabilityRatio(s: ChargingStation): Double? {
        val available = s.availableChargers ?: return null
        if (s.totalChargers <= 0) return null
        return (available.toDouble() / s.totalChargers).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val MAX_ALTERNATIVES = 2   // 대안으로 함께 보여 줄 충전소 수
        const val LOW_WAIT_MIN = 5       // 이 분 이하 대기면 "대기 짧음" 배지
        const val ENOUGH_RATIO = 0.5     // 빈 충전기가 절반 이상이면 "충전기 넉넉" 배지
        const val MIN_SPEED_KMH = 30.0   // 내비 평균 속도가 튀어도 이 범위로 묶는다
        const val MAX_SPEED_KMH = 140.0
        const val STEP_EPS = 1e-6
    }
}
