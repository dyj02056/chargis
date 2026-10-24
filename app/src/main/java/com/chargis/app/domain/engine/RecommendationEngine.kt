package com.chargis.app.domain.engine

import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.ReasonCode
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.domain.model.RecommendationResult
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.VehicleProfile
import com.chargis.app.domain.model.VehicleState
import kotlin.math.ceil
import kotlin.math.min

/**
 * plan.md §7.2 휴게소 충전 추천(F1) + 목표 충전량(F2).
 * 무상태 순수 함수다. 재안내 금지/히스테리시스는 [AnnouncementGate]가 맡는다.
 *
 * 이 앱의 두뇌에 해당한다. 경로, 배터리, 충전소 목록을 넣으면 "어느 휴게소에서 몇 %까지"를 답한다.
 * "무상태 순수 함수"란 같은 입력을 넣으면 언제나 같은 답이 나오고, 이전 호출을 기억하지 않는다는 뜻이다.
 * 덕분에 테스트하기 쉽다.
 */
class RecommendationEngine(private val config: RecommendationConfig = RecommendationConfig()) {

    /**
     * 추천을 계산한다. 아래 1~7번 순서로 진행한다.
     * 결과는 NotNeeded(충전 불필요), NoSafeStop(갈 수 있는 충전소 없음), Recommended(추천) 중 하나다.
     */
    fun recommend(
        route: RouteState,
        vehicle: VehicleState,
        stations: List<ChargingStation>,
        profile: VehicleProfile
    ): RecommendationResult {
        val soc = vehicle.socPercent

        // 1. 충전 필요 여부
        // 지금 배터리로 목적지까지(안전 마진 포함) 갈 수 있으면 추천할 필요가 없다.
        if (ChargeMath.requiredSoc(route.remainingDistanceKm, profile, config) <= soc) {
            return RecommendationResult.NotNeeded
        }

        // 2. 하드 조건 필터
        // 이미 지나친 곳과 목적지보다 먼 곳은 뺀다.
        val ahead = stations.filter {
            it.distanceAlongRouteKm > 0 && it.distanceAlongRouteKm < route.remainingDistanceKm
        }
        // 도착했을 때 최소 잔량(10%)이 남는 곳만 "안전한" 후보로 남긴다.
        val safe = ahead.filter { arrivalSoc(soc, it, profile) >= config.minArrivalSocPercent }
        if (safe.isEmpty()) {
            // 안전한 곳이 하나도 없으면, 그나마 배터리가 0이 되기 전에 닿는 가장 가까운 곳을 경고용으로 알려 준다.
            val nearest = ahead
                .filter { arrivalSoc(soc, it, profile) > 0 }
                .minByOrNull { it.distanceAlongRouteKm + it.detourKm }
            return RecommendationResult.NoSafeStop(nearest)
        }
        // 안전한 후보 중 가장 먼 곳 = "늦어도 여기서는 충전해야 하는" 마지막 휴게소
        val latestSafe = safe.maxBy { it.distanceAlongRouteKm }

        // 3~5. 후보별 목표 충전량, 충전 시간, 시간 제한
        val evaluated = safe.mapNotNull { evaluate(it, soc, route, profile) }
        // 안전 마진 덕분에 어느 휴게소에서도 충전할 필요가 없는 경우
        if (evaluated.isEmpty()) return RecommendationResult.NotNeeded

        // 6. 점수
        val scored = score(evaluated, latestSafe.id, profile)

        // 7. 정렬
        // 점수가 높은 순으로 줄 세워 1등을 추천하고, 그다음 최대 2곳을 대안으로 함께 보여 준다.
        val sorted = scored.sortedByDescending { it.score }
        val primary = sorted.first()
        return RecommendationResult.Recommended(
            primary = primary,
            alternatives = sorted.drop(1).take(MAX_ALTERNATIVES),
            congested = primary.waitMinutes > config.congestionWaitThresholdMin,
            latestSafeStopId = latestSafe.id
        )
    }

    // 충전소까지 가는 거리 = 경로상 거리 + 휴게소로 빠지는 추가 거리
    private fun arrivalSoc(soc: Double, station: ChargingStation, profile: VehicleProfile) =
        ChargeMath.arrivalSoc(soc, station.distanceAlongRouteKm + station.detourKm, profile)

    /**
     * 충전소 한 곳에 대해 도착 잔량, 목표 %, 충전 시간, 시간 제한 위험을 계산한다.
     * 이 충전소에서 충전할 필요가 없으면 null을 돌려줘 후보에서 빠진다.
     */
    private fun evaluate(
        station: ChargingStation,
        soc: Double,
        route: RouteState,
        profile: VehicleProfile
    ): Recommendation? {
        val arrival = arrivalSoc(soc, station, profile)
        // 이 충전소에서 목적지까지 남는 거리와, 그 거리를 가는 데 필요한 %
        val remainingAfter = route.remainingDistanceKm - station.distanceAlongRouteKm
        val needed = ChargeMath.requiredSoc(remainingAfter, profile, config)

        // 필요한 %가 상한(80%)을 넘으면 여기서 80%까지 채워도 목적지까지 못 간다 → 한 번 더 들러야 함
        var needsAnotherStop = needed > config.maxTargetSocPercent
        val target = if (needsAnotherStop) {
            config.maxTargetSocPercent
        } else {
            // 필요한 %를 5% 단위로 올려 "40%까지"처럼 기억하기 쉬운 숫자로 만든다.
            min(ChargeMath.ceilToStep(needed, config.roundUpStepPercent), config.maxTargetSocPercent)
        }
        // 도착 잔량이 이미 목표 이상이면 이 휴게소에서는 충전할 이유가 없다.
        if (target <= arrival) return null

        val minutes = ChargeMath.chargeMinutes(arrival, target, station.maxKw, profile, config)
        // 시간 제한 확인: 제한 시간 동안 충전하면 몇 %까지 차는지 계산한다.
        val limit = station.rule.timeLimitMin
        var willHitLimit = false
        var socAtLimit: Double? = null
        if (limit != null) {
            socAtLimit = ChargeMath.socAfterMinutes(arrival, limit.toDouble(), station.maxKw, profile, config)
            willHitLimit = minutes > limit
            // 제한 시간 안에 필요한 만큼도 못 채우면 한 번 더 들러야 한다.
            if (needed > socAtLimit) needsAnotherStop = true
        }

        return Recommendation(
            station = station,
            arrivalSocPercent = arrival,
            targetSocPercent = target,
            chargeMinutes = ceil(minutes).toInt(),
            waitMinutes = station.expectedWaitMin ?: 0,
            timeLimitMin = limit,
            willHitTimeLimit = willHitLimit,
            socAtTimeLimitPercent = socAtLimit,
            needsAnotherStop = needsAnotherStop,
            score = 0.0,                // 점수와 이유는 다음 단계(score)에서 채운다.
            reasonCodes = emptyList()
        )
    }

