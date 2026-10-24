package com.chargis.app.domain.engine

import com.chargis.app.domain.model.ReasonCode
import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.domain.model.RecommendationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 추천 엔진 테스트. T1~T7은 plan.md에 적어 둔 시험 상황 번호다.
 * 각 테스트는 "이런 상황이면(입력) → 이렇게 추천해야 한다(기대 결과)"를 확인한다.
 * route(남은 km), vehicle(배터리 %), station(id, 거리 km) 같은 재료는 TestFixtures.kt에 있다.
 */
class RecommendationEngineTest {
    private val config = RecommendationConfig()
    private val engine = RecommendationEngine(config)

    /** T1: soc 60%, 남은 210km, 후보 A 90km → 도착 ≈36.7%, 목표 50% */
    @Test
    fun t1_basicRecommendation() {
        assertEquals(72.4, ChargeMath.requiredSoc(210.0, defaultProfile, config), 0.1)

        val result = engine.recommend(route(210.0), vehicle(60.0), listOf(station("A", 90.0)), defaultProfile)

        val rec = (result as RecommendationResult.Recommended).primary
        assertEquals(36.7, rec.arrivalSocPercent, 0.1)
        assertEquals(50.0, rec.targetSocPercent, 1e-9)
        assertFalse(rec.needsAnotherStop)
        assertEquals("A", result.latestSafeStopId)
    }

    /** T2(엔진 경유): 50kW, 40분 제한, 목표 80% 상한 → 제한 초과 + 위험 표시 */
    @Test
    fun t2_timeLimitRiskThroughEngine() {
        // soc 30%, 39km 앞(도착 ≈20%), 남은 400km → 필요 SoC가 상한(80%)을 넘는다
        val result = engine.recommend(
            route(400.0),
            vehicle(30.0),
            listOf(station("B", 38.7, maxKw = 50.0, timeLimitMin = 40)),
            defaultProfile
        )
        val rec = (result as RecommendationResult.Recommended).primary
        assertEquals(20.0, rec.arrivalSocPercent, 0.1)
        assertEquals(80.0, rec.targetSocPercent, 1e-9)
        assertEquals(75, rec.chargeMinutes) // 74.3분 올림
        assertTrue(rec.willHitTimeLimit)
        assertEquals(52.3, rec.socAtTimeLimitPercent!!, 0.1)
        assertTrue(rec.needsAnotherStop)
        assertTrue(ReasonCode.TIME_LIMIT_RISK in rec.reasonCodes)
    }

    /** T3: soc 90%, 남은 100km → 충전 불필요 (필요 ≈39.7%; plan.md의 36.7%는 오기) */
    @Test
    fun t3_notNeeded() {
        assertEquals(39.7, ChargeMath.requiredSoc(100.0, defaultProfile, config), 0.1)
        val result = engine.recommend(route(100.0), vehicle(90.0), listOf(station("A", 50.0)), defaultProfile)
        assertEquals(RecommendationResult.NotNeeded, result)
    }

    /** T4: 모든 후보 도착 SoC < 10% → 추천 없음 + 가장 가까운 충전소 경고 */
    @Test
    fun t4_noSafeStop() {
        // soc 15% → 20km 앞 도착 ≈9.8%, 40km 앞 ≈4.7%
        val result = engine.recommend(
            route(200.0),
            vehicle(15.0),
            listOf(station("far", 40.0), station("near", 20.0)),
            defaultProfile
        )
        val noSafe = result as RecommendationResult.NoSafeStop
        assertEquals("near", noSafe.nearest?.id)
    }

    /** T5: availableChargers == null → 중립 처리, 크래시 없음, ENOUGH_CHARGERS 미표시 */
    @Test
    fun t5_unknownAvailabilityIsNeutral() {
        val result = engine.recommend(
            route(210.0),
            vehicle(60.0),
            listOf(station("unknown", 90.0, available = null), station("known", 60.0, total = 4, available = 4)),
            defaultProfile
        )
        val rec = result as RecommendationResult.Recommended
        val unknown = (listOf(rec.primary) + rec.alternatives).first { it.station.id == "unknown" }
        assertFalse(ReasonCode.ENOUGH_CHARGERS in unknown.reasonCodes)
        assertTrue(unknown.score.isFinite())
    }

    /** T6: 1순위 대기 30분(임계 20분 초과) → 혼잡 표시 + 대안 포함 */
    @Test
    fun t6_congestionIncludesAlternatives() {
        val stations = listOf(
            station("busy", 90.0, maxKw = 200.0, total = 6, available = 0, waitMin = 30),
            station("alt1", 130.0, maxKw = 50.0, total = 4, available = 3, waitMin = 5),
            station("alt2", 170.0, maxKw = 50.0, total = 2, available = 1, waitMin = 3)
        )
        val result = engine.recommend(route(210.0), vehicle(60.0), stations, defaultProfile)
        val rec = result as RecommendationResult.Recommended
        assertEquals("busy", rec.primary.station.id)
        assertTrue(rec.congested)
        assertTrue(rec.alternatives.size >= 2)
    }

    /** T7: 같은 추천을 반복 평가해도 이미 안내한 휴게소는 재안내하지 않는다 */
    @Test
    fun t7_noReannouncement() {
        val gate = AnnouncementGate(config.announceWithinKm)
        val stations = listOf(station("A", 45.0), station("B", 90.0))
        var announcements = 0
        repeat(5) {
            val rec = engine.recommend(route(210.0), vehicle(60.0), stations, defaultProfile)
                as RecommendationResult.Recommended
            if (gate.shouldAnnounce(rec.primary)) {
                announcements++
                gate.markAnnounced(rec.primary.station.id)
            }
        }
        assertEquals(1, announcements)
    }

    /** T7: 수락한 휴게소는 작은 변화에는 그대로 두고, 후보에서 사라질 때만 바꾼다(히스테리시스). */
    @Test
    fun t7_acceptedChoiceSticksUnlessMuchWorse() {
        val gate = AnnouncementGate(config.announceWithinKm)
        val first = engine.recommend(route(210.0), vehicle(60.0), listOf(station("A", 90.0), station("B", 120.0)), defaultProfile)
            as RecommendationResult.Recommended
        val accepted = first.primary

        // 상태가 조금 변해도(soc 0.5% 하락) 유지
        val slightly = engine.recommend(route(210.0), vehicle(59.5), listOf(station("A", 90.0), station("B", 120.0)), defaultProfile)
        assertEquals(null, gate.replacementFor(accepted, slightly))

        // 수락한 휴게소가 후보에서 사라지면 교체
        val gone = engine.recommend(route(210.0), vehicle(60.0), listOf(station("B", 120.0), station("C", 150.0)), defaultProfile)
        assertNotNull(gate.replacementFor(accepted, gone))
    }
}
