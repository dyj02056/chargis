package com.chargis.app.sim

import com.chargis.app.data.fake.FakeVehicle
import com.chargis.app.domain.engine.ChargeMath
import com.chargis.app.domain.model.DataSource
import com.chargis.app.domain.model.UserSettings
import com.chargis.app.domain.session.TripPhase
import com.chargis.app.ui.DriveCard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 실제 주행에 가까운 보정이 시나리오 전체에서 맞물리는지 보는 통합 테스트(ScenarioFlowTest.kt에서 분리).
 * 요금, 고도(S6), 차가운 배터리와 예열(S4), 배터리 직접 입력, SDK가 값을 주지 못할 때, 목적지 도착 잔량.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealismFlowTest {

    /** 요금: 영수증에 충전 요금이 찍힌다. B 휴게소 347.2원/kWh, 약 36.7% → 50%. */
    @Test
    fun s1_receiptShowsChargeCost() = runTest {
        val h = harness()
        h.controller.startScenario("S1")
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
        val rec = (h.controller.state.value.card as DriveCard.Recommend).rec
        // 13.3%(10.3kWh) × 충전 손실 1.08 × 347.2원 ≈ 3,860원
        assertTrue("cost=${rec.costKrw}", rec.costKrw in 3700..4000)
        h.controller.accept("rest_b")
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.Arrived }
        assertTrue(h.controller.state.value.stop!!.costKrw in 3700..4000)
        h.controller.startCharging()
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.Done }
        h.controller.depart()
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.Finished }
        val receipt = h.controller.state.value.receipt!!
        assertTrue("total=${receipt.costKrwTotal}", receipt.costKrwTotal in 3700..4000)
        assertEquals(false, receipt.costPartlyUnknown)
    }

    /**
     * S6 산길: K 휴게소 뒤로 780m 고개가 있다. 평지라면 50%면 되지만 오르막을 넣으면 55%까지 채우라고 한다.
     * 가상 차량도 오르막에서 더 쓰고 내리막에서 되찾으므로, 도착했을 때 배터리가 안내와 맞고 목적지에 10% 넘게 남는다.
     */
    @Test
    fun s6_mountainPassRaisesTargetAndArrivalMatches() = runTest {
        val h = harness()
        h.controller.startScenario("S6")
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
        val rec = (h.controller.state.value.card as DriveCard.Recommend).rec
        assertEquals("rest_k", rec.station.id)
        assertEquals(55.0, rec.targetSocPercent, 1e-9)
        // 고도를 빼고 계산하면 50%였을 것이다(남은 130km: 38.6% × 1.15 + 10% ≈ 48.6%).
        assertEquals(48.6, ChargeMath.requiredSoc(130.0, h.sim.scenario.profile, com.chargis.app.domain.model.RecommendationConfig()), 0.1)
        h.controller.accept("rest_k")
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.Arrived }
        assertEquals(rec.arrivalSocPercent, h.sim.state.value.soc, 0.3)
        h.controller.startCharging()
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.Done }
        h.controller.depart()
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.Finished }
        assertTrue("end=${h.sim.state.value.soc}", h.sim.state.value.soc >= 10.0)
        // 오르막을 "전비가 나쁘다"로 오해하지 않는다(고도 몫을 빼고 실측한다).
        assertEquals(1.0, h.controller.state.value.energy!!.factor, 0.03)
    }

    /**
     * S4 겨울: 영하 8°C에 세워 둔 차라 배터리가 차갑다. 수락한 휴게소 도착 30분 전에 예열을 권하고,
     * [배터리 예열 켜기]를 누르면 데워진 배터리로 충전해 예열하지 않을 때보다 빨리 끝난다.
     */
    @Test
    fun s4_preconditioningAdviceShortensCharge() = runTest {
        fun TestScope.chargeMinutes(precondition: Boolean): Int {
            val h = harness()
            h.controller.startScenario("S4")
            drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
            // 차가운 배터리로는 50kW A 휴게소가 덜 손해라 1순위가 된다(150kW를 받던 차가 영하에서는 50kW 남짓만 받는다).
            // 예열 효과를 보려고 A를 거절하고, 100kW B 휴게소를 고른다.
            val first = (h.controller.state.value.card as DriveCard.Recommend).rec
            assertEquals("rest_a", first.station.id)
            assertEquals(0, first.coldExtraMinutes)
            h.controller.decline()
            runCurrent()
            drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
            val rec = (h.controller.state.value.card as DriveCard.Recommend).rec
            assertEquals("rest_b", rec.station.id)
            assertTrue("coldExtra=${rec.coldExtraMinutes}", rec.coldExtraMinutes >= 3)
            h.controller.accept(rec.station.id)
            runCurrent()
            drive(h) { h.tts.spoken.contains("precondition_advice") }
            val card = h.controller.state.value.card as DriveCard.Navigating
            assertTrue(card.suggestPreconditioning)
            if (precondition) {
                // 주행 중이라 버튼 대신 "예열"이라고 말해도 켤 수 있다.
                h.controller.onVoiceCommand(com.chargis.app.domain.model.VoiceCommand.PRECONDITION)
                runCurrent()
                assertTrue(h.sim.state.value.preconditioning)
                assertTrue(h.tts.spoken.contains("precondition_started"))
            }
            drive(h) { h.controller.state.value.phase == TripPhase.Arrived }
            assertEquals(1, h.tts.spoken.count { it == "precondition_advice" })
            h.controller.startCharging()
            runCurrent()
            drive(h) { h.controller.state.value.phase == TripPhase.Done }
            return h.controller.state.value.receipt!!.charges.single().minutes
        }
        val cold = chargeMinutes(precondition = false)
        val warm = chargeMinutes(precondition = true)
        assertTrue("cold=$cold warm=$warm", warm + 3 <= cold)
    }

    /**
     * 배터리 직접 입력: 차량 값을 쓰지 않고 출발 배터리에서 계산으로 이어 간다(출처 "직접 입력").
     * S4는 실제 소비가 앱의 어림보다 커서 휴게소에 도착하면 추정이 실제보다 높다. 계기판을 보고 고치면 그 값으로 이어 간다.
     */
    @Test
    fun manualSoc_estimatesAndAcceptsCorrections() = runTest {
        val h = harness(settings = UserSettings(manualSoc = true))
        h.controller.startScenario("S4")
        runCurrent()
        assertEquals(DataSource.MANUAL, h.controller.state.value.dataSource)
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
        val rec = (h.controller.state.value.card as DriveCard.Recommend).rec
        h.controller.accept(rec.station.id)
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.Arrived }
        val truth = h.sim.state.value.soc
        val shown = h.controller.state.value.socPercent
        assertTrue("shown=$shown truth=$truth", shown > truth + 1.0)
        assertTrue(h.controller.state.value.stop!!.manualSoc)
        // 도착하면 계기판 값을 확인해 달라고 한다.
        assertTrue(h.tts.spoken.contains("check_soc"))
        val factorBefore = h.controller.state.value.energy!!.factor
        // 계기판 값에 맞춰 1%씩 낮춘다.
        repeat((shown.toInt() - truth.toInt())) { h.controller.correctSoc(-1) }
        runCurrent()
        assertEquals(truth, h.controller.state.value.socPercent, 1.0)
        h.controller.startCharging()
        runCurrent()
        // 고친 값이 전비 실측에 들어가 소비 배율이 실제(1.3)에 다가간다.
        val factorAfter = h.controller.state.value.energy!!.factor
        assertTrue("before=$factorBefore after=$factorAfter", factorAfter > factorBefore && factorAfter in 1.2..1.4)
        drive(h) { h.controller.state.value.phase == TripPhase.Done }
        // 충전 중에도 같은 충전 모델로 이어 가므로 실제 값과 크게 다르지 않다.
        assertEquals(h.sim.state.value.soc, h.controller.state.value.socPercent, 2.0)
        // 차량 데이터로 돌아가면 정차 화면에서 고치는 버튼이 사라진다.
        h.controller.updateSettings { it.copy(manualSoc = false) }
        runCurrent()
        h.sim.advance(1.0)
        runCurrent()
        assertEquals(DataSource.SIMULATED, h.controller.state.value.dataSource)
        assertEquals(false, h.controller.state.value.stop!!.manualSoc)
    }

    /**
     * SDK 대비: 차량이 배터리 %를 주지 못하고(isSupported = false), 배터리 온도·예열 상태·경로 고도도 주지 못하면
     * 설정을 바꾸지 않아도 직접 입력으로 넘어가고, 온도는 바깥 기온으로 어림하며, 예열은 "차량 화면에서 켜 주세요"로 안내한다.
     */
    @Test
    fun sdkWithoutSoc_fallsBackToManualAndOutsideTemperature() = runTest {
        val h = harness(vehicle = { sim ->
            val real = FakeVehicle(sim)
            object : com.chargis.app.port.VehiclePort {
                override val vehicleState = real.vehicleState.map { it.copy(batteryTempC = null, preconditioning = null) }
                override suspend fun isSupported() = false
            }
        })
        h.controller.startScenario("S4")
        runCurrent()
        assertEquals(DataSource.MANUAL, h.controller.state.value.dataSource)
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
        h.controller.decline()   // A(50kW)는 차가워도 손해가 없어 예열 안내가 나오지 않는다
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
        val rec = (h.controller.state.value.card as DriveCard.Recommend).rec
        // 배터리 온도를 모르지만 바깥 기온(-8°C)으로 어림해 차가운 배터리 몫을 넣는다.
        assertTrue("coldExtra=${rec.coldExtraMinutes}", rec.coldExtraMinutes >= 1)
        h.controller.accept(rec.station.id)
        runCurrent()
        h.controller.startPreconditioning()
        runCurrent()
        assertTrue(h.tts.spoken.contains("precondition_manual"))
    }

    /** 목적지 도착 잔량을 30%로 올리면 같은 휴게소에서 더 채우라고 한다(S1 B 휴게소 50% → 70%). */
    @Test
    fun destinationReserveSetting_raisesTarget() = runTest {
        val h = harness(settings = UserSettings(destinationSocPercent = 30))
        h.controller.startScenario("S1")
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
        val rec = (h.controller.state.value.card as DriveCard.Recommend).rec
        assertEquals("rest_b", rec.station.id)
        assertEquals(70.0, rec.targetSocPercent, 1e-9)
    }
}
