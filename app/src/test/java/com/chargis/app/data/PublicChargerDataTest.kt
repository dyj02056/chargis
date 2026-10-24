package com.chargis.app.data

import com.chargis.app.data.publicapi.ChargerItem
import com.chargis.app.data.publicapi.ChargerStatus
import com.chargis.app.data.publicapi.EvChargerParser
import com.chargis.app.data.publicapi.FallbackChargerData
import com.chargis.app.data.publicapi.PublicChargerData
import com.chargis.app.data.publicapi.RouteProjector
import com.chargis.app.data.publicapi.StationAggregator
import com.chargis.app.data.publicapi.StationKinds
import com.chargis.app.domain.model.ChargerRule
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.OpenHours
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.StationKind
import com.chargis.app.domain.model.StationLiveStatus
import com.chargis.app.port.ChargerDataPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(11, items.size)
        assertEquals(100.0, items.first().outputKw!!, 1e-9)
        assertEquals(2, items.first().stat)
        assertEquals("C001", items.first().kindDetail)
        assertEquals("24시간 이용가능", items.first().useTime)
    }

    /** 누구나 쓸 수 있는 급속 충전소는 휴게소가 아니어도 남기고, 아파트·이용자 제한 충전소는 뺀다. */
    @Test
    fun aggregate_keepsPublicFastChargersOnly() {
        val stations = StationAggregator.aggregate(EvChargerParser.parseInfo(body), emptyList(), now)
        assertEquals(
            listOf("PX000001", "PX000002", "PX000003", "PX000004", "PX000007", "PX000009"),
            stations.map { it.id }.sorted()
        )
        val a = stations.first { it.id == "PX000001" }
        assertEquals(StationKind.HIGHWAY_REST_STOP, a.kind)
        assertEquals(200.0, a.maxKw, 1e-9)        // 완속(7kW)은 빠지고 가장 빠른 충전기 기준
        assertEquals(2, a.totalChargers)
        assertEquals(1, a.availableChargers)
        assertEquals(0, a.expectedWaitMin)         // 빈 충전기가 있으면 대기 없음
        assertEquals(40, a.timeLimitMin)           // 이용자 제한 사유가 "1회 40분 이내"뿐이면 시간 제한 40분으로 본다
        assertEquals(OpenHours.ALL_DAY, a.openHours)
        // B: 하나뿐인 충전기가 10분째 충전 중 → 보통 35분 세션으로 보고 25분 대기. 시설 코드가 없으면 이름으로 짐작
        val b = stations.first { it.id == "PX000002" }
        assertEquals(25, b.expectedWaitMin)
        assertEquals(StationKind.HIGHWAY_REST_STOP, b.kind)
        assertNull(b.timeLimitMin)                 // 제한 문구가 없으면 시간 제한 미상
        // 공영주차장: 공공시설, 09~22시, 주차 유료
        val parking = stations.first { it.id == "PX000003" }
        assertEquals(StationKind.PUBLIC, parking.kind)
        assertEquals(OpenHours(9 * 60, 22 * 60), parking.openHours)
        assertEquals(false, parking.parkingFree)
        assertEquals(StationKind.SHELTER, stations.first { it.id == "PX000007" }.kind)
        val mart = stations.first { it.id == "PX000009" }
        assertEquals(StationKind.COMMERCIAL, mart.kind)
        assertEquals(OpenHours(10 * 60, 22 * 60), mart.openHours)   // "10시~22시"
        assertEquals(true, mart.parkingFree)
    }

    /** limitYn/limitDetail은 이용자 제한이다. 시간 제한은 분 단위 제한 문구가 분명할 때만 꺼낸다. */
    @Test
    fun limitDetail_isUserRestrictionNotTimeLimit() {
        fun item(limitYn: String?, detail: String?, useTime: String? = null) =
            ChargerItem("X", "01", "가상", 37.0, 127.0, "04", 100.0, 2, null, limitYn, detail, useTime = useTime)
        assertTrue(StationAggregator.isUserRestricted(item("Y", "입주민 외 이용불가")))
        assertTrue(StationAggregator.isUserRestricted(item("Y", "")))          // 제한인데 사유가 없으면 제한으로 본다
        assertTrue(StationAggregator.isUserRestricted(item("N", "외부차량 출입 불가")))
        assertTrue(StationAggregator.isUserRestricted(item(null, null, useTime = "관계자 외 이용 불가")))
        assertFalse(StationAggregator.isUserRestricted(item("Y", "1회 40분 이내 충전")))
        assertFalse(StationAggregator.isUserRestricted(item("N", null)))

        assertEquals(40, StationAggregator.parseLimit("1회 40분 이내 충전"))
        assertEquals(60, StationAggregator.parseLimit("최대 60분"))
        assertEquals(30, StationAggregator.parseLimit("30분 제한"))
        assertNull(StationAggregator.parseLimit("입주민 외 이용불가"))
        assertNull(StationAggregator.parseLimit("IC에서 5분 거리"))
    }

    /** 시설 구분 코드 → 종류. 외부인이 쓰기 어려운 시설은 빠진다. */
    @Test
    fun stationKinds_mapCodes() {
        fun item(kind: String?, detail: String?, name: String = "가상") =
            ChargerItem("X", "01", name, 37.0, 127.0, "04", 100.0, 2, null, null, null, kind = kind, kindDetail = detail)
        assertEquals(StationKind.LOCAL_REST_STOP, StationKinds.of(item("C0", "C002")))
        assertEquals(StationKind.COMMERCIAL, StationKinds.of(item("E0", "E007")))   // 주유소
        assertEquals(StationKind.PUBLIC, StationKinds.of(item("A0", "A001")))
        assertEquals(StationKind.OTHER, StationKinds.of(item("D0", "D007")))
        assertEquals(StationKind.SHELTER, StationKinds.of(item(null, null, "가상졸음쉼터")))
        assertFalse(StationKinds.isOpenFacility(item("H0", "H001")))
        assertFalse(StationKinds.isOpenFacility(item("J0", "J001")))              // 학교
        assertTrue(StationKinds.isOpenFacility(item("J0", "J004")))               // 공연장
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
        assertEquals(listOf("PX000001", "PX000003", "PX000002", "PX000007", "PX000009"), first.map { it.id })
        assertEquals(40, first.first().rule.timeLimitMin)
        // 휴게소·쉼터는 본선에 붙어 있으므로 우회 0, 주차장은 직선 경로에서 떨어진 만큼 편도 우회
        assertEquals(0.0, first.first { it.id == "PX000001" }.detourKm, 1e-9)
        assertEquals(0.0, first.first { it.id == "PX000007" }.detourKm, 1e-9)
        assertTrue(first.first { it.id == "PX000003" }.detourKm > 0.0)
        assertEquals(StationKind.PUBLIC, first.first { it.id == "PX000003" }.kind)
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
                ChargingStation("s", "샘플", GeoPoint(0.0, 0.0), StationKind.HIGHWAY_REST_STOP, 10.0, 0.0, 50.0, 2, null, null, ChargerRule(null, null))
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
