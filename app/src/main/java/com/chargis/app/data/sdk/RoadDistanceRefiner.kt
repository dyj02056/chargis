package com.chargis.app.data.sdk

import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.ConnectorType
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.RouteState
import com.chargis.app.port.ChargerDataPort
import com.chargis.app.port.TripEstimate
import com.chargis.app.port.TripEstimator
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * B8: 충전소까지의 거리를 순정 내비의 실제 도로 거리(requestTrip)로 바꾼다.
 *
 * [base]가 준 목록에서 가까운 [maxStations]곳만 내비에 물어본다(호출 수 제한). 내비 호출은 한 번에 하나씩 결과가 오므로
 * 같은 충전소는 [cacheMillis] 동안 다시 묻지 않는다.
 *
 * 차량의 실제 위치가 앱이 계산 중인 경로의 현재 위치와 [agreeKm] 안에서 맞을 때만 바꾼다.
 * 하이브리드 데모처럼 경로는 가상인데 차는 다른 곳에 있으면, 실제 도로 거리가 엉뚱한 값이 되므로 원래 값(직선 투영)을 그대로 둔다.
 */
class RoadDistanceRefiner(
    private val base: ChargerDataPort,
    private val trips: TripEstimator,
    private val maxStations: Int = 3,
    private val agreeKm: Double = 2.0,
    private val cacheMillis: Long = 60_000,
    private val locationMillis: Long = 10_000,
    private val now: () -> Long = System::currentTimeMillis
) : ChargerDataPort {
    private val cache = HashMap<String, Pair<Long, TripEstimate>>()
    private var located: Pair<Long, GeoPoint?>? = null

    override suspend fun stationsAlongRoute(route: RouteState, connectors: Set<ConnectorType>?): List<ChargingStation> {
        val stations = base.stationsAlongRoute(route, connectors)
        val car = carLocation() ?: return stations
        if (distanceKm(car, route.current) > agreeKm) return stations
        val nearest = stations.sortedBy { it.distanceAlongRouteKm }.take(maxStations)
        val estimates = nearest.mapNotNull { s -> estimateCached(s)?.let { s.id to it } }.toMap()
        return refine(stations, estimates)
    }

    // 위치 조회는 내비의 모든 연결 앱에 알림이 퍼지므로, 추천을 다시 계산할 때마다 묻지 않고 [locationMillis]마다 한 번만 묻는다.
    private suspend fun carLocation(): GeoPoint? {
        val t = now()
        located?.let { (at, p) -> if (t - at < locationMillis) return p }
        val p = runCatching { trips.currentLocation() }.getOrNull()
        located = t to p
        return p
    }

    private suspend fun estimateCached(station: ChargingStation): TripEstimate? {
        val t = now()
        cache[station.id]?.let { (at, e) -> if (t - at < cacheMillis) return e }
        val e = runCatching { trips.estimate(station) }.getOrNull() ?: return null
        cache[station.id] = t to e
        return e
    }

    companion object {
        /**
         * 내비 예측이 있는 충전소의 경로상 거리를 실제 도로 거리로 바꾼다.
         * 도로 거리는 충전소 입구까지라 경로에서 빠지는 거리(편도 우회)가 들어 있다. 엔진은 우회를 따로 왕복으로 세므로 그만큼 뺀다.
         */
        fun refine(stations: List<ChargingStation>, estimates: Map<String, TripEstimate>): List<ChargingStation> =
            stations.map { s ->
                val e = estimates[s.id] ?: return@map s
                s.copy(distanceAlongRouteKm = (e.distanceKm - s.detourKm).coerceAtLeast(0.1))
            }

        /** 두 지점 사이 대권 거리(km). */
        fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
            val r = 6371.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(h))
        }
    }
}
