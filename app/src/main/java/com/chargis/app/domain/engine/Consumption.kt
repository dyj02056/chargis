package com.chargis.app.domain.engine

import kotlin.math.max
import kotlin.math.min

/**
 * B3: 전비 보정. "사양 전비대로 달린다"는 가정을 두 단계로 고친다.
 *
 * 1. 사전 추정([ConsumptionModel]): 출발 전에도 알 수 있는 바깥 기온과 속도로 소비가 몇 배일지 어림한다.
 * 2. 실측 보정([ConsumptionCalibrator]): 달리면서 실제로 1km에 몇 %씩 줄었는지 재서, 사전 추정을 실측 쪽으로 옮긴다.
 *
 * 결과는 "소비 배율" 하나다. 1.2면 사양보다 20% 더 쓴다는 뜻이고,
 * 엔진은 VehicleProfile.withConsumptionFactor(1.2)로 전비만 바꿔 같은 계산식을 그대로 쓴다.
 */
object ConsumptionModel {

    /**
     * 기온과 속도로 어림한 소비 배율.
     * - 기온: 15°C보다 1도 낮을 때마다 0.8%씩 더 쓴다(난방·배터리 효율). 최대 1.35배.
     * - 속도: 100km/h를 넘으면 1km/h마다 0.6%씩 더 쓴다(공기 저항). 최대 1.3배.
     * 계수는 공개 자료의 대략적인 경향을 따른 가정값이다(docs/DECISIONS.md).
     */
    fun priorFactor(outsideTempC: Double?, speedKmh: Double?): Double {
        val temp = outsideTempC?.let { min(MAX_TEMP_FACTOR, 1 + TEMP_COEF * max(0.0, REF_TEMP_C - it)) } ?: 1.0
        val speed = speedKmh?.let { min(MAX_SPEED_FACTOR, 1 + SPEED_COEF * max(0.0, it - REF_SPEED_KMH)) } ?: 1.0
        return temp * speed
    }

    private const val REF_TEMP_C = 15.0
    private const val TEMP_COEF = 0.008
    private const val MAX_TEMP_FACTOR = 1.35
    private const val REF_SPEED_KMH = 100.0
    private const val SPEED_COEF = 0.006
    private const val MAX_SPEED_FACTOR = 1.3
}

/**
 * 주행 중 실측으로 소비 배율을 보정한다. 순수 Kotlin.
 *
 * [onSample]에 (누적 주행 km, SoC)를 계속 넣는다. 충전으로 SoC가 오르면 그 구간은 끊고 새로 잰다.
 * 최근 [windowKm] 동안의 "1km당 실제 감소 %"를 사양 값과 비교해 실측 배율을 구하고,
 * 달린 거리가 [fullTrustKm]에 가까워질수록 사전 추정 대신 실측을 믿는다.
 *
 * 예) 사양 0.26%/km인데 20km 동안 6.2% 줄었다(0.31%/km) → 실측 배율 1.2
 */
class ConsumptionCalibrator(
    private val windowKm: Double = 40.0,
    private val fullTrustKm: Double = 20.0,
    private val minFactor: Double = 0.7,
    private val maxFactor: Double = 1.6
) {
    // 끊기지 않은 주행 구간들. 각 구간은 (km, SoC) 점들의 줄이다.
    private val segments = ArrayDeque<ArrayDeque<Pair<Double, Double>>>()

    /** 새 측정값. [odometerKm]는 여행 시작부터 누적 주행 거리, [soc]는 지금 배터리 %. */
    fun onSample(odometerKm: Double, soc: Double) {
        val current = segments.lastOrNull()
        val last = current?.lastOrNull()
        when {
            // 처음이거나, 배터리가 올랐으면(충전) 새 구간을 시작한다.
            current == null || last == null || soc > last.second + SOC_EPS -> segments.addLast(ArrayDeque(listOf(odometerKm to soc)))
            // 거리만 늘고 SoC는 그대로인 점은 경로·차량 값이 따로 도착해 짝이 어긋난 것이라 버린다.
            // (그대로 두면 다음 점에서 줄어든 SoC가 엉뚱한 짧은 구간에 몰려 소비가 부풀려진다.)
            odometerKm > last.first && soc == last.second -> Unit
            // 차가 움직였을 때만 점을 더한다(정차 중 같은 값 반복 방지).
            odometerKm > last.first -> current.addLast(odometerKm to soc)
        }
        trim(odometerKm)
    }

    /** 사전 추정 [prior]와 실측을 섞은 소비 배율. [specSocPerKm]는 사양 기준 1km당 SoC(%). */
    fun factor(specSocPerKm: Double, prior: Double = 1.0): Double {
        var km = 0.0
        var used = 0.0
        segments.forEach { seg ->
            if (seg.size >= 2) {
                km += seg.last().first - seg.first().first
                used += seg.first().second - seg.last().second
            }
        }
        if (km < MIN_KM || specSocPerKm <= 0) return prior
        val observed = (used / km / specSocPerKm).coerceIn(minFactor, maxFactor)
        val trust = min(1.0, km / fullTrustKm)
        return prior + (observed - prior) * trust
    }

    /** 실측으로 확인한 거리(km). 화면에 "N km 실측 반영" 같은 표시를 할 때 쓴다. */
    val observedKm: Double
        get() = segments.sumOf { if (it.size >= 2) it.last().first - it.first().first else 0.0 }

    fun reset() = segments.clear()

    // 최근 windowKm보다 오래된 점은 버린다. 각 구간은 끝점을 남겨 다음 구간과 이어지게 둔다.
    private fun trim(nowKm: Double) {
        segments.forEach { seg -> while (seg.size > 2 && nowKm - seg.first().first > windowKm) seg.removeFirst() }
        while (segments.size > 1 && nowKm - segments.first().last().first > windowKm) segments.removeFirst()
    }

    private companion object {
        const val SOC_EPS = 1e-6
        const val MIN_KM = 2.0   // 이보다 짧으면 실측을 쓰지 않는다
    }
}
