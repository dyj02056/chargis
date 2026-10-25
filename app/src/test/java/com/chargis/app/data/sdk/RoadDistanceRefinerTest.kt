package com.chargis.app.data.sdk

import com.chargis.app.domain.engine.station
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.ConnectorType
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.RouteState
import com.chargis.app.port.ChargerDataPort
import com.chargis.app.port.TripEstimate
import com.chargis.app.port.TripEstimator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** B8: 순정 내비의 실제 도로 거리(requestTrip)로 충전소 거리를 바꾸는 규칙. */
class RoadDistanceRefinerTest {
    private val here = GeoPoint(37.50, 127.00)
    private val route = RouteState(current = here, destination = null, remainingDistanceKm = 200.0, remainingTimeMin = null)
    private val stations = listOf(
        station("a", 20.0), station("b", 40.0, detourKm = 2.0), station("c", 60.0), station("d", 80.0)
    )
    private val base = object : ChargerDataPort {
        override suspend fun stationsAlongRoute(route: RouteState, connectors: Set<ConnectorType>?) = stations
    }

    private class Trips(var location: GeoPoint?) : TripEstimator {
        val asked = mutableListOf<String>()
        override suspend fun currentLocation() = location
        override suspend fun estimate(station: ChargingStation): TripEstimate {
            asked += station.id
            return TripEstimate(distanceKm = station.distanceAlongRouteKm * 1.2, etaMin = 30, arrivalSocPercent = 40)
        }
    }

    /** 가까운 3곳만 묻고, 도로 거리에서 편도 우회를 뺀 값을 경로상 거리로 쓴다. 나머지는 그대로. */
    @Test
    fun refinesNearestThreeWhenCarIsOnRoute() = runTest {
        val trips = Trips(here)
        val out = RoadDistanceRefiner(base, trips).stationsAlongRoute(route, null).associateBy { it.id }
        assertEquals(listOf("a", "b", "c"), trips.asked)
        assertEquals(24.0, out.getValue("a").distanceAlongRouteKm, 1e-9)
        assertEquals(46.0, out.getValue("b").distanceAlongRouteKm, 1e-9)   // 40 × 1.2 − 우회 2
        assertEquals(80.0, out.getValue("d").distanceAlongRouteKm, 1e-9)
    }

    /** 차 위치가 경로 위 현재 위치와 2km 넘게 떨어져 있으면(가상 경로 데모) 묻지도 바꾸지도 않는다. */
    @Test
    fun keepsEstimatesWhenCarIsElsewhere() = runTest {
        val trips = Trips(GeoPoint(35.10, 129.04))
        val out = RoadDistanceRefiner(base, trips).stationsAlongRoute(route, null)
        assertEquals(stations, out)
        assertEquals(emptyList<String>(), trips.asked)
    }

    /** 위치를 모르면(내비 미연결) 원래 목록을 그대로 준다. */
    @Test
    fun keepsEstimatesWithoutLocation() = runTest {
        assertEquals(stations, RoadDistanceRefiner(base, Trips(null)).stationsAlongRoute(route, null))
    }

    /** 같은 충전소는 캐시 시간 동안 다시 묻지 않는다. */
    @Test
    fun cachesPerStation() = runTest {
        var t = 0L
        val trips = Trips(here)
        val refiner = RoadDistanceRefiner(base, trips, cacheMillis = 60_000, now = { t })
        refiner.stationsAlongRoute(route, null)
        t = 30_000
        refiner.stationsAlongRoute(route, null)
        assertEquals(3, trips.asked.size)
        t = 61_000
        refiner.stationsAlongRoute(route, null)
        assertEquals(6, trips.asked.size)
    }

    /** 위치는 10초에 한 번만 묻는다(내비 알림이 모든 연결 앱에 퍼지므로). */
    @Test
    fun throttlesLocationQueries() = runTest {
        var t = 0L
        var asks = 0
        val trips = object : TripEstimator {
            override suspend fun currentLocation(): GeoPoint? { asks++; return null }
            override suspend fun estimate(station: ChargingStation): TripEstimate? = null
        }
        val refiner = RoadDistanceRefiner(base, trips, now = { t })
        repeat(5) { refiner.stationsAlongRoute(route, null) }
        assertEquals(1, asks)
        t = 10_000
        refiner.stationsAlongRoute(route, null)
        assertEquals(2, asks)
    }

    /** TripInfo 단위 변환: 거리 m → km, 도착 시간 초 → 분, 범위를 벗어난 배터리는 모름. */
    @Test
    fun convertsTripInfoUnits() {
        assertEquals(TripEstimate(12.345, 15, 41), PleosNavi.toEstimate(12_345, 900, 41))
        assertEquals(null, PleosNavi.toEstimate(1_000, 60, -1).arrivalSocPercent)
    }
}
