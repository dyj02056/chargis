package com.chargis.app.domain.engine

import com.chargis.app.domain.model.CurvePoint
import com.chargis.app.domain.model.ReasonCode
import com.chargis.app.domain.model.RecommendContext
import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.domain.model.RecommendationResult
import com.chargis.app.domain.model.WaitSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 고도화한 추천 엔진 테스트.
 * B1 총 소요 시간 비교, B2 두 번 들르기 계획, B3 충전 곡선·전비 보정, B4 도착 시각 대기 예측을 확인한다.
 */
class PlanningTest {
    private val config = RecommendationConfig()
    private val engine = RecommendationEngine(config)

    private fun recommended(r: RecommendationResult) = r as RecommendationResult.Recommended
    private fun all(r: RecommendationResult.Recommended) = listOf(r.primary) + r.alternatives

    /** B1: 대기가 같으면 충전이 빠른 곳이 먼저 도착하므로 1순위가 되고, 배지가 붙는다. */
    @Test
    fun b1_ranksByArrivalTime() {
        val result = recommended(
            engine.recommend(
                route(210.0), vehicle(60.0),
                listOf(station("slow", 60.0, maxKw = 50.0), station("fast", 90.0, maxKw = 150.0)),
                defaultProfile
            )
        )
        assertEquals("fast", result.primary.station.id)
        assertTrue(ReasonCode.FASTEST_ARRIVAL in result.primary.reasonCodes)
        val slow = result.alternatives.single()
        assertTrue(slow.etaMinutes > result.primary.etaMinutes)
        // 비용은 도착 시간에 위험 가산만 더한 값이다.
        assertTrue(result.primary.costMinutes >= result.primary.etaMinutes)
    }

    /** B1: 도착 시간 = 주행 + 대기 + 충전 + 들르는 시간. 남은 210km를 시속 100km로 가면 주행만 126분. */
    @Test
    fun b1_etaAddsDriveWaitChargeAndOverhead() {
        val rec = recommended(engine.recommend(route(210.0), vehicle(60.0), listOf(station("A", 90.0, waitMin = 7)), defaultProfile)).primary
        val charge = ChargeMath.chargeMinutes(rec.arrivalSocPercent, rec.targetSocPercent, 100.0, defaultProfile, config)
        assertEquals(126.0 + 7 + charge + config.stopOverheadMin, rec.etaMinutes, 1e-6)
    }

    /** B1 혼잡: 붐비지 않았다면 골랐을 곳이 30분 대기면 그곳을 1순위로 두고, 더 빨리 도착하는 대안과 비교하게 한다. */
    @Test
    fun b1_congestionComparesIdealWithFastest() {
        val stations = listOf(
            station("busy", 90.0, maxKw = 100.0, available = 0, waitMin = 30),
            station("alt", 130.0, maxKw = 50.0, available = 3, waitMin = 5)
        )
        val result = recommended(engine.recommend(route(210.0), vehicle(60.0), stations, defaultProfile))
        assertEquals("busy", result.primary.station.id)
        assertTrue(result.congested)
        val alt = result.alternatives.first()
        assertEquals("alt", alt.station.id)
        assertTrue(alt.etaMinutes < result.primary.etaMinutes)
    }

    /** B1: 수락한 곳보다 5분 넘게 빠른 곳이 생기지 않으면 바꾸지 않는다. */
    @Test
    fun b1_gateSwitchesOnlyWhenClearlyFaster() {
        val gate = AnnouncementGate(config.announceWithinKm, config.switchMinutesGap)
        val stations = listOf(station("A", 90.0, waitMin = 5), station("B", 120.0, waitMin = 5))
        val first = recommended(engine.recommend(route(210.0), vehicle(60.0), stations, defaultProfile))
        val accepted = first.primary
        // 다른 곳의 대기가 2분 줄어도 그대로 간다.
        val other = stations.first { it.id != accepted.station.id }
        val slightly = engine.recommend(route(210.0), vehicle(60.0), stations.map { if (it.id == other.id) it.copy(expectedWaitMin = 3) else it }, defaultProfile)
        assertNull(gate.replacementFor(accepted, slightly))
        // 수락한 곳 대기가 30분으로 늘면 바꾼다.
        val jammed = engine.recommend(route(210.0), vehicle(60.0), stations.map { if (it.id == accepted.station.id) it.copy(expectedWaitMin = 30) else it }, defaultProfile)
        assertEquals(other.id, gate.replacementFor(accepted, jammed)?.station?.id)
    }

