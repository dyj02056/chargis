package com.chargis.app.domain.engine

import com.chargis.app.domain.model.ConnectorType
import com.chargis.app.domain.model.ElevationPoint
import com.chargis.app.domain.model.ReasonCode
import com.chargis.app.domain.model.RecommendContext
import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.domain.model.RecommendationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 실제 주행에 가까운 계산을 확인한다: 충전구 호환, 차가운 배터리와 예열, 요금, 목적지 도착 잔량,
 * 고장 의심 충전소, 오르막·내리막, 배터리 직접 입력 추정.
 * 숫자는 기본 차종(77.4kWh, 5km/kWh, 150kW, 2,100kg) 기준이다.
 */
class TripRealismTest {
    private val config = RecommendationConfig()
    private val engine = RecommendationEngine(config)

    private fun recommended(result: RecommendationResult) = result as RecommendationResult.Recommended

    // ---- 충전구 호환 ----

    @Test
    fun connectors_incompatibleStationIsSkipped_unknownIsKept() {
        val chademo = station("A", 60.0).copy(connectors = setOf(ConnectorType.CHADEMO))
        val unknown = station("B", 90.0)                                   // 충전구 미상
        val combo = station("C", 70.0).copy(connectors = setOf(ConnectorType.DC_COMBO, ConnectorType.CHADEMO))
        val r = recommended(engine.recommend(route(210.0), vehicle(60.0), listOf(chademo, unknown, combo), defaultProfile))
        val ids = (listOf(r.primary) + r.alternatives).map { it.station.id }
        assertTrue("A는 차데모 전용이라 DC콤보 차는 못 쓴다: $ids", "A" !in ids)
        assertTrue(ids.containsAll(listOf("B", "C")))
        // 차의 충전구를 모르면 거르지 않는다.
        val anyCar = defaultProfile.copy(connectors = null)
        val r2 = recommended(engine.recommend(route(210.0), vehicle(60.0), listOf(chademo), anyCar))
        assertEquals("A", r2.primary.station.id)
    }

    @Test
    fun stationWithNoUsableCharger_isSkipped() {
        val allBroken = station("A", 80.0, total = 0, available = 0).copy(brokenChargers = 4)
        val result = engine.recommend(route(210.0), vehicle(60.0), listOf(allBroken), defaultProfile)
        assertTrue(result is RecommendationResult.NoSafeStop)
    }

    // ---- 배터리 온도와 예열 ----

    @Test
    fun thermal_factorFollowsTemperature() {
        assertEquals(1.0, BatteryThermal.chargePowerFactor(25.0), 1e-9)
        assertEquals(1.0, BatteryThermal.chargePowerFactor(null), 1e-9)
        assertEquals(0.25, BatteryThermal.chargePowerFactor(-20.0), 1e-9)
        assertEquals(0.5, BatteryThermal.chargePowerFactor(0.0), 1e-9)
        // 예열은 분당 1°C씩 25°C까지, 주행은 분당 0.05°C씩 15°C까지
        assertEquals(22.0, BatteryThermal.tempAfter(-8.0, 30.0, BatteryThermal.Mode.PRECONDITIONING), 1e-9)
        assertEquals(-6.5, BatteryThermal.tempAfter(-8.0, 30.0, BatteryThermal.Mode.DRIVING), 1e-9)
        assertEquals(30.0, BatteryThermal.tempAfter(30.0, 30.0, BatteryThermal.Mode.DRIVING), 1e-9)
    }

    @Test
    fun coldBattery_slowsChargingAndPreconditioningRecoversIt() {
        val b = listOf(station("B", 90.0))
        val warm = recommended(engine.recommend(route(210.0), vehicle(60.0), b, defaultProfile)).primary
        val cold = recommended(engine.recommend(route(210.0), vehicle(60.0), b, defaultProfile, RecommendContext(batteryTempC = -8.0))).primary
        val heated = recommended(
            engine.recommend(route(210.0), vehicle(60.0), b, defaultProfile, RecommendContext(batteryTempC = -8.0, preconditioning = true))
        ).primary
        assertEquals(0, warm.coldExtraMinutes)
        assertTrue("cold=${cold.chargeMinutes} warm=${warm.chargeMinutes}", cold.chargeMinutes > warm.chargeMinutes)
        assertEquals(cold.chargeMinutes - warm.chargeMinutes, cold.coldExtraMinutes)
        // 54분 동안 예열하면 25°C까지 데워져 따뜻할 때와 같다.
        assertEquals(warm.chargeMinutes, heated.chargeMinutes)
        assertEquals(0, heated.coldExtraMinutes)
        assertTrue(heated.etaMinutes < cold.etaMinutes)
    }

