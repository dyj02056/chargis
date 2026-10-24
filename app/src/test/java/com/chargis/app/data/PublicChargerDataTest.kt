package com.chargis.app.data

import com.chargis.app.data.publicapi.ChargerStatus
import com.chargis.app.data.publicapi.EvChargerParser
import com.chargis.app.data.publicapi.FallbackChargerData
import com.chargis.app.data.publicapi.PublicChargerData
import com.chargis.app.data.publicapi.RouteProjector
import com.chargis.app.data.publicapi.StationAggregator
import com.chargis.app.domain.model.ChargerRule
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.StationLiveStatus
import com.chargis.app.port.ChargerDataPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/*
 * B5 공공 충전소 데이터 어댑터 테스트. 실제 API를 부르지 않고, 저장해 둔 응답(test/resources)으로 확인한다.
 * 가상 데이터만 쓴다(실제 시설명 아님).
 */
class PublicChargerDataTest {
    private val body = javaClass.classLoader!!.getResource("ev_charger_info.json")!!.readText(Charsets.UTF_8)
    private val now = LocalDateTime.of(2026, 10, 25, 10, 20)
    // 서울 근처 → 속초 방향 직선 경로, 남은 210km
    private val route = RouteState(GeoPoint(37.55, 127.05), GeoPoint(38.20, 128.59), 210.0, 126)

    @Test
    fun parse_skipsInvalidAndReadsStringNumbers() {
        val items = EvChargerParser.parseInfo(body)
        assertEquals(6, items.size)
        assertEquals(100.0, items.first().outputKw!!, 1e-9)
        assertEquals(2, items.first().stat)
    }

    @Test
    fun aggregate_keepsRestStopFastChargersOnly() {
        val stations = StationAggregator.aggregate(EvChargerParser.parseInfo(body), emptyList(), now)
        assertEquals(listOf("PX000001", "PX000002", "PX000004"), stations.map { it.id }.sorted())
        val a = stations.first { it.id == "PX000001" }
        assertEquals(200.0, a.maxKw, 1e-9)        // 완속(7kW)은 빠지고 가장 빠른 충전기 기준
        assertEquals(2, a.totalChargers)
        assertEquals(1, a.availableChargers)
        assertEquals(0, a.expectedWaitMin)         // 빈 충전기가 있으면 대기 없음
        assertEquals(40, a.timeLimitMin)           // "1회 40분 이내" → 40
        // B: 하나뿐인 충전기가 10분째 충전 중 → 보통 35분 세션으로 보고 25분 대기
        assertEquals(25, stations.first { it.id == "PX000002" }.expectedWaitMin)
    }

    @Test
    fun aggregate_statusOverridesInfo() {
        val statuses = listOf(ChargerStatus("PX000002", "01", 2, null))
        val b = StationAggregator.aggregate(EvChargerParser.parseInfo(body), statuses, now).first { it.id == "PX000002" }
        assertEquals(1, b.availableChargers)
        assertEquals(0, b.expectedWaitMin)
    }

    @Test
    fun project_alongRouteAndCorridor() {
        val (along, detour) = RouteProjector.project(route, GeoPoint(37.81, 127.67), corridorKm = 5.0)!!
        assertTrue("along=$along", along in 50.0..90.0)
        assertTrue("detour=$detour", detour < 5.0)
        // 경로 뒤쪽이거나 너무 먼 곳은 뺀다.
        assertNull(RouteProjector.project(route, GeoPoint(37.40, 126.80), 5.0))
        assertNull(RouteProjector.project(route, GeoPoint(36.00, 126.00), 5.0))
    }

    @Test
    fun publicData_cachesInfoAndUsesRoute() = runTest {
        var calls = 0
        var clock = 0L
        val data = PublicChargerData(
            http = { op, _ -> calls++; if (op == "getChargerInfo") body else """{"resultCode":"00","items":{"item":[]}}""" },
            zcodes = listOf("11"),
            corridorKm = 5.0,
            nowMillis = { clock },
            nowLocal = { now }
        )
        val first = data.stationsAlongRoute(route)
        assertEquals(listOf("PX000001", "PX000002"), first.map { it.id })
        assertEquals(40, first.first().rule.timeLimitMin)
        data.stationsAlongRoute(route)
        assertEquals(2, calls)                     // 캐시가 살아 있으면 다시 부르지 않는다
        clock += 3 * 60 * 1000L                    // 상태 캐시(2분)만 만료
        data.stationsAlongRoute(route)
        assertEquals(3, calls)
    }

    @Test
    fun fallback_usesSampleWhenPrimaryFails() = runTest {
        val sample = object : ChargerDataPort {
            override suspend fun stationsAlongRoute(route: RouteState) = listOf(
                ChargingStation("s", "샘플", GeoPoint(0.0, 0.0), true, 10.0, 0.0, 50.0, 2, null, null, ChargerRule(null, null))
            )
            override fun liveStatus(stationId: String): Flow<StationLiveStatus?> = flowOf(null)
        }
        val broken = PublicChargerData(http = { _, _ -> error("offline") }, zcodes = listOf("11"))
        var fellBack = false
        val data = FallbackChargerData(broken, sample) { fellBack = true }
        assertEquals("s", data.stationsAlongRoute(route).single().id)
        assertTrue(fellBack)
    }
}