    /**
     * 후보마다 점수를 매기고 추천 이유 배지를 붙인다.
     *
     * 항목마다 단위가 달라서(%, kW, km, 분) 그대로 더할 수 없으므로,
     * 먼저 후보들 사이에서 0~1로 줄 세운 다음(정규화) 가중치를 곱해 더한다.
     * 좋은 항목(도착 잔량, 출력, 빈 충전기)은 더하고, 나쁜 항목(우회 거리, 대기)은 뺀다.
     */
    private fun score(
        candidates: List<Recommendation>,
        latestSafeId: String,
        profile: VehicleProfile
    ): List<Recommendation> {
        val w = config.weights
        val power = { r: Recommendation -> ChargeMath.peakPowerKw(r.station.maxKw, profile) }
        val arrivalNorm = Normalizer(candidates.map { it.arrivalSocPercent })
        val powerNorm = Normalizer(candidates.map(power))
        val detourNorm = Normalizer(candidates.map { it.station.detourKm })
        // 대기 시간을 모르는 충전소는 비교 기준에서 빼고, 아는 값끼리만 줄 세운다.
        val knownWaits = candidates.mapNotNull { it.station.expectedWaitMin?.toDouble() }
        val waitNorm = Normalizer(knownWaits)
        val maxPower = candidates.maxOf(power)
        val minWait = knownWaits.minOrNull()

        return candidates.map { r ->
            val s = r.station
            val availability = availabilityRatio(s)
            // 정보가 없는 항목은 중간값(0.5)으로 쳐서 유리하지도 불리하지도 않게 한다.
            val wait = s.expectedWaitMin?.let { waitNorm(it.toDouble()) } ?: NEUTRAL
            val score = w.arrivalSoc * arrivalNorm(r.arrivalSocPercent) +
                w.power * powerNorm(power(r)) +
                w.availability * (availability ?: NEUTRAL) -
                w.detour * detourNorm(s.detourKm) -
                w.wait * wait

            // 화면에 보여 줄 "왜 여기를 추천했는지" 배지 목록
            val reasons = buildList {
                if (power(r) >= maxPower) add(ReasonCode.MAX_POWER)
                if (s.expectedWaitMin != null && (s.expectedWaitMin.toDouble() == minWait || s.expectedWaitMin <= LOW_WAIT_MIN)) {
                    add(ReasonCode.LOW_WAIT)
                }
                if (availability != null && availability >= ENOUGH_RATIO) add(ReasonCode.ENOUGH_CHARGERS)
                if (s.id == latestSafeId) add(ReasonCode.LATEST_SAFE_STOP)
                if (s.detourKm <= NO_DETOUR_KM) add(ReasonCode.NO_DETOUR)
                if (r.willHitTimeLimit && (r.socAtTimeLimitPercent ?: 0.0) < r.targetSocPercent) {
                    add(ReasonCode.TIME_LIMIT_RISK)
                }
            }
            r.copy(score = score, reasonCodes = reasons)
        }
    }

    // 빈 충전기 비율(0~1). 예) 6대 중 3대 비어 있으면 0.5. 정보가 없으면 null.
    private fun availabilityRatio(s: ChargingStation): Double? {
        val available = s.availableChargers ?: return null
        if (s.totalChargers <= 0) return null
        return (available.toDouble() / s.totalChargers).coerceIn(0.0, 1.0)
    }

    /**
     * 후보 집합 안에서 min-max 정규화. 값이 모두 같으면 중립값.
     * 예) 출력이 50, 100, 200kW인 세 후보 → 0, 0.33, 1
     */
    private class Normalizer(values: List<Double>) {
        private val lo = values.minOrNull() ?: 0.0
        private val hi = values.maxOrNull() ?: 0.0
        operator fun invoke(v: Double): Double = if (hi - lo < 1e-9) NEUTRAL else (v - lo) / (hi - lo)
    }

    private companion object {
        const val MAX_ALTERNATIVES = 2   // 대안으로 함께 보여 줄 충전소 수
        const val NEUTRAL = 0.5          // 정보가 없을 때 쓰는 중간 점수
        const val LOW_WAIT_MIN = 5       // 이 분 이하 대기면 "대기 짧음" 배지
        const val ENOUGH_RATIO = 0.5     // 빈 충전기가 절반 이상이면 "충전기 넉넉" 배지
        const val NO_DETOUR_KM = 0.5     // 우회 거리가 이 km 이하면 "경로 이탈 없음" 배지
    }
}
