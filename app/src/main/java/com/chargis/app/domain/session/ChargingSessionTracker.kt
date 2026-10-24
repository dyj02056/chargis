package com.chargis.app.domain.session

import com.chargis.app.domain.engine.ChargeMath
import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.domain.model.VehicleProfile
import kotlin.math.max

/**
 * plan.md §8.2 출력. 시간은 모두 분(소수), 시각은 세션 시계의 초.
 * 충전 중 어느 한 순간의 상황을 찍은 "스냅숏(사진)"이다. 충전 화면과 음성 알림이 이 값을 그대로 쓴다.
 */
data class ChargingSnapshot(
    val currentSoc: Double,               // 지금 배터리 %
    val targetSoc: Double,                // 목표 배터리 %
    val elapsedMin: Double,               // 충전 시작 후 지난 분
    val minutesToTarget: Double,          // 목표까지 남은 분
    val minutesToLimit: Double?,          // 제한 없음이면 null
    val predictedSocAtLimit: Double?,     // 제한 시각에 도달할 SoC
    val departAtSec: Double,              // 출발 예정(목표 도달 또는 제한 종료 중 이른 쪽)
    val reached: Boolean,                 // 목표에 도달했는지
    val limitExpired: Boolean             // 시간 제한이 끝났는지
) {
    /** 제한 안에 목표에 닿지 못할 것으로 보이면 true. */
    val willHitLimit: Boolean
        get() = minutesToLimit != null && minutesToTarget > minutesToLimit
}

/**
 * 충전 한 번의 진행을 추적한다(plan.md §8.2). 순수 Kotlin.
 * 남은 시간은 ChargeMath 모델값을, 최근 [windowSec] 동안 실제 SoC 증가율로 보정한다.
 * 샘플이 부족하면(2개 미만이거나 [minSpanSec]보다 짧으면) 모델값을 그대로 쓴다.
 *
 * 쉽게 말해, 계산식으로 예상한 충전 속도와 실제로 오르는 속도를 비교해서 남은 시간을 고친다.
 * 예) 계산상 1분에 2%씩 올라야 하는데 실제로는 1%씩 오르면, 남은 시간을 2배로 늘려 잡는다.
 * 최근 2분(120초) 동안의 기록만 보므로 충전기 상태가 바뀌어도 금방 따라간다.
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

    /** 새 측정값(시각 [nowSec], 배터리 [soc])을 받아 최신 충전 상황을 계산한다. */
    fun update(nowSec: Double, soc: Double): ChargingSnapshot {
        // 시각만 앞서고 SoC는 그대로인 샘플은 데이터 짝이 어긋난 것(경로·차량 값이 따로 도착)이라 보정에 쓰지 않는다.
        val stale = samples.isNotEmpty() && soc == samples.last().second && nowSec > samples.last().first
        if (!stale) samples.addLast(nowSec to soc)
        // 2분보다 오래된 기록은 버린다(단, 최소 2개는 남겨 속도를 잴 수 있게 한다).
        while (samples.size > 2 && nowSec - samples.first().first > windowSec) samples.removeFirst()

        val elapsedMin = max(0.0, (nowSec - startSec) / 60.0)
        val factor = correctionFactor(soc)
        // 계산식으로 구한 남은 시간에 보정 배율을 곱한다.
        val modelToTarget = ChargeMath.chargeMinutes(soc, targetSoc, stationMaxKw, profile, config)
        val minutesToTarget = modelToTarget * factor
        val reached = soc >= targetSoc - REACH_EPS

        // 시간 제한까지 남은 분과, 제한이 끝날 때 예상 배터리 %
        val minutesToLimit = timeLimitMin?.let { max(0.0, it - elapsedMin) }
        val limitExpired = minutesToLimit != null && minutesToLimit <= LIMIT_EPS
        val predictedAtLimit = minutesToLimit?.let {
            ChargeMath.socAfterMinutes(soc, it / factor, stationMaxKw, profile, config).coerceAtMost(100.0)
        }
        // 출발까지 남은 분: 목표 도달과 제한 종료 중 먼저 오는 쪽
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
        // 기록 기간이 30초보다 짧거나 배터리가 안 올랐으면 믿을 만한 측정이 아니므로 보정하지 않는다.
        if (span < minSpanSec || s1 <= s0) return 1.0
        val measuredPerMin = (s1 - s0) / (span / 60.0)
        val modelPerMin = ChargeMath.socAfterMinutes(soc, 1.0, stationMaxKw, profile, config) - soc
        if (modelPerMin <= 0) return 1.0
        // 측정이 튀어도 남은 시간이 터무니없어지지 않게 0.25배~4배 사이로 묶는다.
        return (modelPerMin / measuredPerMin).coerceIn(0.25, 4.0)
    }

    private companion object {
        // 소수 오차 허용치. 49.9999999%도 50% 도달로 본다.
        const val REACH_EPS = 1e-6
        const val LIMIT_EPS = 1e-6
    }
}
