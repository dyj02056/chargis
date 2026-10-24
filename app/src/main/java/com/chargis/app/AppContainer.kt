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
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val catalog = AssetCatalog(
        profilesJson = readAsset("vehicle_profiles.json"),
        stationsJson = readAsset("sample_stations.json"),
        routesJson = readAsset("sample_route.json")
    )

    val config = RecommendationConfig()

    val simulator = ScenarioSimulator(catalog.scenarios.first(), config)

    val navi: NaviPort = FakeNavi(simulator)
    val vehicle: VehiclePort = FakeVehicle(simulator)
    val chargerData: ChargerDataPort = FakeChargerData(simulator)
    val location: LocationPort = FakeLocation(simulator)

    val tts = FakeTts(delegate = AndroidTts(app))
    val announcer = VoiceAnnouncer(tts)
    val phrases: VoicePhrases = AndroidVoicePhrases(app)

    private fun readAsset(name: String): String =
        app.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
