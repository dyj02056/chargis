package com.chargis.app.domain.engine

import com.chargis.app.domain.model.CurvePoint
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
     * 차종에 충전 곡선([VehicleProfile.chargeCurve])이 있으면 SoC를 잘게 나눠 구간마다 실제 출력으로 계산한다(B3).
     * 없으면 예전처럼 두 구간 모델을 쓴다: 전기차는 80%를 넘으면 배터리 보호를 위해 충전 속도가 크게 느려지므로(테이퍼링)
     * 80% 아래 구간과 위 구간을 나눠서 따로 계산한 뒤 더한다.
     * 예) 150kW 충전기로 20% → 50%: 30%(23.2kWh) ÷ 112.5kW(150×0.75) × 60 ≈ 12.4분
     */
    fun chargeMinutes(
        fromSoc: Double,
        toSoc: Double,
        stationMaxKw: Double,
        profile: VehicleProfile,
        config: RecommendationConfig
    ): Double {
        if (toSoc <= fromSoc) return 0.0
        if (profile.chargeCurve != null) {
            // 곡선 모델: 0.25%씩 올라가며 그 구간의 출력으로 걸리는 시간을 더한다.
            var minutes = 0.0
            var soc = fromSoc
            while (soc < toSoc - EPSILON) {
                val step = min(CURVE_STEP, toSoc - soc)
                minutes += kwhOf(step, profile) / powerKw(soc + step / 2, stationMaxKw, profile, config) * 60.0
                soc += step
            }
            return minutes
        }
        val cap = config.taperStartSocPercent
        val (lowKw, highKw) = segmentPowers(stationMaxKw, profile, config)
        // 80% 아래에서 충전할 양(lowPct)과 80% 위에서 충전할 양(highPct)을 나눈다.
        val lowPct = max(0.0, min(toSoc, cap) - fromSoc)
        val highPct = max(0.0, toSoc - max(fromSoc, cap))
        // 시간(분) = 충전할 에너지(kWh) ÷ 출력(kW) × 60
        return kwhOf(lowPct, profile) / lowKw * 60.0 + kwhOf(highPct, profile) / highKw * 60.0
    }

    /**
     * [fromSoc]에서 [minutes]분 충전했을 때의 SoC(100% 상한). [chargeMinutes]의 역함수.
     * 시간 제한(예: 40분) 안에 몇 %까지 찰지 미리 계산할 때 쓴다.
     * 80%에 닿기 전까지는 빠른 속도로, 그 뒤로는 느린 속도로 남은 시간만큼 채운다.
     */
    fun socAfterMinutes(
        fromSoc: Double,
        minutes: Double,
        stationMaxKw: Double,
        profile: VehicleProfile,
        config: RecommendationConfig
    ): Double {
        if (minutes <= 0) return fromSoc
        if (profile.chargeCurve != null) {
            // 곡선 모델: 구간마다 걸리는 시간을 빼 가며 남은 시간 안에 오를 수 있는 만큼 올린다.
            var soc = fromSoc
            var left = minutes
            while (left > EPSILON && soc < 100.0) {
                val step = min(CURVE_STEP, 100.0 - soc)
                val kw = powerKw(soc + step / 2, stationMaxKw, profile, config)
                val need = kwhOf(step, profile) / kw * 60.0
                if (need >= left) return soc + pctOf(kw * left / 60.0, profile)
                soc += step
                left -= need
            }
            return min(100.0, soc)
        }
        val cap = config.taperStartSocPercent
        val (lowKw, highKw) = segmentPowers(stationMaxKw, profile, config)
        var soc = fromSoc
        var left = minutes
        // 80% 아래라면: 80%까지 가는 데 걸리는 시간보다 남은 시간이 짧으면 그만큼만 채우고 끝낸다.
        if (soc < cap) {
            val toCap = chargeMinutes(soc, cap, stationMaxKw, profile, config)
            if (left <= toCap) return soc + pctOf(lowKw * left / 60.0, profile)
            soc = cap
            left -= toCap
        }
        // 80%를 넘었으면 느린 속도로 남은 시간만큼 더 채운다(100%를 넘지 않게).
        return min(100.0, soc + pctOf(highKw * left / 60.0, profile))
    }

    /**
     * 배터리가 [soc]%일 때 실제로 들어오는 출력(kW). 충전 중 화면의 출력 표시와 곡선 그래프에 쓴다.
     * 곡선이 있으면 min(충전기 실출력, 곡선 값), 없으면 두 구간 모델의 출력이다.
     */
    fun powerKw(soc: Double, stationMaxKw: Double, profile: VehicleProfile, config: RecommendationConfig): Double {
        val curve = profile.chargeCurve
        if (curve != null) {
            val delivered = stationMaxKw * config.stationDeliveryFactor
            return max(MIN_KW, min(delivered, min(profile.maxChargeKw, curveKw(curve, soc))))
        }
        val (lowKw, highKw) = segmentPowers(stationMaxKw, profile, config)
        return if (soc < config.taperStartSocPercent) lowKw else highKw
    }

    // 곡선 위 두 점 사이를 직선으로 이어 [soc]의 출력을 구한다. 범위 밖이면 양 끝 값.
    private fun curveKw(curve: List<CurvePoint>, soc: Double): Double {
        if (soc <= curve.first().socPercent) return curve.first().kw
        if (soc >= curve.last().socPercent) return curve.last().kw
        val i = curve.indexOfFirst { it.socPercent >= soc }
        val a = curve[i - 1]
        val b = curve[i]
        val t = (soc - a.socPercent) / (b.socPercent - a.socPercent)
        return a.kw + (b.kw - a.kw) * t
    }

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
    private const val CURVE_STEP = 0.25   // 곡선 모델의 적분 간격(%)
    private const val MIN_KW = 1.0        // 0으로 나누지 않도록 출력 하한
}
