package com.chargis.app.sim

import com.chargis.app.data.fake.FakeChargerData
import com.chargis.app.data.fake.FakeNavi
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.ConnectorType
import com.chargis.app.domain.model.RouteState
import com.chargis.app.port.ChargerDataPort
import com.chargis.app.port.NaviPort
import com.chargis.app.domain.session.TripPhase
import com.chargis.app.ui.DriveCard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 데모 시나리오(S1 기본, S2 명절 혼잡, S3 충전기 시간 제한, S4 겨울, S5 휴게소 밖)를 처음부터 끝까지 자동으로 돌려 보는 통합 테스트.
 * 복원은 RestoreFlowTest, 요금·고도(S6)·예열·직접 입력은 RealismFlowTest에 있다.
 * 화면 없이 TripController와 시뮬레이터만 써서, 사람이 버튼을 누르는 대신 함수를 직접 부른다.
 * 가상 시계를 1초씩 밀어 가며 단계가 바뀌는 순서와 음성 안내 목록이 기대와 같은지 확인한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioFlowTest {

    // S1 전체 흐름: 추천 → 수락 → 휴게소 도착 → 충전 → 출발 → 목적지 도착
    @Test
    fun s1_fullFlow_recommendAcceptChargeResumeArrive() = runTest {
        val h = harness()
        val phases = mutableListOf<TripPhase>()
        fun record() {
            val p = h.controller.state.value.phase
            if (phases.lastOrNull() != p) phases += p
        }

        h.controller.startScenario("S1")
        runCurrent(); record()

        drive(h) { record(); h.controller.state.value.phase == TripPhase.AwaitingDecision }
        val card = h.controller.state.value.card as DriveCard.Recommend
        assertEquals("rest_b", card.rec.station.id)
        assertEquals(50.0, card.rec.targetSocPercent, 1e-9)
        assertTrue(card.rec.station.distanceAlongRouteKm <= 50.0)
        // 안내는 B까지 약 50km 남았을 때(출발 후 약 40km)
        assertEquals(40.0, h.sim.state.value.traveledKm, 1.0)

        h.controller.accept("rest_b")
        runCurrent(); record()
        assertEquals(TripPhase.NavigatingToCharger, h.controller.state.value.phase)

        drive(h) { record(); h.controller.state.value.phase == TripPhase.Arrived }
        assertEquals(90.0, h.sim.state.value.traveledKm, 1e-6)

        h.controller.startCharging()
        runCurrent(); record()
        drive(h) { record(); h.controller.state.value.phase == TripPhase.Done }
        assertEquals(50.0, h.sim.state.value.soc, 1e-6)

        h.controller.depart()
        runCurrent(); record()
        drive(h) { record(); h.controller.state.value.phase == TripPhase.Finished }

        assertEquals(
            listOf(
                TripPhase.Driving, TripPhase.AwaitingDecision, TripPhase.NavigatingToCharger,
                TripPhase.Arrived, TripPhase.Charging, TripPhase.Done, TripPhase.Driving, TripPhase.Finished
            ),
            phases.filter { it != TripPhase.TripPlanned && it != TripPhase.Resuming }
        )
        assertEquals(
            listOf("summary:1", "recommend:B 휴게소:50", "waypoint:B 휴게소", "rule:40", "target_soon", "target_reached", "resume"),
            h.tts.spoken
        )
        assertTrue(h.sim.state.value.soc >= 10.0)
    }

    // S1에서 B 휴게소를 거절하면 다음 휴게소를 추천하고, B는 다시 안내하지 않는지
    @Test
    fun s1_declineMovesToNextCandidate() = runTest {
        val h = harness()
        h.controller.startScenario("S1")
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }

        h.controller.decline()
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }

        val card = h.controller.state.value.card as DriveCard.Recommend
        assertTrue(card.rec.station.id != "rest_b")
        assertEquals(1, h.tts.spoken.count { it.startsWith("recommend:B") })
    }

    // S2 명절 혼잡: B 휴게소 대기 30분 → 혼잡 비교 카드가 뜨고, 대안 C를 고르면 C로 가는지
    @Test
    fun s2_congestionShowsComparisonCard() = runTest {
        val h = harness()
        h.controller.startScenario("S2")
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }

        val card = h.controller.state.value.card as DriveCard.Congested
        assertEquals("rest_b", card.primary.station.id)
        assertEquals("rest_c", card.alternative.station.id)
        // B4: 지금 30분 대기 + 명절 시간대 통계(11시 34분)를 섞어 도착 시각 대기를 30분 넘게 예측한다.
        val wait = h.tts.spoken.first { it.startsWith("congested:B 휴게소:") }.split(":")[2].toInt()
        assertTrue(wait in 30..34)
        // B1: 대안 C가 목적지에 더 빨리 도착한다.
        assertTrue(card.alternative.etaMinutes < card.primary.etaMinutes)

        h.controller.accept("rest_c")
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.Arrived }
        assertEquals(130.0, h.sim.state.value.traveledKm, 1e-6)
    }

    /**
     * S3: E 휴게소 40분 제한 안에 목표(80%)에 닿지 못하는 충전.
     * 규칙 안내 → 제한 10분·5분 전 경고 → 제한 종료(미달) → 출발 후 F 휴게소 재추천.
     */
    @Test
    fun s3_timeLimitWarningsShortfallAndReRecommend() = runTest {
        val h = harness()
        h.controller.startScenario("S3")
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
        val rec = (h.controller.state.value.card as DriveCard.Recommend).rec
        assertEquals("rest_e", rec.station.id)
        // B2: E에서 80%를 채우는 대신, 빠른 F까지 갈 만큼(45%)만 채우고 F에서 한 번 더 충전하는 계획
        assertEquals(45.0, rec.targetSocPercent, 1e-9)
        assertEquals("rest_f", rec.nextStop?.station?.id)
        assertTrue(rec.willHitTimeLimit)

        h.controller.accept("rest_e")
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.Arrived }
        h.controller.startCharging()
        runCurrent()

        // 충전 중 화면: 제한에 걸릴 예정이 표시된다
        assertTrue(h.controller.state.value.stop?.willHitLimit == true)
        drive(h) { h.controller.state.value.phase == TripPhase.Done }
        val stop = h.controller.state.value.stop!!
        assertTrue(stop.endedByLimit)
        assertEquals(43.1, h.sim.state.value.soc, 0.2) // 26.9% + 40분×18.75kW(25kW×0.75)
        assertEquals(40, h.controller.state.value.receipt!!.charges.single().minutes)
        assertTrue(h.controller.state.value.receipt!!.charges.single().endedByLimit)

        h.controller.depart()
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
        val next = (h.controller.state.value.card as DriveCard.Recommend).rec
        assertEquals("rest_f", next.station.id)

        val spoken = h.tts.spoken
        val f3 = spoken.filter { it.startsWith("rule") || it.startsWith("limit") || it.startsWith("target") }
        assertEquals(listOf("rule:40", "limit_warn:10:43", "limit_warn:5:43", "limit_shortfall:43:45"), f3)
        assertTrue(spoken.indexOf("limit_shortfall:43:45") < spoken.indexOfFirst { it.startsWith("recommend:F 휴게소") })
    }

    /**
     * S5 휴게소 밖 충전: G 휴게소와 H 졸음쉼터가 붐비고, 경로에서 2.5km 벗어난 I 마트는 비어 있다.
     * 마트를 고르면 경로를 벗어난다는 안내가 붙고, 시뮬레이터도 왕복 우회만큼 배터리를 써서 안내한 도착 잔량과 맞는다.
     */
    @Test
    fun s5_offRouteStationRecommendedAndDetourDriven() = runTest {
        val h = harness()
        h.controller.startScenario("S5")
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
        // 휴게소·쉼터가 붐벼서, 순위 가산과 왕복 우회를 넣고도 I 마트가 1순위로 추천된다.
        val card = h.controller.state.value.card as DriveCard.Recommend
        val rec = card.rec
        assertEquals("mart_i", rec.station.id)
        assertTrue(h.tts.spoken.toString(), h.tts.spoken.contains("recommend:I 마트:${rec.targetSocPercent.toInt()}"))
        assertTrue(h.tts.spoken.toString(), h.tts.spoken.contains("off_route:I 마트:5"))

        h.controller.accept("mart_i")
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.Arrived }
        // 경로 100km 지점 + 편도 2.5km를 달린 배터리
        assertEquals(rec.arrivalSocPercent, h.sim.state.value.soc, 0.5)

        h.controller.startCharging()
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.Done }
        val charged = h.sim.state.value.soc
        h.controller.depart()
        runCurrent()
        // 출발하면 경로로 돌아오는 2.5km만큼 배터리가 준다
        assertTrue(h.sim.state.value.soc < charged)
        drive(h) { h.controller.state.value.phase == TripPhase.Finished }
        assertTrue(h.sim.state.value.soc >= 10.0)
    }

    /** B3 S4 겨울: 실제 소비가 사양보다 30% 많다. 달릴수록 앱이 실측으로 알아내 보정 배율이 1.3에 다가간다. */
    @Test
    fun s4_winterCalibratesConsumption() = runTest {
        val h = harness()
        h.controller.startScenario("S4")
        drive(h) { h.sim.state.value.traveledKm >= 30.0 }
        val energy = h.controller.state.value.energy!!
        assertEquals(1.3, energy.factor, 0.02)
        assertTrue(energy.observedKm >= 20.0)
        // 보정된 소비로 계산하므로, 실제로 휴게소에 도착했을 때 배터리가 안내한 값과 크게 다르지 않다.
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
        val rec = (h.controller.state.value.card as DriveCard.Recommend).rec
        h.controller.accept(rec.station.id)
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.Arrived }
        assertEquals(rec.arrivalSocPercent, h.sim.state.value.soc, 1.0)
    }

    /** 3: 1초마다 상태가 바뀌어도 충전소 목록과 추천은 0.5km(또는 30초)마다만 새로 계산한다. */
    @Test
    fun evaluation_isThrottledByDistance() = runTest {
        var calls = 0
        val h = harness(data = { sim ->
            val real = FakeChargerData(sim)
            object : ChargerDataPort by real {
                override suspend fun stationsAlongRoute(route: RouteState, connectors: Set<ConnectorType>?): List<ChargingStation> {
                    calls++
                    return real.stationsAlongRoute(route, connectors)
                }
            }
        })
        h.controller.startScenario("S1")
        drive(h) { h.sim.state.value.traveledKm >= 20.0 }
        // 20km를 1초(약 28m)씩 720번 넘게 갱신했지만, 계산은 0.5km마다 한 번꼴(약 40번)이다.
        assertTrue("calls=$calls", calls in 30..50)
    }

    /** 7: 수락을 빠르게 두 번 눌러도 내비에 경유지는 한 번만 넣는다. */
    @Test
    fun accept_twiceAddsWaypointOnce() = runTest {
        var adds = 0
        val h = harness(navi = { sim ->
            val real = FakeNavi(sim)
            object : NaviPort by real {
                override suspend fun addWaypoint(station: ChargingStation): Result<Unit> {
                    adds++
                    return real.addWaypoint(station)
                }
            }
        })
        h.controller.startScenario("S1")
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
        h.controller.accept("rest_b")
        h.controller.accept("rest_b")
        runCurrent()
        h.controller.accept("rest_b")   // 이미 수락한 뒤에 다시 눌러도 무시
        runCurrent()
        assertEquals(1, adds)
        assertEquals(TripPhase.NavigatingToCharger, h.controller.state.value.phase)
    }

    /** F2·F4: 충전 중 목표를 올리면 새 목표까지 충전하고, 영수증은 80%까지 채웠을 때보다 아낀 시간을 보여 준다. */
    @Test
    fun s1_adjustTargetAndSavings() = runTest {
        val h = harness()
        h.controller.startScenario("S1")
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
        h.controller.accept("rest_b")
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.Arrived }
        h.controller.startCharging()
        runCurrent()
        h.controller.adjustTarget(+5)
        runCurrent()
        assertEquals(55.0, h.controller.state.value.stop!!.targetSoc, 1e-9)
        assertTrue(h.tts.spoken.contains("target_changed:55"))
        drive(h) { h.controller.state.value.phase == TripPhase.Done }
        assertEquals(55.0, h.sim.state.value.soc, 1e-6)
        h.controller.depart()
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.Finished }
        // B 100kW: 36.7→55%는 약 12분, 습관처럼 80%까지 채웠다면 약 27분 → 15분가량 아낌
        val receipt = h.controller.state.value.receipt!!
        assertTrue("saved=${receipt.savedMinutes}", receipt.savedMinutes in 12..18)
    }

    // 시뮬레이터를 두 번 똑같이 돌리면 결과도 똑같은지(무작위 요소가 없는지)
    @Test
    fun simulator_isDeterministic() {
        fun run(): List<SimState> {
            val sim = ScenarioSimulator(catalog.scenario("S1"))
            sim.start()
            return List(500) { sim.advance(7.0); sim.state.value }
        }
        assertEquals(run(), run())
    }
}
