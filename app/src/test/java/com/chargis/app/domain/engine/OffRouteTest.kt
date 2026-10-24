package com.chargis.app.domain.engine

import com.chargis.app.domain.model.OpenHours
import com.chargis.app.domain.model.ReasonCode
import com.chargis.app.domain.model.RecommendContext
import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.domain.model.RecommendationResult
import com.chargis.app.domain.model.StationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 휴게소 밖 충전소 테스트.
 * 졸음쉼터·국도변·상업시설 충전소를 후보로 넣었을 때 왕복 우회, 진출입 시간, 휴게소 우선, 이용 가능 시간을 확인한다.
 */
class OffRouteTest {
    private val config = RecommendationConfig()
    private val engine = RecommendationEngine(config)

    private fun recommended(r: RecommendationResult) = r as RecommendationResult.Recommended

    /** 경로에서 5km 떨어진 충전소: 갔다 오는 10km를 주행 시간에 넣고, 진출입 시간을 더하고, 돌아오는 거리까지 채운다. */
    @Test
    fun detour_isCountedBothWays() {
        val s = station("M", 90.0, waitMin = 0, detourKm = 5.0, kind = StationKind.COMMERCIAL)
        val rec = recommended(engine.recommend(route(210.0), vehicle(60.0), listOf(s), defaultProfile)).primary
        // 도착 잔량: 경로 90km + 편도 5km
        assertEquals(ChargeMath.arrivalSoc(60.0, 95.0, defaultProfile), rec.arrivalSocPercent, 1e-9)
        // 목표: 돌아오는 5km + 남은 120km를 안전 마진과 함께
        val needed = ChargeMath.requiredSoc(125.0, defaultProfile, config)
        assertEquals(ChargeMath.ceilToStep(needed, config.roundUpStepPercent), rec.targetSocPercent, 1e-9)
        // 도착 시간: (210 + 10)km를 시속 100km로 132분 + 충전 + 들르는 시간 + 진출입 시간
        val charge = ChargeMath.chargeMinutes(rec.arrivalSocPercent, rec.targetSocPercent, 100.0, defaultProfile, config)
        assertEquals(132.0 + charge + config.stopOverheadMin + config.offRouteAccessMin, rec.etaMinutes, 1e-6)
        assertFalse(ReasonCode.NO_DETOUR in rec.reasonCodes)
    }

    /** 조건이 같으면 휴게소·쉼터를 상업시설보다 먼저 고른다(순위 가산). 도착 시간 자체는 같다. */
    @Test
    fun roadside_isPreferredWhenEqual() {
        val stations = listOf(
            station("mart", 90.0, kind = StationKind.COMMERCIAL),
            station("rest", 90.0, kind = StationKind.HIGHWAY_REST_STOP)
        )
        val result = recommended(engine.recommend(route(210.0), vehicle(60.0), stations, defaultProfile))
        assertEquals("rest", result.primary.station.id)
        val mart = result.alternatives.single()
        assertEquals(result.primary.etaMinutes, mart.etaMinutes, 1e-6)
        assertEquals(config.nonRoadsidePenaltyMin, mart.costMinutes - result.primary.costMinutes, 1e-6)
    }

    /** 졸음쉼터는 휴게소처럼 순위 가산이 없다. */
    @Test
    fun shelter_hasNoPenalty() {
        val stations = listOf(
            station("shelter", 90.0, kind = StationKind.SHELTER),
            station("rest", 90.0, kind = StationKind.HIGHWAY_REST_STOP)
        )
        val result = recommended(engine.recommend(route(210.0), vehicle(60.0), stations, defaultProfile))
        assertEquals(result.primary.costMinutes, result.alternatives.single().costMinutes, 1e-6)
    }

    /** 휴게소가 크게 붐비면, 조금 벗어나도 빈 충전기가 많은 마트가 혼잡 비교의 대안으로 나오고 더 빨리 도착한다. */
    @Test
    fun offRoute_becomesAlternativeWhenRestStopCongested() {
        val stations = listOf(
            station("rest", 90.0, maxKw = 100.0, available = 0, waitMin = 40),
            station("mart", 95.0, maxKw = 200.0, total = 8, available = 5, waitMin = 0, detourKm = 2.5, kind = StationKind.COMMERCIAL)
        )
        val result = recommended(engine.recommend(route(210.0), vehicle(60.0), stations, defaultProfile))
        assertTrue(result.congested)
        assertEquals("rest", result.primary.station.id)
        val mart = result.alternatives.first()
        assertEquals("mart", mart.station.id)
        assertTrue(mart.etaMinutes < result.primary.etaMinutes)
    }

    /** 이용 가능 시간을 알면, 도착할 때 닫혀 있거나 곧 닫는 곳은 후보에서 뺀다. 지금 시각을 모르면 빼지 않는다. */
    @Test
    fun closedStation_isExcluded() {
        val hours = OpenHours(10 * 60, 22 * 60)
        val stations = listOf(
            station("mart", 90.0, maxKw = 200.0, waitMin = 0, kind = StationKind.COMMERCIAL, openHours = hours),
            station("rest", 130.0, maxKw = 50.0)
        )
        // 8시 출발 → 90km 지점 8시 54분 도착: 아직 열지 않았다
        val morning = recommended(engine.recommend(route(210.0), vehicle(60.0), stations, defaultProfile, RecommendContext(minuteOfDay = 8 * 60)))
        assertTrue(morning.alternatives.none { it.station.id == "mart" } && morning.primary.station.id == "rest")
        // 12시 출발 → 열려 있다
        val noon = recommended(engine.recommend(route(210.0), vehicle(60.0), stations, defaultProfile, RecommendContext(minuteOfDay = 12 * 60)))
        assertTrue((listOf(noon.primary) + noon.alternatives).any { it.station.id == "mart" })
        // 21시 출발 → 21시 54분 도착: 도착 후 30분을 못 채우고 닫는다
        val late = recommended(engine.recommend(route(210.0), vehicle(60.0), stations, defaultProfile, RecommendContext(minuteOfDay = 21 * 60)))
        assertEquals("rest", late.primary.station.id)
        assertTrue(late.alternatives.isEmpty())
        // 시각을 모르면 빼지 않는다
        val unknown = recommended(engine.recommend(route(210.0), vehicle(60.0), stations, defaultProfile))
        assertTrue((listOf(unknown.primary) + unknown.alternatives).any { it.station.id == "mart" })
    }

    /** 이용 가능 시간: 자정을 넘기는 시간대, 하루 종일, 글자 해석. */
    @Test
    fun openHours_coversAndParses() {
        val night = OpenHours(18 * 60, 2 * 60)
        assertTrue(night.covers(23 * 60, 23 * 60 + 30))
        assertTrue(night.covers(60, 90))                     // 새벽 1시: 어제 18시에 열린 시간대
        assertFalse(night.covers(90, 150))                   // 2시에 닫는다
        assertFalse(night.covers(12 * 60, 12 * 60 + 10))
        assertTrue(OpenHours.ALL_DAY.covers(23 * 60, 25 * 60))
        assertEquals(OpenHours(9 * 60, 22 * 60 + 30), OpenHours.parse("평일 09:00 ~ 22:30"))
        assertEquals(OpenHours(18 * 60, 0), OpenHours.parse("18시~24시"))
        assertEquals(OpenHours.ALL_DAY, OpenHours.parse("24시간 이용가능"))
        assertEquals(OpenHours.ALL_DAY, OpenHours.parse("00:00~24:00"))
        assertEquals(null, OpenHours.parse("이용가능"))
    }
}
