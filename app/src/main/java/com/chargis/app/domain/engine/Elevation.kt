package com.chargis.app.domain.engine

import com.chargis.app.domain.model.ElevationPoint
import com.chargis.app.domain.model.VehicleProfile

/**
 * 오르막·내리막에 드는 배터리. 순수 Kotlin.
 *
 * 차를 [VehicleProfile.massKg]만큼 h미터 들어 올리는 데 드는 에너지는 m × g × h 다.
 * 예) 2,100kg 차가 600m를 오르면 2100 × 9.81 × 600 ≈ 12.4MJ ≈ 3.4kWh, 모터 효율(90%)을 넣으면 약 3.8kWh(77.4kWh의 약 5%).
 * 내려갈 때는 회생 제동으로 그 일부(60%, 가정)를 되찾는다. 그래서 같은 높이를 올랐다 내려와도 조금 손해를 본다.
 *
 * 평지 소비(전비)는 [ChargeMath.socPerKm]가 맡고, 이 파일은 높이가 바뀌는 만큼만 더하거나 뺀다.
 * 고도 정보는 내비 경로에서 받는다. 없으면 평지로 본다(0).
 */
object ElevationModel {

    /**
     * 경로 [fromKm]~[toKm] 구간을 달릴 때 고도 변화로 더 쓰는(+) 또는 되찾는(−) SoC(%).
     * [points]는 지금 위치 기준 거리 오름차순 고도. 없거나 한 점뿐이면 0.
     */
    fun socBetween(points: List<ElevationPoint>?, fromKm: Double, toKm: Double, profile: VehicleProfile): Double {
        if (points == null || points.size < 2 || toKm <= fromKm) return 0.0
        // 구간 안의 꺾이는 점들과 양 끝 점의 고도를 이어, 오르막과 내리막을 따로 더한다.
        val inner = points.filter { it.distanceKm > fromKm && it.distanceKm < toKm }.map { it.elevationM }
        val heights = listOf(elevationAt(points, fromKm)) + inner + listOf(elevationAt(points, toKm))
        return heights.zipWithNext { a, b -> socForClimb(b - a, profile) }.sum()
    }

    /** 높이 [deltaM]m를 오르는(+) 또는 내려가는(−) 데 드는 SoC(%). 내려가면 회생 제동으로 일부를 되찾아 음수가 된다. */
    fun socForClimb(deltaM: Double, profile: VehicleProfile): Double {
        val kwh = profile.massKg * G * deltaM / J_PER_KWH
        val battery = if (deltaM >= 0) kwh / DRIVE_EFFICIENCY else kwh * REGEN_EFFICIENCY
        return battery / profile.batteryKwh * 100.0
    }

    /** 경로 [km] 지점의 고도(m). 점 사이는 직선으로 잇고, 범위 밖이면 양 끝 값. */
    fun elevationAt(points: List<ElevationPoint>, km: Double): Double {
        if (km <= points.first().distanceKm) return points.first().elevationM
        if (km >= points.last().distanceKm) return points.last().elevationM
        val i = points.indexOfFirst { it.distanceKm >= km }
        val a = points[i - 1]
        val b = points[i]
        val t = (km - a.distanceKm) / (b.distanceKm - a.distanceKm)
        return a.elevationM + (b.elevationM - a.elevationM) * t
    }

    /** 출발점 기준 고도 목록을 지금 위치 기준으로 바꾼다. [traveledKm] 앞쪽만 남기고, 첫 점은 지금 위치(0km)다. */
    fun ahead(points: List<ElevationPoint>?, traveledKm: Double): List<ElevationPoint>? {
        if (points == null || points.size < 2) return null
        val here = ElevationPoint(0.0, elevationAt(points, traveledKm))
        return listOf(here) + points.filter { it.distanceKm > traveledKm }.map { it.copy(distanceKm = it.distanceKm - traveledKm) }
    }

    private const val G = 9.81
    private const val J_PER_KWH = 3_600_000.0
    private const val DRIVE_EFFICIENCY = 0.9   // 배터리 → 바퀴 (가정)
    private const val REGEN_EFFICIENCY = 0.6   // 내리막 위치 에너지 → 배터리 (가정)
}
