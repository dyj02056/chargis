package com.chargis.app.sim

import com.chargis.app.data.asset.AssetCatalog
import com.chargis.app.data.fake.FakeChargerData
import com.chargis.app.data.fake.FakeNavi
import com.chargis.app.data.fake.FakeVehicle
import com.chargis.app.domain.model.Priority
import com.chargis.app.domain.session.TripPhase
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

@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioFlowTest {

    private val catalog = AssetCatalog(
        asset("vehicle_profiles.json"),
        asset("sample_stations.json"),
        asset("sample_route.json")
    )

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
    }

    private class Harness(val sim: ScenarioSimulator, val controller: TripController, val tts: Recorder)

    private fun TestScope.harness(): Harness {
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
            runClock = false
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

    @Test
    fun s2_congestionShowsComparisonCard() = runTest {
        val h = harness()
        h.controller.startScenario("S2")
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }

        val card = h.controller.state.value.card as DriveCard.Congested
        assertEquals("rest_b", card.primary.station.id)
        assertEquals("rest_c", card.alternative.station.id)
        assertTrue(h.tts.spoken.any { it.startsWith("congested:B 휴게소:30") })

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
        assertEquals(80.0, rec.targetSocPercent, 1e-9)
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
        assertEquals(59.2, h.sim.state.value.soc, 0.2) // T2와 같은 역산: 26.9% + 40분×37.5kW
        assertEquals(40, h.controller.state.value.receipt!!.charges.single().minutes)
        assertTrue(h.controller.state.value.receipt!!.charges.single().endedByLimit)

        h.controller.depart()
        runCurrent()
        drive(h) { h.controller.state.value.phase == TripPhase.AwaitingDecision }
        val next = (h.controller.state.value.card as DriveCard.Recommend).rec
        assertEquals("rest_f", next.station.id)

        val spoken = h.tts.spoken
        val f3 = spoken.filter { it.startsWith("rule") || it.startsWith("limit") || it.startsWith("target") }
        assertEquals(listOf("rule:40", "limit_warn:10:59", "limit_warn:5:59", "limit_shortfall:59:80"), f3)
        assertTrue(spoken.indexOf("limit_shortfall:59:80") < spoken.indexOfFirst { it.startsWith("recommend:F 휴게소") })
    }

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
        fun asset(name: String) = File("src/main/assets/$name").readText(Charsets.UTF_8)
    }
}
