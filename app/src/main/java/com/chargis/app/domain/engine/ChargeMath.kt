package com.chargis.app.domain.engine

import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.domain.model.VehicleProfile
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * plan.md §7.1, §7.2-4의 충전 수식. 추천 엔진과 충전 세션 트래커가 함께 쓴다.
 *
 * 아래 예시는 모두 기본 차종(배터리 77.4kWh, 전비 5km/kWh, 최대 150kW 충전)을 기준으로 한다.
 * object는 앱 전체에 하나만 있는 도구 상자라는 뜻이다. ChargeMath.socPerKm(...)처럼 바로 꺼내 쓴다.
 */
object ChargeMath {

    /**
     * 1km 주행에 쓰는 SoC(%).
     * 예) 1km에 1/5 = 0.2kWh를 쓰고, 0.2 / 77.4 × 100 ≈ 0.26%. 100km를 가면 약 26%가 줄어든다.
     */
    fun socPerKm(profile: VehicleProfile): Double =
        (1.0 / profile.efficiencyKmPerKwh) / profile.batteryKwh * 100.0

    /**
     * 지금 배터리 [soc]%로 [distanceKm]만큼 가면 도착할 때 남는 %.
     * 예) 60%에서 100km 가면 60 - 26 = 약 34%
     */
    fun arrivalSoc(soc: Double, distanceKm: Double, profile: VehicleProfile): Double =
        soc - distanceKm * socPerKm(profile)

    /**
     * [distanceKm]를 안전하게 가려면 출발할 때 몇 %가 있어야 하는지.
     * 실제 소모량에 안전 마진(15%)을 더 얹고, 도착 시 최소 잔량(10%)을 남기도록 계산한다.
     * 예) 100km → 26% × 1.15 + 10 ≈ 39.7%
     */
    fun requiredSoc(distanceKm: Double, profile: VehicleProfile, config: RecommendationConfig): Double =
        distanceKm * socPerKm(profile) * (1 + config.safetyMargin) + config.minArrivalSocPercent

    /**
     * [value]를 [step] 단위로 올림한다. 예) 39.7을 5 단위로 → 40
     * EPSILON은 컴퓨터 소수 계산의 아주 작은 오차(40.0000000001 같은 값) 때문에
     * 40이 45로 잘못 올라가는 것을 막는 장치다.
     */
    fun ceilToStep(value: Double, step: Double): Double =
        ceil(value / step - EPSILON) * step

    /**
     * 충전소 출력과 차량 수용 출력 중 작은 값.
     * 200kW 충전기라도 차가 150kW까지만 받으면 실제 속도는 150kW다.
     */
    fun peakPowerKw(stationMaxKw: Double, profile: VehicleProfile): Double =
        min(stationMaxKw, profile.maxChargeKw)

    /**
     * [fromSoc] → [toSoc] 충전에 걸리는 분(소수).
     *
     * 전기차는 80%를 넘으면 배터리 보호를 위해 충전 속도가 크게 느려진다(테이퍼링).
     * 그래서 80% 아래 구간과 위 구간을 나눠서 따로 계산한 뒤 더한다.
     * 예) 150kW 충전기로 20% → 50%: 30%(23.2kWh) ÷ 112.5kW(150×0.75) × 60 ≈ 12.4분
     */
    fun chargeMinutes(
        fromSoc: Double,
        toSoc: Double,
        stationMaxKw: Double,
        profile: VehicleProfile,
        config: RecommendationConfig
    ): Double {
        // 이미 목표 이상이면 충전할 필요가 없다.
        if (toSoc <= fromSoc) return 0.0
        val cap = config.taperStartSocPercent
        val (lowKw, highKw) = segmentPowers(stationMaxKw, profile, config)
        // 80% 아래에서 채울 양과 80% 위에서 채울 양(%)을 나눈다.
        val lowPct = max(0.0, min(toSoc, cap) - fromSoc)
        val highPct = max(0.0, toSoc - max(fromSoc, cap))
        // 시간(분) = 에너지(kWh) ÷ 속도(kW) × 60
        return kwhOf(lowPct, profile) / lowKw * 60.0 + kwhOf(highPct, profile) / highKw * 60.0
    }

    /**
     * [fromSoc]에서 [minutes]분 충전했을 때의 SoC. [chargeMinutes]의 역함수.
     * "충전기 시간 제한 40분이 끝나면 몇 %가 되는가?"를 계산할 때 쓴다.
     */
    fun socAfterMinutes(
        fromSoc: Double,
        minutes: Double,
        stationMaxKw: Double,
        profile: VehicleProfile,
        config: RecommendationConfig
    ): Double {
        if (minutes <= 0) return fromSoc
        val cap = config.taperStartSocPercent
        val (lowKw, highKw) = segmentPowers(stationMaxKw, profile, config)
        var soc = fromSoc
        var left = minutes
        if (soc < cap) {
            // 먼저 빠른 구간(80%까지)을 채우는 데 몇 분 걸리는지 본다.
            val toCap = chargeMinutes(soc, cap, stationMaxKw, profile, config)
            // 80%에 닿기 전에 시간이 끝나면 빠른 속도로만 계산하고 끝낸다.
            if (left <= toCap) return soc + pctOf(lowKw * left / 60.0, profile)
            // 아니면 80%까지 채우고, 남은 시간은 느린 구간에서 쓴다.
            soc = cap
            left -= toCap
        }
        // 느린 구간 계산. 100%를 넘을 수는 없다.
        return min(100.0, soc + pctOf(highKw * left / 60.0, profile))
    }

    /**
     * 구간별 평균 충전 속도(kW)를 돌려준다. (80% 아래 속도, 80% 위 속도)
     * 예) 최대 150kW → 아래 구간 112.5kW(×0.75), 위 구간 약 39kW(×0.35 추가 감쇠)
     */
    private fun segmentPowers(
        stationMaxKw: Double,
        profile: VehicleProfile,
        config: RecommendationConfig
    ): Pair<Double, Double> {
        val low = peakPowerKw(stationMaxKw, profile) * config.avgPowerFactor
        return low to low * config.taperFactorAboveCap
    }

    // % ↔ kWh 변환. 예) 77.4kWh 배터리의 10% = 7.74kWh
    private fun kwhOf(pct: Double, profile: VehicleProfile) = pct / 100.0 * profile.batteryKwh
    private fun pctOf(kwh: Double, profile: VehicleProfile) = kwh / profile.batteryKwh * 100.0

    // 소수 계산 오차를 흡수하기 위한 아주 작은 값(0.000000001)
    private const val EPSILON = 1e-9
}
