package com.chargis.app.sim

import com.chargis.app.data.asset.AssetCatalog
import com.chargis.app.data.fake.FakeChargerData
import com.chargis.app.data.fake.FakeNavi
import com.chargis.app.data.fake.FakeVehicle
import com.chargis.app.data.fake.InMemoryTripStore
import com.chargis.app.domain.model.Priority
import com.chargis.app.domain.session.TripPhase
import com.chargis.app.port.TripStore
import com.chargis.app.port.TtsPort
import com.chargis.app.ui.DriveCard
import com.chargis.app.ui.TripController
import com.chargis.app.voice.VoiceAnnouncer
import com.chargis.app.voice.VoicePhrases
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * 데모 시나리오(S1 기본, S2 명절 혼잡, S3 충전기 시간 제한)를 처음부터 끝까지 자동으로 돌려 보는 통합 테스트.
 * 화면 없이 TripController와 시뮬레이터만 써서, 사람이 버튼을 누르는 대신 함수를 직접 부른다.
 * 가상 시계를 1초씩 밀어 가며 단계가 바뀌는 순서와 음성 안내 목록이 기대와 같은지 확인한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioFlowTest {

    private val catalog = AssetCatalog(
        asset("vehicle_profiles.json"),
        asset("sample_stations.json"),
        asset("sample_route.json")
    )

    // 소리를 내는 대신 말한 문장을 목록에 적어 두는 가짜 스피커
    private class Recorder : TtsPort {
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String, priority: Priority) {
            spoken += text
        }
    }

    /** 문구 키만 남기는 테스트용 문구 조립기. */
    private object KeyPhrases : VoicePhrases {
        override fun tripSummary(stops: Int) = "summary:$stops"
        override fun recommend(km: Double, name: String, targetSoc: Double) = "recommend:$name:${targetSoc.toInt()}"
        override fun needAnother() = "need_another"
        override fun congested(name: String, waitMin: Int, altName: String) = "congested:$name:$waitMin:$altName"
        override fun waypointAdded(name: String) = "waypoint:$name"
        override fun waypointFailed() = "waypoint_failed"
        override fun ruleNotice(limitMin: Int) = "rule:$limitMin"
        override fun limitWarn(minutesLeft: Int, nowSoc: Double, endSoc: Double) = "limit_warn:$minutesLeft:${endSoc.toInt()}"
        override fun targetSoon() = "target_soon"
        override fun limitShortfall(endSoc: Double, targetSoc: Double) = "limit_shortfall:${endSoc.toInt()}:${targetSoc.toInt()}"
        override fun targetReached() = "target_reached"
        override fun resume() = "resume"
        override fun noSafeStop() = "no_safe"
        override fun planTwoStops(nextName: String, nextTarget: Double) = "two_stops:$nextName:${nextTarget.toInt()}"
        override fun targetChanged(target: Double) = "target_changed:${target.toInt()}"
        override fun offRoute(name: String, roundTripKm: Int) = "off_route:$name:$roundTripKm"
    }

    // 테스트 한 번에 필요한 부품 묶음(시뮬레이터, 컨트롤러, 가짜 스피커)
    private class Harness(val sim: ScenarioSimulator, val controller: TripController, val tts: Recorder)

    private fun TestScope.harness(store: TripStore? = null): Harness {
        val sim = ScenarioSimulator(catalog.scenarios.first())
        val tts = Recorder()
        val controller = TripController(
            scope = backgroundScope,
            navi = FakeNavi(sim),
            vehicle = FakeVehicle(sim),
            chargerData = FakeChargerData(sim),
            announcer = VoiceAnnouncer(tts),
            phrases = KeyPhrases,
            demo = sim,
            scenarios = catalog.scenarios,
            runClock = false,
            profiles = catalog.profiles,
            tripStore = store
        )
        runCurrent()
        return Harness(sim, controller, tts)
    }

    /** 가상 시계로 [seconds]초를 1초 단위로 진행하며 [until]이 참이 되면 멈춘다. */
    private fun TestScope.drive(h: Harness, seconds: Int = 4 * 3600, until: () -> Boolean) {
        repeat(seconds) {
            if (until()) return
            h.sim.advance(1.0)
            runCurrent()
        }
        error("조건에 도달하지 못함: phase=${h.controller.state.value.phase}")
    }

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
        // G 휴게소 대기 35분 → 혼잡 비교 카드에서 대안으로 I 마트가 나오고, 마트 쪽이 더 빨리 도착한다.
        val card = h.controller.state.value.card as DriveCard.Congested
        assertEquals("rest_g", card.primary.station.id)
        val rec = card.alternative
        assertEquals("mart_i", rec.station.id)
        assertTrue(rec.etaMinutes < card.primary.etaMinutes)
        assertTrue(h.tts.spoken.toString(), h.tts.spoken.contains("off_route:I 마트:5"))

        h.controller.accept("mart_i")
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.Arrived }
        // 경로 95km 지점 + 편도 2.5km를 달린 배터리
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
        assertEquals("E 휴게소", restored.stop?.stationName)
        assertEquals(savedSoc, second.sim.state.value.soc, 0.5)
        drive(second) { second.controller.state.value.phase == TripPhase.Done }
        // 이미 낸 10분 전 경고는 다시 내지 않고, 5분 전 경고와 종료 안내만 이어서 낸다.
        val f3 = second.tts.spoken.filter { it.startsWith("limit") || it.startsWith("rule") }
        assertEquals(listOf("limit_warn:5:43", "limit_shortfall:43:45"), f3)
        assertEquals(1, second.controller.state.value.receipt!!.charges.size)
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

    private companion object {
        // 앱과 같은 샘플 JSON 파일을 디스크에서 직접 읽는다(테스트는 안드로이드 기기 없이 돌기 때문).
        fun asset(name: String) = File("src/main/assets/$name").readText(Charsets.UTF_8)
    }
}
