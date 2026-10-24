package com.chargis.app

import android.content.Context
import com.chargis.app.data.asset.AssetCatalog
import com.chargis.app.data.fake.FakeChargerData
import com.chargis.app.data.fake.FakeLocation
import com.chargis.app.data.fake.FakeNavi
import com.chargis.app.data.fake.FakeTts
import com.chargis.app.data.fake.FakeVehicle
import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.port.ChargerDataPort
import com.chargis.app.port.LocationPort
import com.chargis.app.port.NaviPort
import com.chargis.app.port.VehiclePort
import com.chargis.app.sim.ScenarioSimulator
import com.chargis.app.voice.AndroidTts
import com.chargis.app.voice.AndroidVoicePhrases
import com.chargis.app.voice.VoiceAnnouncer
import com.chargis.app.voice.VoicePhrases

/**
 * 수동 DI. 포트 구현체(Fake ↔ SDK)는 여기서만 고른다.
 * 지금은 SDK 미제공 → 모든 포트가 시뮬레이터 기반 Fake다(docs/DECISIONS.md).
 *
 * 쉽게 말해 앱의 "부품 조립대"다. 앱이 켜질 때 한 번 만들어지고, 필요한 부품을 모두 여기서 만들어 나눠 준다.
 * (DI = Dependency Injection, 부품을 안에서 직접 만들지 않고 밖에서 넣어 주는 방식)
 * 나중에 실제 차량 SDK가 나오면 이 파일에서 Fake를 SDK 구현체로 바꾸기만 하면 된다.
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    // 앱에 함께 들어 있는 샘플 데이터(차종, 충전소, 경로 JSON 파일)를 읽어 온다.
    val catalog = AssetCatalog(
        profilesJson = readAsset("vehicle_profiles.json"),
        stationsJson = readAsset("sample_stations.json"),
        routesJson = readAsset("sample_route.json")
    )

    // 추천 기준값(안전 마진, 최소 도착 잔량 등). 기본값을 그대로 쓴다.
    val config = RecommendationConfig()

    // 데모용 가상 주행 시뮬레이터. 첫 번째 시나리오로 시작한다.
    val simulator = ScenarioSimulator(catalog.scenarios.first(), config)

    // 내비, 차량, 충전소, 위치 정보는 모두 시뮬레이터가 만들어 내는 가짜 값을 쓴다.
    val navi: NaviPort = FakeNavi(simulator)
    val vehicle: VehiclePort = FakeVehicle(simulator)
    val chargerData: ChargerDataPort = FakeChargerData(simulator)
    val location: LocationPort = FakeLocation(simulator)

    // 음성 안내. 실제 소리는 안드로이드 기본 TTS로 내고, Fake가 말한 내용을 기록해 둔다.
    val tts = FakeTts(delegate = AndroidTts(app))
    val announcer = VoiceAnnouncer(tts)
    val phrases: VoicePhrases = AndroidVoicePhrases(app)

    // assets 폴더의 파일을 UTF-8 텍스트로 읽는다(한글이 깨지지 않게).
    private fun readAsset(name: String): String =
        app.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