    /** B2: 한 번으로 못 가면 두 번째 휴게소까지 계획하고, 느린 첫 휴게소에서는 다음 휴게소까지 갈 만큼만 채운다. */
    @Test
    fun b2_twoStopPlanChargesLessAtSlowStop() {
        val stations = listOf(station("slow", 70.0, maxKw = 50.0), station("fast", 180.0, maxKw = 150.0))
        val rec = recommended(engine.recommend(route(330.0), vehicle(45.0), stations, defaultProfile)).primary
        assertEquals("slow", rec.station.id)
        assertNotNull(rec.nextStop)
        val next = rec.nextStop!!
        assertEquals("fast", next.station.id)
        // slow→fast 110km를 안전하게 갈 최소 % = 110×0.2584×1.15+10 ≈ 42.7 → 45%
        assertEquals(45.0, rec.targetSocPercent, 1e-9)
        // fast→목적지 150km ≈ 54.6 → 55%
        assertEquals(55.0, next.targetSocPercent, 1e-9)
        assertTrue(rec.needsAnotherStop)
        assertTrue(next.arrivalSocPercent >= config.minArrivalSocPercent)
    }

    /** B2: 첫 휴게소가 더 빠르면 거기서 더 많이 채워 두 번째 충전을 줄인다. */
    @Test
    fun b2_chargesMoreAtFasterFirstStop() {
        val stations = listOf(station("fast", 70.0, maxKw = 150.0), station("slow", 180.0, maxKw = 50.0))
        val rec = recommended(engine.recommend(route(330.0), vehicle(45.0), stations, defaultProfile)).primary
        assertEquals("fast", rec.station.id)
        val next = rec.nextStop!!
        assertTrue(rec.targetSocPercent > 45.0)
        assertTrue(next.targetSocPercent - next.arrivalSocPercent < 30.0)
    }

    /** B2: 한 번에 갈 수 있으면 두 번 들르는 계획을 고르지 않는다(들를 때마다 시간이 더 든다). */
    @Test
    fun b2_singleStopWhenEnough() {
        val stations = listOf(station("A", 90.0), station("B", 150.0))
        val result = recommended(engine.recommend(route(210.0), vehicle(60.0), stations, defaultProfile))
        all(result).forEach { assertNull(it.nextStop) }
    }

    /** B3: 충전 곡선이 있으면 출력이 줄어드는 높은 SoC 구간이 더 오래 걸린다. 역함수와도 맞아야 한다. */
    @Test
    fun b3_chargeCurve() {
        val curved = defaultProfile.copy(
            chargeCurve = listOf(CurvePoint(0.0, 150.0), CurvePoint(50.0, 120.0), CurvePoint(80.0, 60.0), CurvePoint(100.0, 15.0))
        )
        val low = ChargeMath.chargeMinutes(20.0, 40.0, 200.0, curved, config)
        val high = ChargeMath.chargeMinutes(60.0, 80.0, 200.0, curved, config)
        assertTrue(high > low * 1.5)
        assertEquals(80.0, ChargeMath.socAfterMinutes(60.0, high, 200.0, curved, config), 0.05)
        // 충전기 출력이 곡선보다 낮으면 충전기 쪽이 상한이 된다(50kW × 0.92).
        assertEquals(46.0, ChargeMath.powerKw(30.0, 50.0, curved, config), 1e-9)
        assertEquals(60.0, ChargeMath.powerKw(80.0, 200.0, curved, config), 1e-9)
    }

    /** B3: 실측 소비가 사양보다 20% 많으면 같은 휴게소에서도 더 많이 채우라고 한다. */
    @Test
    fun b3_consumptionFactorRaisesTarget() {
        val stations = listOf(station("A", 90.0))
        val normal = recommended(engine.recommend(route(210.0), vehicle(70.0), stations, defaultProfile)).primary
        val winter = recommended(
            engine.recommend(route(210.0), vehicle(70.0), stations, defaultProfile, RecommendContext(consumptionFactor = 1.2))
        ).primary
        assertTrue(winter.targetSocPercent > normal.targetSocPercent)
        assertTrue(winter.arrivalSocPercent < normal.arrivalSocPercent)
    }

