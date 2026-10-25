package com.chargis.app.data

import com.chargis.app.data.publicapi.ChargerItem
import com.chargis.app.data.publicapi.ChargerRuleTable
import com.chargis.app.data.publicapi.RestStopDirection
import com.chargis.app.data.publicapi.SidoCodes
import com.chargis.app.data.publicapi.UrlEvChargerHttp
import com.chargis.app.data.publicapi.ChargerStatus
import com.chargis.app.data.publicapi.ConnectorCodes
import com.chargis.app.data.publicapi.EvChargerParser
import com.chargis.app.data.publicapi.FallbackChargerData
import com.chargis.app.data.publicapi.PublicChargerData
import com.chargis.app.data.publicapi.RouteProjector
import com.chargis.app.data.publicapi.StationAggregator
import com.chargis.app.data.publicapi.StationKinds
import com.chargis.app.domain.model.ChargerRule
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.ConnectorType
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.OpenHours
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.StationKind
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
        assertEquals(12, items.size)
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
            listOf("PX000001", "PX000002", "PX000003", "PX000004", "PX000007", "PX000009", "PX000010"),
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
        // 반대 방향 휴게소("가상K휴게소(서울방향)")는 서울→속초 경로에서 뺀다.
        assertTrue(first.none { it.id == "PX000010" })
        // 시간 제한: 문구에서 읽은 A는 확인됨, 문구도 규칙표도 없는 B는 "정보 없음"
        assertTrue(first.first { it.id == "PX000001" }.rule.timeLimitKnown)
        assertFalse(first.first { it.id == "PX000002" }.rule.timeLimitKnown)
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

    /** 규칙표: 운영기관·종류가 맞는 첫 규칙의 시간 제한을 쓰고, 맞는 규칙이 없으면 "정보 없음"으로 둔다. */
    @Test
    fun ruleTable_fillsTimeLimitWhenTextHasNone() = runTest {
        val rules = ChargerRuleTable.parse(
            """{"rules":[{"operatorId":"EX","kind":"HIGHWAY_REST_STOP","timeLimitMin":40,"source":"테스트용 가정"}]}"""
        )
        assertEquals(40, rules.timeLimitFor("EX", StationKind.HIGHWAY_REST_STOP))
        assertNull(rules.timeLimitFor("EX", StationKind.COMMERCIAL))
        assertNull(rules.timeLimitFor(null, StationKind.HIGHWAY_REST_STOP))
        assertEquals(ChargerRuleTable.EMPTY.timeLimitFor("EX", StationKind.HIGHWAY_REST_STOP), ChargerRuleTable.parse("깨진 JSON").timeLimitFor("EX", StationKind.HIGHWAY_REST_STOP))

        // 반대로 가는 경로(속초→서울)에서는 K휴게소(서울방향)가 남고, 규칙표의 40분이 붙는다.
        val back = RouteState(GeoPoint(38.20, 128.59), GeoPoint(37.55, 127.05), 210.0, 126)
        val data = PublicChargerData(
            http = { op, _ -> if (op == "getChargerInfo") body else """{"resultCode":"00","items":{"item":[]}}""" },
            zcodes = listOf("11"),
            corridorKm = 5.0,
            rules = rules,
            nowLocal = { now }
        )
        val k = data.stationsAlongRoute(back).first { it.id == "PX000010" }
        assertEquals(40, k.rule.timeLimitMin)
        assertTrue(k.rule.timeLimitKnown)
        assertTrue(data.stationsAlongRoute(back).none { it.id == "PX000001" })   // A(속초방향)는 반대편
    }

    /** 이름의 "(○○방향)" 도시가 진행 방향 뒤쪽이면 반대편 휴게소다. 양방향·표기 없음·모르는 도시는 판단하지 않는다. */
    @Test
    fun restStopDirection_usesDirectionCity() {
        val here = GeoPoint(37.80, 127.60)
        assertTrue(RestStopDirection.isOpposite("가상휴게소(서울방향)", here, route))
        assertFalse(RestStopDirection.isOpposite("가상휴게소(속초방향)", here, route))
        assertFalse(RestStopDirection.isOpposite("가상휴게소(양방향)", here, route))
        assertFalse(RestStopDirection.isOpposite("가상휴게소", here, route))
        assertFalse(RestStopDirection.isOpposite("가상휴게소(없는도시방향)", here, route))
        assertEquals("강릉", RestStopDirection.directionCity("가상휴게소( 강릉 방향 )"))
    }

    /** 12: 조회할 시도는 경로가 지나는 곳으로 고른다. 서울→부산이면 충청·경상까지, 서울→속초면 강원까지. */
    @Test
    fun sidoCodes_followRoute() {
        val busan = SidoCodes.along(RouteState(GeoPoint(37.55, 127.05), GeoPoint(35.18, 129.07), 330.0, 200))
        assertTrue(busan.toString(), busan.containsAll(listOf("11", "41", "43", "47", "26")))
        assertFalse(busan.contains("50"))                       // 제주는 지나지 않는다
        val sokcho = SidoCodes.along(route)
        assertTrue(sokcho.toString(), sokcho.containsAll(listOf("11", "41", "51")))
        assertFalse(sokcho.contains("26"))
    }

    /** 12: 시도를 고정하지 않으면 경로가 지나는 시도마다 따로 받고, 같은 시도는 캐시로 다시 받지 않는다. */
    @Test
    fun publicData_queriesZonesAlongRoute() = runTest {
        val asked = mutableListOf<String>()
        val data = PublicChargerData(
            http = { op, p -> if (op == "getChargerInfo") asked += p.getValue("zcode"); """{"resultCode":"00","items":{"item":[]}}""" },
            nowMillis = { 0L },
            nowLocal = { now }
        )
        data.stationsAlongRoute(route)
        assertEquals(SidoCodes.along(route), asked.sorted())
        asked.clear()
        data.stationsAlongRoute(route)
        assertTrue(asked.isEmpty())
    }

    /** 10: 키가 있으면 주소에 붙이고, 비어 있으면(프록시가 붙이는 경우) 붙이지 않는다. 프록시 주소를 쓸 수 있다. */
    @Test
    fun http_addsKeyOnlyWhenPresent() {
        val direct = UrlEvChargerHttp("abc").urlFor("getChargerInfo", mapOf("zcode" to "11"))
        assertTrue(direct.startsWith(UrlEvChargerHttp.DEFAULT_BASE + "/getChargerInfo?"))
        assertTrue(direct.contains("serviceKey=abc"))
        val proxied = UrlEvChargerHttp("", "https://proxy.example.test/ev/").urlFor("getChargerInfo", mapOf("zcode" to "11"))
        assertTrue(proxied.startsWith("https://proxy.example.test/ev/getChargerInfo?"))
        assertFalse(proxied.contains("serviceKey"))
    }

    // 충전기 한 대(가상). 나머지 값은 공용 고속도로 휴게소 급속 충전기로 둔다.
    private fun charger(id: String, type: String?, stat: Int? = 2, lastTedt: String? = null, kw: Double = 100.0) = ChargerItem(
        statId = "PX9", chgerId = id, statNm = "X 휴게소", lat = 37.7, lng = 127.6, chgerType = type, outputKw = kw,
        stat = stat, nowTsdt = null, limitYn = "N", limitDetail = null, kind = "C0", kindDetail = "C001", lastTedt = lastTedt
    )

    @Test
    fun connectorCodes_mapChargerTypes() {
        assertEquals(setOf(ConnectorType.CHADEMO), ConnectorCodes.of("01"))
        assertEquals(setOf(ConnectorType.DC_COMBO), ConnectorCodes.of("04"))
        assertEquals(setOf(ConnectorType.CHADEMO, ConnectorType.AC3, ConnectorType.DC_COMBO), ConnectorCodes.of("06"))
        assertNull(ConnectorCodes.of("02"))
        assertNull(ConnectorCodes.of(null))
    }

    /** DC콤보 차에게는 차데모 전용 충전기를 빈 대수·출력에 넣지 않는다. 타입을 모르는 충전기는 남긴다. */
    @Test
    fun aggregate_countsOnlyChargersTheCarCanUse() {
        val items = listOf(
            charger("1", "01", stat = 2, kw = 200.0),   // 차데모 전용, 비어 있음
            charger("2", "04", stat = 3),               // DC콤보, 충전 중
            charger("3", null, stat = 2)                // 타입 미상, 비어 있음
        )
        val combo = StationAggregator.aggregate(items, emptyList(), now, setOf(ConnectorType.DC_COMBO)).single()
        assertEquals(2, combo.totalChargers)
        assertEquals(1, combo.availableChargers)
        assertEquals(100.0, combo.maxKw, 1e-9)
        assertEquals(setOf(ConnectorType.DC_COMBO), combo.connectors)
        val any = StationAggregator.aggregate(items, emptyList(), now).single()
        assertEquals(3, any.totalChargers)
        assertEquals(setOf(ConnectorType.CHADEMO, ConnectorType.DC_COMBO), any.connectors)
    }

    /** 고장·점검 중인 충전기 수와, 마지막 충전이 끝난 뒤 지난 시간을 센다. 충전 중인 충전기가 있으면 0시간. */
    @Test
    fun aggregate_reportsBrokenChargersAndLastCharge() {
        val idle = StationAggregator.aggregate(
            listOf(charger("1", "04", stat = 4), charger("2", "04", stat = 2, lastTedt = "20261022102000")), emptyList(), now
        ).single()
        assertEquals(1, idle.brokenChargers)
        assertEquals(1, idle.totalChargers)
        assertEquals(72.0, idle.hoursSinceLastCharge!!, 1e-9)
        val busy = StationAggregator.aggregate(listOf(charger("1", "04", stat = 3, lastTedt = "20261001000000")), emptyList(), now).single()
        assertEquals(0.0, busy.hoursSinceLastCharge!!, 1e-9)
        // 상태 API의 마지막 충전 시각이 목록보다 최신이면 그것을 쓴다.
        val updated = StationAggregator.aggregate(
            listOf(charger("1", "04", lastTedt = "20261001000000")),
            listOf(ChargerStatus("PX9", "1", 2, null, "20261025092000")), now
        ).single()
        assertEquals(1.0, updated.hoursSinceLastCharge!!, 1e-9)
        assertNull(StationAggregator.aggregate(listOf(charger("1", "04")), emptyList(), now).single().hoursSinceLastCharge)
    }

    /** 규칙표의 요금은 운영기관·시설 종류로 찾는다. 시간 제한만 적힌 규칙은 요금을 주지 않는다. */
    @Test
    fun ruleTable_findsPriceSeparatelyFromTimeLimit() {
        val table = ChargerRuleTable.parse(
            """{"rules":[
              {"operatorId":"ME","timeLimitMin":40,"source":"t"},
              {"operatorId":"ME","pricePerKwh":347.2,"source":"t"},
              {"kind":"COMMERCIAL","pricePerKwh":385,"source":"t"}
            ]}"""
        )
        assertEquals(40, table.timeLimitFor("ME", StationKind.HIGHWAY_REST_STOP))
        assertEquals(347.2, table.priceFor("ME", StationKind.HIGHWAY_REST_STOP)!!, 1e-9)
        assertEquals(385.0, table.priceFor("XX", StationKind.COMMERCIAL)!!, 1e-9)
        assertNull(table.priceFor("XX", StationKind.HIGHWAY_REST_STOP))
        assertNull(table.timeLimitFor("XX", StationKind.COMMERCIAL))
    }

    @Test
    fun fallback_usesSampleWhenPrimaryFails() = runTest {
        val sample = object : ChargerDataPort {
            override suspend fun stationsAlongRoute(route: RouteState, connectors: Set<ConnectorType>?) = listOf(
                ChargingStation("s", "샘플", GeoPoint(0.0, 0.0), StationKind.HIGHWAY_REST_STOP, 10.0, 0.0, 50.0, 2, null, null, ChargerRule(null, null))
            )
        }
        val broken = PublicChargerData(http = { _, _ -> error("offline") }, zcodes = listOf("11"))
        var fellBack = false
        val data = FallbackChargerData(broken, sample) { fellBack = true }
        assertEquals("s", data.stationsAlongRoute(route).single().id)
        assertTrue(fellBack)
    }
}