    // ---- 요금 ----

    @Test
    fun cost_isEnergyFromChargerTimesPrice_unknownPriceIsNull() {
        // 77.4kWh의 10% = 7.74kWh, 충전 손실 1.08 → 8.36kWh × 347.2원 ≈ 2,902원
        assertEquals(2902, ChargeMath.chargeCostKrw(40.0, 50.0, defaultProfile, 347.2, config))
        assertNull(ChargeMath.chargeCostKrw(40.0, 50.0, defaultProfile, null, config))
        val priced = station("B", 90.0).copy(pricePerKwh = 347.2)
        val r = recommended(engine.recommend(route(210.0), vehicle(60.0), listOf(priced), defaultProfile)).primary
        assertEquals(ChargeMath.chargeCostKrw(r.arrivalSocPercent, r.targetSocPercent, defaultProfile, 347.2, config), r.costKrw)
        val unpriced = recommended(engine.recommend(route(210.0), vehicle(60.0), listOf(station("B", 90.0)), defaultProfile)).primary
        assertNull(unpriced.costKrw)
    }

    // ---- 목적지 도착 잔량 ----

    @Test
    fun destinationReserve_raisesTargetButNotStationReserve() {
        val b = listOf(station("B", 90.0))
        val normal = recommended(engine.recommend(route(210.0), vehicle(60.0), b, defaultProfile)).primary
        val reserve30 = RecommendationEngine(config.copy(destinationSocPercent = 30.0))
        val high = recommended(reserve30.recommend(route(210.0), vehicle(60.0), b, defaultProfile)).primary
        // 남은 120km: 45.7%(10% 남김) → 50%, 65.7%(30% 남김) → 70%
        assertEquals(50.0, normal.targetSocPercent, 1e-9)
        assertEquals(70.0, high.targetSocPercent, 1e-9)
        // 충전소 도착 잔량 기준(10%)은 그대로라 같은 충전소에 들른다.
        assertEquals(normal.arrivalSocPercent, high.arrivalSocPercent, 1e-9)
        // 목적지에 30%를 남기려면 지금 배터리로는 모자라 충전이 필요해진다(10%면 충전 불필요).
        assertTrue(engine.recommend(route(150.0), vehicle(60.0), b, defaultProfile) is RecommendationResult.NotNeeded)
        assertTrue(reserve30.recommend(route(150.0), vehicle(60.0), b, defaultProfile) is RecommendationResult.Recommended)
    }

    // ---- 고장 의심 ----

    @Test
    fun staleStation_isRankedBelowSameStationAndFlagged() {
        val stale = station("A", 90.0).copy(hoursSinceLastCharge = 96.0)
        val fresh = station("B", 92.0).copy(hoursSinceLastCharge = 1.0)
        val r = recommended(engine.recommend(route(210.0), vehicle(60.0), listOf(stale, fresh), defaultProfile))
        assertEquals("B", r.primary.station.id)
        val a = r.alternatives.first { it.station.id == "A" }
        assertTrue(ReasonCode.MAYBE_BROKEN in a.reasonCodes)
        assertTrue(a.costMinutes - a.etaMinutes >= config.unreliablePenaltyMin)
    }

    /** 이용이 드문 졸음쉼터는 사흘 충전 기록이 없어도 의심하지 않고, 일주일이 넘으면 의심한다. */
    @Test
    fun staleThreshold_isLongerForQuietPlaces() {
        fun flagged(kind: com.chargis.app.domain.model.StationKind, hours: Double): Boolean {
            val s = station("S", 90.0, kind = kind).copy(hoursSinceLastCharge = hours)
            val r = recommended(engine.recommend(route(210.0), vehicle(60.0), listOf(s), defaultProfile)).primary
            return ReasonCode.MAYBE_BROKEN in r.reasonCodes
        }
        assertTrue(flagged(com.chargis.app.domain.model.StationKind.HIGHWAY_REST_STOP, 96.0))
        assertEquals(false, flagged(com.chargis.app.domain.model.StationKind.SHELTER, 96.0))
        assertTrue(flagged(com.chargis.app.domain.model.StationKind.SHELTER, 200.0))
    }

    // ---- 오르막·내리막 ----