    /** B3: 실측 보정기는 달린 거리가 쌓일수록 사전 추정에서 실측 배율로 옮겨 간다. 충전 구간은 끊어서 잰다. */
    @Test
    fun b3_calibratorConvergesToObserved() {
        val spec = ChargeMath.socPerKm(defaultProfile)
        val cal = ConsumptionCalibrator(fullTrustKm = 20.0)
        var soc = 80.0
        for (km in 0..10) cal.onSample(km.toDouble(), soc).also { soc -= spec * 1.25 }
        val partial = cal.factor(spec, prior = 1.0)
        assertTrue(partial > 1.05 && partial < 1.25)
        // 충전으로 SoC가 올라도 그 구간은 소비 계산에 넣지 않는다.
        soc = 90.0
        for (km in 10..30) cal.onSample(km.toDouble(), soc).also { soc -= spec * 1.25 }
        assertEquals(1.25, cal.factor(spec, prior = 1.0), 0.01)
    }

    /** B3: 거리만 먼저 오고 SoC가 늦게 오는 어긋난 짝(데모의 거리 점프 등)이 섞여도 배율이 부풀지 않는다. */
    @Test
    fun b3_calibratorIgnoresStalePairs() {
        val spec = ChargeMath.socPerKm(defaultProfile)
        val cal = ConsumptionCalibrator(windowKm = 40.0)
        cal.onSample(0.0, 60.0)
        cal.onSample(30.0, 60.0)                 // 거리만 30km 늘고 SoC는 아직 예전 값
        cal.onSample(30.0, 60.0 - 30 * spec)     // 같은 거리에서 SoC 갱신
        cal.onSample(40.0, 60.0 - 30 * spec)     // 다시 어긋난 짝
        cal.onSample(40.0, 60.0 - 40 * spec)
        cal.onSample(41.0, 60.0 - 41 * spec)     // 0km 점이 창(40km) 밖으로 밀려남
        assertEquals(1.0, cal.factor(spec), 0.02)
    }

    /** B3: 사전 추정 — 추울수록, 100km/h보다 빠를수록 많이 쓴다. */
    @Test
    fun b3_priorFactor() {
        assertEquals(1.0, ConsumptionModel.priorFactor(null, null), 1e-9)
        assertEquals(1.0, ConsumptionModel.priorFactor(20.0, 90.0), 1e-9)
        assertEquals(1.16, ConsumptionModel.priorFactor(-5.0, 100.0), 1e-9)
        assertEquals(1.06, ConsumptionModel.priorFactor(15.0, 110.0), 1e-9)
    }

    /** B4: 도착이 멀면 시간대별 통계를, 가까우면 실시간 값을 더 믿는다. */
    @Test
    fun b4_forecastBlendsLiveAndHistory() {
        val byHour = List(24) { if (it in 10..12) 25 else 5 }
        val s = station("A", 50.0, waitMin = 30).copy(waitByHour = byHour)
        val now = WaitForecast.predict(s, 0.0, 9 * 60, 45.0)
        assertEquals(30, now.minutes)
        assertEquals(WaitSource.LIVE, now.source)
        // 3시간 뒤 15시 도착: 실시간 비중 1/16 → 거의 통계값 5분
        val later = WaitForecast.predict(s, 180.0, 15 * 60, 45.0)
        assertEquals(WaitSource.BLENDED, later.source)
        assertEquals(7, later.minutes) // 30/16 + 5×15/16 ≈ 6.6
        // 실시간이 없으면 통계로 예측한다. 10:30은 10시대 25분.
        val noLive = WaitForecast.predict(s.copy(expectedWaitMin = null), 60.0, 10 * 60 + 30, 45.0)
        assertEquals(WaitEstimate(25, WaitSource.FORECAST), noLive)
        assertEquals(WaitEstimate(null, WaitSource.UNKNOWN), WaitForecast.predict(s.copy(expectedWaitMin = null, waitByHour = null), 10.0, null, 45.0))
    }

    /** B4: 지금은 한산해도 도착할 즈음 붐비는 휴게소는 엔진이 대기를 길게 잡는다. */
    @Test
    fun b4_engineUsesArrivalTimeWait() {
        val rush = List(24) { if (it >= 10) 40 else 0 }
        val stations = listOf(station("A", 150.0, waitMin = 0).copy(waitByHour = rush))
        val rec = recommended(
            engine.recommend(route(210.0), vehicle(60.0), stations, defaultProfile, RecommendContext(minuteOfDay = 9 * 60))
        ).primary
        // 9시 출발, 90분 뒤 10:30 도착 → 실시간 0분보다 훨씬 긴 대기로 예측
        assertTrue(rec.waitMinutes >= 25)
        assertEquals(WaitSource.BLENDED, rec.waitSource)
    }
}
