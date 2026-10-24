package com.chargis.app.domain.session

import com.chargis.app.domain.engine.ChargeMath
import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.domain.model.VehicleProfile
import kotlin.math.max

/** plan.md §8.2 출력. 시간은 모두 분(소수), 시각은 세션 시계의 초. */
data class ChargingSnapshot(
    val currentSoc: Double,
    val targetSoc: Double,
    val elapsedMin: Double,
    val minutesToTarget: Double,
    val minutesToLimit: Double?,          // 제한 없음이면 null
    val predictedSocAtLimit: Double?,     // 제한 시각에 도달할 SoC
    val departAtSec: Double,              // 출발 예정(목표 도달 또는 제한 종료 중 이른 쪽)
    val reached: Boolean,
    val limitExpired: Boolean
) {
    /** 제한 안에 목표에 닿지 못할 것으로 보이면 true. */
    val willHitLimit: Boolean
        get() = minutesToLimit != null && minutesToTarget > minutesToLimit
}

/**
 * 충전 한 번의 진행을 추적한다(plan.md §8.2). 순수 Kotlin.
 * 남은 시간은 ChargeMath 모델값을, 최근 [windowSec] 동안 실제 SoC 증가율로 보정한다.
 * 샘플이 부족하면(2개 미만이거나 [minSpanSec]보다 짧으면) 모델값을 그대로 쓴다.
 */
class ChargingSessionTracker(
    private val profile: VehicleProfile,
    private val config: RecommendationConfig,
    private val stationMaxKw: Double,
    val targetSoc: Double,
    val timeLimitMin: Int?,
    private val startSec: Double,
    private val windowSec: Double = 120.0,
    private val minSpanSec: Double = 30.0
) {
    private val samples = ArrayDeque<Pair<Double, Double>>() // (초, SoC)

    fun update(nowSec: Double, soc: Double): ChargingSnapshot {
        // 시각만 앞서고 SoC는 그대로인 샘플은 데이터 짝이 어긋난 것(경로·차량 값이 따로 도착)이라 보정에 쓰지 않는다.
        val stale = samples.isNotEmpty() && soc == samples.last().second && nowSec > samples.last().first
        if (!stale) samples.addLast(nowSec to soc)
        while (samples.size > 2 && nowSec - samples.first().first > windowSec) samples.removeFirst()

        val elapsedMin = max(0.0, (nowSec - startSec) / 60.0)
        val factor = correctionFactor(soc)
        val modelToTarget = ChargeMath.chargeMinutes(soc, targetSoc, stationMaxKw, profile, config)
        val minutesToTarget = modelToTarget * factor
        val reached = soc >= targetSoc - REACH_EPS

        val minutesToLimit = timeLimitMin?.let { max(0.0, it - elapsedMin) }
        val limitExpired = minutesToLimit != null && minutesToLimit <= LIMIT_EPS
        val predictedAtLimit = minutesToLimit?.let {
            ChargeMath.socAfterMinutes(soc, it / factor, stationMaxKw, profile, config).coerceAtMost(100.0)
        }
        val untilDepart = when {
            reached || limitExpired -> 0.0
            minutesToLimit != null -> minOf(minutesToTarget, minutesToLimit)
            else -> minutesToTarget
        }
        return ChargingSnapshot(
            currentSoc = soc,
            targetSoc = targetSoc,
            elapsedMin = elapsedMin,
            minutesToTarget = if (reached) 0.0 else minutesToTarget,
            minutesToLimit = minutesToLimit,
            predictedSocAtLimit = predictedAtLimit,
            departAtSec = nowSec + untilDepart * 60.0,
            reached = reached,
            limitExpired = limitExpired
        )
    }

    /** 모델 충전 속도 ÷ 실제 측정 속도. 1보다 크면 실제가 더 느리다. */
    private fun correctionFactor(soc: Double): Double {
        if (samples.size < 2) return 1.0
        val (t0, s0) = samples.first()
        val (t1, s1) = samples.last()
        val span = t1 - t0
        if (span < minSpanSec || s1 <= s0) return 1.0
        val measuredPerMin = (s1 - s0) / (span / 60.0)
        val modelPerMin = ChargeMath.socAfterMinutes(soc, 1.0, stationMaxKw, profile, config) - soc
        if (modelPerMin <= 0) return 1.0
        return (modelPerMin / measuredPerMin).coerceIn(0.25, 4.0)
    }

    private companion object {
        const val REACH_EPS = 1e-6
        const val LIMIT_EPS = 1e-6
    }
}
