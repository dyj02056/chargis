package com.chargis.app.data.publicapi

import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.RouteState
import kotlin.math.cos
import kotlin.math.sqrt

// 충전소 좌표 → 경로상 거리·우회 거리(PublicChargerData.kt에서 분리).

/**
 * 지도 좌표를 경로 위 거리로 바꾼다. 경로 폴리라인이 없으므로(내비 SDK 확인 전) 지금 위치→목적지 직선을 경로로 본다.
 * 직선 위로 내린 위치를 실제 남은 거리 비율로 늘려 "경로상 거리"로, 직선에서 떨어진 거리를 "편도 우회 거리"로 쓴다.
 * 돌아오는 거리는 추천 엔진이 왕복으로 다시 센다.
 */
object RouteProjector {

    /** (경로상 거리 km, 편도 우회 km). 경로 앞쪽이 아니거나 [corridorKm]보다 멀면 null. */
    fun project(route: RouteState, point: GeoPoint, corridorKm: Double): Pair<Double, Double>? {
        val dest = route.destination ?: return null
        val (dx, dy) = toKm(route.current, dest)
        val (px, py) = toKm(route.current, point)
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-6) return null
        val along = (px * dx + py * dy) / len
        val lateral = kotlin.math.abs(px * dy - py * dx) / len
        if (along <= 0 || along >= len || lateral > corridorKm) return null
        // 직선 거리를 실제 도로 거리로 늘리는 비율(도로는 직선보다 길다)
        val roadFactor = (route.remainingDistanceKm / len).coerceAtLeast(1.0)
        return along * roadFactor to lateral * DETOUR_FACTOR
    }

    // 작은 범위에서는 위도·경도 차이를 km로 바로 바꿔도 충분히 정확하다(등장방형 근사).
    private fun toKm(origin: GeoPoint, p: GeoPoint): Pair<Double, Double> {
        val x = (p.lon - origin.lon) * KM_PER_DEG_LON * cos(Math.toRadians(origin.lat))
        val y = (p.lat - origin.lat) * KM_PER_DEG_LAT
        return x to y
    }

    private const val KM_PER_DEG_LAT = 110.57
    private const val KM_PER_DEG_LON = 111.32
    private const val DETOUR_FACTOR = 1.3
}