    @Test
    fun elevation_climbCostsAndDescentRecoversPart() {
        // 2,100kg × 9.81 × 600m ≈ 3.43kWh, 모터 효율 0.9 → 3.81kWh ≈ 4.92%
        assertEquals(4.92, ElevationModel.socForClimb(600.0, defaultProfile), 0.01)
        // 내려오면 60%만 되찾는다: 3.43kWh × 0.6 ≈ 2.06kWh ≈ −2.66%
        assertEquals(-2.66, ElevationModel.socForClimb(-600.0, defaultProfile), 0.01)
        val hill = listOf(ElevationPoint(0.0, 0.0), ElevationPoint(50.0, 600.0), ElevationPoint(100.0, 0.0))
        assertEquals(4.92 - 2.66, ElevationModel.socBetween(hill, 0.0, 100.0, defaultProfile), 0.02)
        // 구간 중간에서 자르면 그 사이 높이만 센다: 25km 지점(300m)부터 50km(600m)
        assertEquals(2.46, ElevationModel.socBetween(hill, 25.0, 50.0, defaultProfile), 0.01)
        assertEquals(0.0, ElevationModel.socBetween(null, 0.0, 100.0, defaultProfile), 1e-9)
        // 지금 위치 기준으로 바꾸기
        val ahead = ElevationModel.ahead(hill, 25.0)!!
        assertEquals(ElevationPoint(0.0, 300.0), ahead.first())
        assertEquals(75.0, ahead.last().distanceKm, 1e-9)
    }

    @Test
    fun elevation_climbAfterStationRaisesTarget_climbBeforeLowersArrival() {
        val b = listOf(station("B", 90.0))
        val flat = recommended(engine.recommend(route(210.0), vehicle(60.0), b, defaultProfile)).primary
        // 충전소 뒤로 900m 고개를 넘어 300m까지 내려간다: 45.7% + 오르막 8.0%(마진 포함) − 내리막 2.7% ≈ 51% → 55%
        val pass = route(210.0).copy(
            elevation = listOf(ElevationPoint(0.0, 50.0), ElevationPoint(90.0, 50.0), ElevationPoint(180.0, 900.0), ElevationPoint(210.0, 300.0))
        )
        val mountain = recommended(engine.recommend(pass, vehicle(60.0), b, defaultProfile)).primary
        assertEquals(flat.arrivalSocPercent, mountain.arrivalSocPercent, 1e-9)
        assertEquals(50.0, flat.targetSocPercent, 1e-9)
        assertEquals(55.0, mountain.targetSocPercent, 1e-9)
        // 충전소까지 400m를 오르면 도착 잔량이 그만큼 준다.
        val climbFirst = route(210.0).copy(elevation = listOf(ElevationPoint(0.0, 0.0), ElevationPoint(90.0, 400.0), ElevationPoint(210.0, 400.0)))
        val r = recommended(engine.recommend(climbFirst, vehicle(60.0), b, defaultProfile)).primary
        assertEquals(flat.arrivalSocPercent - ElevationModel.socForClimb(400.0, defaultProfile), r.arrivalSocPercent, 1e-6)
    }

    // ---- 배터리 직접 입력 ----

    @Test
    fun manualSoc_drivesDownChargesUpAndContinuesSmoothly() {
        val est = ManualSocEstimator()
        assertNull(est.estimate(0.0, 0.0, 0.0, 0.26, null))
        est.set(60.0, odometerKm = 0.0, elevationSoc = 0.0, nowSec = 0.0, charging = false)
        // 100km를 1km당 0.26%로, 오르막으로 2% 더 써서 → 60 − 26 − 2 = 32%
        assertEquals(32.0, est.estimate(100.0, 2.0, 3600.0, 0.26, null)!!, 1e-9)
        // 충전을 시작하면 그 순간의 추정값(32%)에서 충전 곡선대로 오른다. 목표(50%)에서 멈춘다.
        val charger = ManualSocEstimator.Charger(100.0, defaultProfile, config, capSoc = 50.0)
        assertEquals(32.0, est.estimate(100.0, 2.0, 3600.0, 0.26, charger)!!, 1e-9)
        val tenMin = ChargeMath.socAfterMinutes(32.0, 10.0, 100.0, defaultProfile, config)
        assertEquals(tenMin, est.estimate(100.0, 2.0, 4200.0, 0.26, charger)!!, 1e-9)
        assertEquals(50.0, est.estimate(100.0, 2.0, 9000.0, 0.26, charger)!!, 1e-9)
        // 충전을 마치고 다시 달리면 50%에서 이어 간다.
        assertEquals(50.0, est.estimate(100.0, 2.0, 9400.0, 0.26, null)!!, 1e-9)
        assertEquals(50.0 - 10 * 0.26, est.estimate(110.0, 2.0, 9800.0, 0.26, null)!!, 1e-9)
        // 운전자가 고치면 그 값이 새 기준점이다.
        est.set(45.0, 110.0, 2.0, 9400.0, false)
        assertEquals(45.0, est.estimate(110.0, 2.0, 9400.0, 0.26, null)!!, 1e-9)
    }
}
