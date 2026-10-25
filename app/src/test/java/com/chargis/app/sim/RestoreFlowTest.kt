package com.chargis.app.sim

import com.chargis.app.data.fake.InMemoryTripStore
import com.chargis.app.domain.session.TripPhase
import com.chargis.app.ui.DriveCard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 앱을 껐다 켜도 여행이 이어지는지 보는 통합 테스트(ScenarioFlowTest.kt에서 분리).
 * 충전 중·충전소 도착 후 복원, 시나리오에 없는 충전소(공공데이터) 복원.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreFlowTest {

    /** 1·5: 시나리오에 없는 충전소를 수락해도 그 자리에서 서서 충전하고, 앱을 다시 켜도 이어 간다. 시간 제한을 모르면 그렇게 알린다. */
    @Test
    fun publicStation_acceptArriveChargeAndRestore() = runTest {
        val store = InMemoryTripStore()
        val first = harness(store, data = ::PublicLikeData)
        first.controller.startScenario("S1")
        drive(first) { first.controller.state.value.phase == TripPhase.AwaitingDecision }
        val rec = (first.controller.state.value.card as DriveCard.Recommend).rec
        assertEquals("pub_rest_b", rec.station.id)
        first.controller.accept("pub_rest_b")
        runCurrent()
        assertEquals(TripPhase.NavigatingToCharger, first.controller.state.value.phase)
        drive(first) { first.controller.state.value.phase == TripPhase.Arrived }
        assertEquals(90.0, first.sim.state.value.traveledKm, 1e-6)
        first.controller.startCharging()
        runCurrent()
        assertTrue(first.tts.spoken.contains("rule_unknown"))
        assertEquals(false, first.controller.state.value.stop?.timeLimitKnown)
        val start = first.sim.state.value.simTimeSec
        drive(first) { first.sim.state.value.simTimeSec - start >= 60 }

        // 앱을 다시 켜면 저장해 둔 충전소 정보로 되살려 같은 충전을 이어 간다.
        val second = harness(store, data = ::PublicLikeData)
        assertEquals(TripPhase.Charging, second.controller.state.value.phase)
        assertEquals("B 휴게소", second.controller.state.value.stop?.stationName)
        drive(second) { second.controller.state.value.phase == TripPhase.Done }
        assertEquals(50.0, second.sim.state.value.soc, 1e-6)
    }

    /** B5: 충전 중에 앱이 꺼졌다 켜져도 같은 휴게소·목표·알림 기록으로 이어 가고, 끝까지 간다. */
    @Test
    fun restore_continuesChargingAfterRestart() = runTest {
        val store = InMemoryTripStore()
        val first = harness(store)
        first.controller.startScenario("S3")
        drive(first) { first.controller.state.value.phase == TripPhase.AwaitingDecision }
        first.controller.accept("rest_e")
        runCurrent()
        drive(first) { first.controller.state.value.phase == TripPhase.Arrived }
        first.controller.startCharging()
        runCurrent()
        val start = first.sim.state.value.simTimeSec
        // 제한 10분 전 경고(30분 시점)가 나간 뒤에 앱이 꺼졌다고 친다.
        drive(first) { first.sim.state.value.simTimeSec - start >= 32 * 60 }
        assertTrue(first.tts.spoken.contains("limit_warn:10:43"))
        assertTrue(store.saved != null)
        val savedSoc = first.sim.state.value.soc

        // 새 컨트롤러 = 앱을 다시 켬
        val second = harness(store)
        val restored = second.controller.state.value
        assertEquals(TripPhase.Charging, restored.phase)
        assertTrue(restored.restored)
        assertEquals(false, restored.loading)
        assertEquals("E 휴게소", restored.stop?.stationName)
        assertEquals(savedSoc, second.sim.state.value.soc, 0.5)
        drive(second) { second.controller.state.value.phase == TripPhase.Done }
        // 이미 낸 10분 전 경고는 다시 내지 않고, 5분 전 경고와 종료 안내만 이어서 낸다.
        val f3 = second.tts.spoken.filter { it.startsWith("limit") || it.startsWith("rule") }
        assertEquals(listOf("limit_warn:5:43", "limit_shortfall:43:45"), f3)
        assertEquals(1, second.controller.state.value.receipt!!.charges.size)
    }

    /** 앱을 다시 켜 충전소 도착 화면에서 이어 가면, 비교 표("앞으로의 충전소")를 다시 계산해 채운다. */
    @Test
    fun restore_atStationRefillsCandidateTable() = runTest {
        val store = InMemoryTripStore()
        val first = harness(store)
        first.controller.startScenario("S1")
        drive(first) { first.controller.state.value.phase == TripPhase.AwaitingDecision }
        first.controller.accept("rest_b")
        runCurrent()
        drive(first) { first.controller.state.value.phase == TripPhase.Arrived }
        assertTrue(first.controller.state.value.candidates.isNotEmpty())

        val second = harness(store)
        assertEquals(TripPhase.Arrived, second.controller.state.value.phase)
        second.sim.advance(1.0)
        runCurrent()
        val ids = second.controller.state.value.candidates.map { it.station.id }
        assertTrue("candidates=$ids", ids.isNotEmpty() && "rest_c" in ids)
    }
}
