package com.chargis.app.domain.engine

import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.domain.model.VehicleProfile
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** plan.md §7.1, §7.2-4의 충전 수식. 추천 엔진과 충전 세션 트래커가 함께 쓴다. */
object ChargeMath {

    /** 1km 주행에 쓰는 SoC(%). */
    fun socPerKm(profile: VehicleProfile): Double =
        (1.0 / profile.efficiencyKmPerKwh) / profile.batteryKwh * 100.0

    fun arrivalSoc(soc: Double, distanceKm: Double, profile: VehicleProfile): Double =
        soc - distanceKm * socPerKm(profile)

    fun requiredSoc(distanceKm: Double, profile: VehicleProfile, config: RecommendationConfig): Double =
        distanceKm * socPerKm(profile) * (1 + config.safetyMargin) + config.minArrivalSocPercent

    fun ceilToStep(value: Double, step: Double): Double =
        ceil(value / step - EPSILON) * step

    /** 충전소 출력과 차량 수용 출력 중 작은 값. */
    fun peakPowerKw(stationMaxKw: Double, profile: VehicleProfile): Double =
        min(stationMaxKw, profile.maxChargeKw)

    /** [fromSoc] → [toSoc] 충전에 걸리는 분(소수). */
    fun chargeMinutes(
        fromSoc: Double,
        toSoc: Double,
        stationMaxKw: Double,
        profile: VehicleProfile,
        config: RecommendationConfig
    ): Double {
        if (toSoc <= fromSoc) return 0.0
        val cap = config.taperStartSocPercent
        val (lowKw, highKw) = segmentPowers(stationMaxKw, profile, config)
        val lowPct = max(0.0, min(toSoc, cap) - fromSoc)
        val highPct = max(0.0, toSoc - max(fromSoc, cap))
        return kwhOf(lowPct, profile) / lowKw * 60.0 + kwhOf(highPct, profile) / highKw * 60.0
    }

    /** [fromSoc]에서 [minutes]분 충전했을 때의 SoC. [chargeMinutes]의 역함수. */
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
            val toCap = chargeMinutes(soc, cap, stationMaxKw, profile, config)
            if (left <= toCap) return soc + pctOf(lowKw * left / 60.0, profile)
            soc = cap
            left -= toCap
        }
        return min(100.0, soc + pctOf(highKw * left / 60.0, profile))
    }

    private fun segmentPowers(
        stationMaxKw: Double,
        profile: VehicleProfile,
        config: RecommendationConfig
    ): Pair<Double, Double> {
        val low = peakPowerKw(stationMaxKw, profile) * config.avgPowerFactor
        return low to low * config.taperFactorAboveCap
    }

    private fun kwhOf(pct: Double, profile: VehicleProfile) = pct / 100.0 * profile.batteryKwh
    private fun pctOf(kwh: Double, profile: VehicleProfile) = kwh / profile.batteryKwh * 100.0

    private const val EPSILON = 1e-9
}
