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
 */
class RecommendationEngine(private val config: RecommendationConfig = RecommendationConfig()) {

    fun recommend(
        route: RouteState,
        vehicle: VehicleState,
        stations: List<ChargingStation>,
        profile: VehicleProfile
    ): RecommendationResult {
        val soc = vehicle.socPercent

        // 1. 충전 필요 여부
        if (ChargeMath.requiredSoc(route.remainingDistanceKm, profile, config) <= soc) {
            return RecommendationResult.NotNeeded
        }

        // 2. 하드 조건 필터
        val ahead = stations.filter {
            it.distanceAlongRouteKm > 0 && it.distanceAlongRouteKm < route.remainingDistanceKm
        }
        val safe = ahead.filter { arrivalSoc(soc, it, profile) >= config.minArrivalSocPercent }
        if (safe.isEmpty()) {
            val nearest = ahead
                .filter { arrivalSoc(soc, it, profile) > 0 }
                .minByOrNull { it.distanceAlongRouteKm + it.detourKm }
            return RecommendationResult.NoSafeStop(nearest)
        }
        val latestSafe = safe.maxBy { it.distanceAlongRouteKm }

        // 3~5. 후보별 목표 충전량, 충전 시간, 시간 제한
        val evaluated = safe.mapNotNull { evaluate(it, soc, route, profile) }
        // 안전 마진 덕분에 어느 휴게소에서도 충전할 필요가 없는 경우
        if (evaluated.isEmpty()) return RecommendationResult.NotNeeded

        // 6. 점수
        val scored = score(evaluated, latestSafe.id, profile)

        // 7. 정렬
        val sorted = scored.sortedByDescending { it.score }
        val primary = sorted.first()
        return RecommendationResult.Recommended(
            primary = primary,
            alternatives = sorted.drop(1).take(MAX_ALTERNATIVES),
            congested = primary.waitMinutes > config.congestionWaitThresholdMin,
            latestSafeStopId = latestSafe.id
        )
    }

    private fun arrivalSoc(soc: Double, station: ChargingStation, profile: VehicleProfile) =
        ChargeMath.arrivalSoc(soc, station.distanceAlongRouteKm + station.detourKm, profile)

    private fun evaluate(
        station: ChargingStation,
        soc: Double,
        route: RouteState,
        profile: VehicleProfile
    ): Recommendation? {
        val arrival = arrivalSoc(soc, station, profile)
        val remainingAfter = route.remainingDistanceKm - station.distanceAlongRouteKm
        val needed = ChargeMath.requiredSoc(remainingAfter, profile, config)

        var needsAnotherStop = needed > config.maxTargetSocPercent
        val target = if (needsAnotherStop) {
            config.maxTargetSocPercent
        } else {
            min(ChargeMath.ceilToStep(needed, config.roundUpStepPercent), config.maxTargetSocPercent)
        }
        if (target <= arrival) return null

        val minutes = ChargeMath.chargeMinutes(arrival, target, station.maxKw, profile, config)
        val limit = station.rule.timeLimitMin
        var willHitLimit = false
        var socAtLimit: Double? = null
        if (limit != null) {
            socAtLimit = ChargeMath.socAfterMinutes(arrival, limit.toDouble(), station.maxKw, profile, config)
            willHitLimit = minutes > limit
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
            score = 0.0,
            reasonCodes = emptyList()
        )
    }

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
        val knownWaits = candidates.mapNotNull { it.station.expectedWaitMin?.toDouble() }
        val waitNorm = Normalizer(knownWaits)
        val maxPower = candidates.maxOf(power)
        val minWait = knownWaits.minOrNull()

        return candidates.map { r ->
            val s = r.station
            val availability = availabilityRatio(s)
            val wait = s.expectedWaitMin?.let { waitNorm(it.toDouble()) } ?: NEUTRAL
            val score = w.arrivalSoc * arrivalNorm(r.arrivalSocPercent) +
                w.power * powerNorm(power(r)) +
                w.availability * (availability ?: NEUTRAL) -
                w.detour * detourNorm(s.detourKm) -
                w.wait * wait

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

    private fun availabilityRatio(s: ChargingStation): Double? {
        val available = s.availableChargers ?: return null
        if (s.totalChargers <= 0) return null
        return (available.toDouble() / s.totalChargers).coerceIn(0.0, 1.0)
    }

    /** 후보 집합 안에서 min-max 정규화. 값이 모두 같으면 중립값. */
    private class Normalizer(values: List<Double>) {
        private val lo = values.minOrNull() ?: 0.0
        private val hi = values.maxOrNull() ?: 0.0
        operator fun invoke(v: Double): Double = if (hi - lo < 1e-9) NEUTRAL else (v - lo) / (hi - lo)
    }

    private companion object {
        const val MAX_ALTERNATIVES = 2
        const val NEUTRAL = 0.5
        const val LOW_WAIT_MIN = 5
        const val ENOUGH_RATIO = 0.5
        const val NO_DETOUR_KM = 0.5
    }
}
