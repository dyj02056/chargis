package com.chargis.app.sim

import com.chargis.app.data.asset.AssetCatalog
import com.chargis.app.data.fake.FakeChargerData
import com.chargis.app.data.fake.FakeNavi
import com.chargis.app.data.fake.FakeVehicle
import com.chargis.app.data.fake.InMemorySettingsStore
import com.chargis.app.domain.model.UserSettings
import com.chargis.app.domain.model.ChargerRule
import com.chargis.app.domain.model.ConnectorType
import com.chargis.app.domain.model.Priority
import com.chargis.app.domain.model.RouteState
import com.chargis.app.port.ChargerDataPort
import com.chargis.app.port.NaviPort
import com.chargis.app.port.TripStore
import com.chargis.app.port.TtsPort
import com.chargis.app.ui.TripController
import com.chargis.app.voice.VoiceAnnouncer
import com.chargis.app.voice.VoicePhrases
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import java.io.File

/*
 * 시나리오 통합 테스트가 함께 쓰는 준비물(ScenarioFlowTest.kt에서 분리).
 * 가짜 스피커(Recorder), 문구 키만 남기는 문구 조립기(KeyPhrases), 컨트롤러 조립(harness), 가상 시계 진행(drive).
 */
internal val catalog = AssetCatalog(
    asset("vehicle_profiles.json"),
    asset("sample_stations.json"),
    asset("sample_route.json")
)

// 소리를 내는 대신 말한 문장을 목록에 적어 두는 가짜 스피커
internal class Recorder : TtsPort {
    val spoken = mutableListOf<String>()
    override suspend fun speak(text: String, priority: Priority) {
        spoken += text
    }
}

/** 문구 키만 남기는 테스트용 문구 조립기. */
internal object KeyPhrases : VoicePhrases {
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
    override fun waypointSwitched(fromName: String, toName: String, fasterMin: Int) = "switched:$fromName:$toName:$fasterMin"
    override fun waypointSwitchedUnreachable(fromName: String, toName: String) = "switched_unreachable:$fromName:$toName"
    override fun ruleUnknown() = "rule_unknown"
    override fun preconditionAdvice(savedMin: Int) = "precondition_advice"
    override fun preconditionAdviceManual(savedMin: Int) = "precondition_advice_manual"
    override fun preconditionStarted() = "precondition_started"
    override fun preconditionManual() = "precondition_manual"
    override fun checkSoc() = "check_soc"
}

// 테스트 한 번에 필요한 부품 묶음(시뮬레이터, 컨트롤러, 가짜 스피커)
internal class Harness(val sim: ScenarioSimulator, val controller: TripController, val tts: Recorder)

internal fun TestScope.harness(
    store: TripStore? = null,
    data: (ScenarioSimulator) -> ChargerDataPort = ::FakeChargerData,
    navi: (ScenarioSimulator) -> NaviPort = ::FakeNavi,
    settings: UserSettings? = null,
    vehicle: (ScenarioSimulator) -> com.chargis.app.port.VehiclePort = ::FakeVehicle
): Harness {
    val sim = ScenarioSimulator(catalog.scenarios.first())
    val tts = Recorder()
    val controller = TripController(
        scope = backgroundScope,
        navi = navi(sim),
        vehicle = vehicle(sim),
        chargerData = data(sim),
        announcer = VoiceAnnouncer(tts),
        phrases = KeyPhrases,
        demo = sim,
        scenarios = catalog.scenarios,
        runClock = false,
        profiles = catalog.profiles,
        tripStore = store,
        settingsStore = settings?.let(::InMemorySettingsStore)
    )
    runCurrent()
    return Harness(sim, controller, tts)
}

/** 가상 시계로 [seconds]초를 1초 단위로 진행하며 [until]이 참이 되면 멈춘다. */
internal fun TestScope.drive(h: Harness, seconds: Int = 4 * 3600, until: () -> Boolean) {
    repeat(seconds) {
        if (until()) return
        h.sim.advance(1.0)
        runCurrent()
    }
    error("조건에 도달하지 못함: phase=${h.controller.state.value.phase}")
}

/**
 * 공공데이터처럼 시나리오에 없는 충전소를 주는 가짜 공급원. 시나리오 충전소를 "pub_" id로 바꾸고 시간 제한은 모른다고 둔다.
 * 시뮬레이터가 경유지로 올려 둔 pub_ 충전소는 원본과 겹치지 않게 뺀다.
 */
internal class PublicLikeData(sim: ScenarioSimulator) : ChargerDataPort {
    private val inner = FakeChargerData(sim)
    override suspend fun stationsAlongRoute(route: RouteState, connectors: Set<ConnectorType>?) = inner.stationsAlongRoute(route, connectors)
        .filter { !it.id.startsWith("pub_") }
        .map { it.copy(id = "pub_${it.id}", rule = ChargerRule(timeLimitMin = null, amountLimitKrw = null, timeLimitKnown = false)) }
}

// 앱과 같은 샘플 JSON 파일을 디스크에서 직접 읽는다(테스트는 안드로이드 기기 없이 돌기 때문).
internal fun asset(name: String) = File("src/main/assets/$name").readText(Charsets.UTF_8)
