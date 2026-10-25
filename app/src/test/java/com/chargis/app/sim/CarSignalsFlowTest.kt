package com.chargis.app.sim

import com.chargis.app.data.fake.FakeNavi
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.session.TripPhase
import com.chargis.app.port.CarSignalsPort
import com.chargis.app.port.NaviPort
import com.chargis.app.port.WaypointsFullException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 하이브리드 데모: 실제 차량 신호(배터리 %, 기어, 충전 스위치, 주행 중 화면 제한)와 순정 내비 경유지가 여행 흐름에 반영되는지. */
@OptIn(ExperimentalCoroutinesApi::class)
class CarSignalsFlowTest {
    private class FakeCar(soc: Int? = null) : CarSignalsPort {
        override val available = MutableStateFlow(soc != null)
        override val socPercent = MutableStateFlow(soc)
        override val parked = MutableStateFlow<Boolean?>(false)
        override val chargeSwitchOn = MutableStateFlow<Boolean?>(false)
        override val uxRestricted = MutableStateFlow(false)
    }

    /** 차량 배터리 %가 발권 표와 출발 배터리가 된다. */
    @Test
    fun carSocBecomesStartSoc() = runTest {
        val car = FakeCar(soc = 72)
        val h = harness(car = car)
        runCurrent()
        assertTrue(h.controller.state.value.scenarios.all { it.startSoc == 72.0 })
        h.controller.startScenario("S1")
        runCurrent()
        assertEquals(72.0, h.sim.state.value.soc, 1e-9)
    }

    /** 주행 중 기어 P면 가상 주행이 멈추고, D면 이어 간다. 처음 구독할 때의 값에는 반응하지 않는다. */
    @Test
    fun gearParkPausesDemoDriving() = runTest {
        val car = FakeCar()
        val h = harness(car = car)
        h.controller.startScenario("S1")
        runCurrent()
        assertFalse(h.sim.state.value.paused)
        car.parked.value = true
        runCurrent()
        assertTrue(h.sim.state.value.paused)
        car.parked.value = false
        runCurrent()
        assertFalse(h.sim.state.value.paused)
    }

    /** 수락한 충전소 바로 앞에서 기어 P로 바꾸면 멈추지 않고 도착 처리까지 간다. */
    @Test
    fun gearParkAtChargerStillArrives() = runTest {
        val car = FakeCar()
        val h = harness(car = car)
        h.controller.startScenario("S1")
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
        h.controller.accept("rest_b")
        runCurrent()
        drive(h) { (h.sim.kmToWaypoint() ?: 99.0) < 0.5 }
        car.parked.value = true
        runCurrent()
        assertFalse(h.sim.state.value.paused)
        drive(h, seconds = 120) { h.controller.state.value.phase == TripPhase.Arrived }
        assertEquals(TripPhase.Arrived, h.controller.state.value.phase)
    }

    /** 충전소에 도착한 뒤 충전 스위치가 켜지면 충전 시작, 꺼지면 충전 종료. */
    @Test
    fun chargeSwitchStartsAndStopsCharging() = runTest {
        val car = FakeCar()
        val h = harness(car = car)
        h.controller.startScenario("S1")
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
        h.controller.accept("rest_b")
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.Arrived }

        car.chargeSwitchOn.value = true
        runCurrent()
        assertEquals(TripPhase.Charging, h.controller.state.value.phase)
        car.chargeSwitchOn.value = false
        runCurrent()
        assertEquals(TripPhase.Done, h.controller.state.value.phase)
        assertEquals(1, h.controller.state.value.receipt?.charges?.size)
    }

    /**
     * 충전 종료는 한 번만 기록된다. 기기에서는 Main.immediate라 시뮬레이터를 멈추는 순간 틱 처리가 끼어들 수 있다.
     * Unconfined 디스패처로 그 끼어들기를 재현한다.
     */
    @Test
    fun stopChargingRecordsOnceEvenWhenTickRunsInline() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.controller.startScenario("S1")
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
        h.controller.accept("rest_b")
        drive(h) { h.controller.state.value.phase == TripPhase.Arrived }
        h.controller.startCharging()
        repeat(60) { h.sim.advance(1.0); runCurrent() }
        h.controller.stopCharging()
        assertEquals(TripPhase.Done, h.controller.state.value.phase)
        assertEquals(1, h.controller.state.value.receipt?.charges?.size)
    }

    /** 주행 중 화면 제한이 걸리면 화면 상태에 실린다(정차 화면을 가리는 데 쓴다). */
    @Test
    fun uxRestrictionReachesUiState() = runTest {
        val car = FakeCar()
        val h = harness(car = car)
        runCurrent()
        assertFalse(h.controller.state.value.uxRestricted)
        car.uxRestricted.value = true
        runCurrent()
        assertTrue(h.controller.state.value.uxRestricted)
    }

    /** 순정 내비 경유지 두 자리가 차 있으면 수락하지 않고 "경유지 가득" 안내를 한다. */
    @Test
    fun fullNaviWaypointsAnnounceAndKeepOffer() = runTest {
        val h = harness(navi = { sim ->
            val demo = FakeNavi(sim)
            object : NaviPort {
                override val routeState: Flow<RouteState?> = demo.routeState
                override suspend fun addWaypoint(station: ChargingStation) = Result.failure<Unit>(WaypointsFullException())
                override suspend fun removeWaypoint(station: ChargingStation) = demo.removeWaypoint(station)
                override suspend fun requestReRoute() = demo.requestReRoute()
            }
        })
        h.controller.startScenario("S1")
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
        h.controller.accept("rest_b")
        runCurrent()
        assertEquals(TripPhase.AwaitingDecision, h.controller.state.value.phase)
        assertTrue("waypoints_full" in h.tts.spoken)
        assertFalse("waypoint_failed" in h.tts.spoken)
    }
}
