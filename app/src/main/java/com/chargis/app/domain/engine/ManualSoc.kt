package com.chargis.app.domain.engine

import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.domain.model.VehicleProfile

/**
 * 배터리 %를 차량에서 받을 수 없을 때(Vehicle SDK 미지원) 쓰는 추정기. 순수 Kotlin.
 *
 * 운전자가 계기판을 보고 넣은 값을 기준점으로 잡고, 그 뒤로는 계산으로 이어 간다.
 * - 주행 중: 기준점 − 달린 거리 × 1km당 % − 고도 변화 몫
 * - 충전 중: 기준점에서 충전 곡선대로 시간만큼 오른 값(목표에서 멈춤)
 * 주행 ↔ 충전이 바뀌는 순간에는 그때의 추정값을 새 기준점으로 삼아, 값이 튀지 않게 이어 붙인다.
 * 운전자가 정차 중에 값을 고치면([set]) 그 값이 새 기준점이 된다.
 */
class ManualSocEstimator {

    /** 충전 중 추정에 쓰는 값. [capSoc]에서 충전이 멈춘다고 본다(충전 목표). */
    class Charger(val stationMaxKw: Double, val profile: VehicleProfile, val config: RecommendationConfig, val capSoc: Double)

    private var anchorSoc: Double? = null
    private var anchorOdoKm = 0.0
    private var anchorElevSoc = 0.0
    private var anchorSec = 0.0
    private var anchorCharging = false
    private var last: Double? = null

    /** 기준점이 있는지(운전자가 한 번이라도 넣었는지) */
    val hasValue: Boolean get() = anchorSoc != null

    /**
     * 운전자가 넣은 값(또는 이어 붙일 추정값)을 기준점으로 잡는다.
     * [odometerKm]·[elevationSoc]은 그 순간의 누적 주행 거리와 누적 고도 몫, [charging]은 지금 충전 중인지.
     */
    fun set(soc: Double, odometerKm: Double, elevationSoc: Double, nowSec: Double, charging: Boolean) {
        anchorSoc = soc.coerceIn(0.0, 100.0)
        anchorOdoKm = odometerKm
        anchorElevSoc = elevationSoc
        anchorSec = nowSec
        anchorCharging = charging
        last = anchorSoc
    }

    /**
     * 지금 배터리 % 추정. [socPerKm]는 보정된 1km당 %, [charger]가 있으면 충전 중이다. 기준점이 없으면 null.
     */
    fun estimate(odometerKm: Double, elevationSoc: Double, nowSec: Double, socPerKm: Double, charger: Charger?): Double? {
        val base = anchorSoc ?: return null
        val charging = charger != null
        // 주행 ↔ 충전이 바뀌었으면 지금까지의 추정값에서 새로 시작한다.
        if (charging != anchorCharging) set(last ?: base, odometerKm, elevationSoc, nowSec, charging)
        val a = anchorSoc ?: return null
        val value = if (charger == null) {
            a - (odometerKm - anchorOdoKm) * socPerKm - (elevationSoc - anchorElevSoc)
        } else {
            val minutes = (nowSec - anchorSec) / 60.0
            minOf(charger.capSoc.coerceAtLeast(a), ChargeMath.socAfterMinutes(a, minutes, charger.stationMaxKw, charger.profile, charger.config))
        }
        return value.coerceIn(0.0, 100.0).also { last = it }
    }

    fun reset() {
        anchorSoc = null
        last = null
        anchorCharging = false
    }
}
