package com.chargis.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.chargis.app.voice.AndroidSpeechStt
import com.chargis.app.data.asset.AssetCatalog
import com.chargis.app.data.fake.FakeChargerData
import com.chargis.app.data.fake.FakeLocation
import com.chargis.app.data.fake.FakeNavi
import com.chargis.app.data.fake.FakeTts
import com.chargis.app.data.fake.FakeVehicle
import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.port.ChargerDataPort
import com.chargis.app.data.publicapi.ChargerRuleTable
import com.chargis.app.data.publicapi.FallbackChargerData
import com.chargis.app.data.publicapi.PublicChargerData
import com.chargis.app.data.publicapi.UrlEvChargerHttp
import com.chargis.app.data.store.PrefsSettingsStore
import com.chargis.app.data.store.PrefsTripStore
import com.chargis.app.port.LocationPort
import com.chargis.app.port.SettingsStore
import com.chargis.app.port.SttPort
import com.chargis.app.port.TripStore
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
    // 충전소 정보: 공공데이터를 켜 두었고 키(개발용 빌드) 또는 프록시 주소가 있으면 공공데이터를 먼저,
    // 실패하면 번들 샘플로 넘어간다(B5). 조회할 시도는 경로가 지나는 곳으로 그때그때 고른다.
    private val proxyUrl = BuildConfig.CHARGER_API_BASE_URL.takeIf { it.isNotBlank() }
    val chargerData: ChargerDataPort =
        if (BuildConfig.USE_PUBLIC_CHARGER_API && (BuildConfig.CHARGER_API_KEY.isNotBlank() || proxyUrl != null)) {
            FallbackChargerData(
                primary = PublicChargerData(
                    UrlEvChargerHttp(BuildConfig.CHARGER_API_KEY, proxyUrl ?: UrlEvChargerHttp.DEFAULT_BASE),
                    rules = ChargerRuleTable.parse(readAsset("charger_rules.json"))
                ),
                fallback = FakeChargerData(simulator)
            )
        } else {
            FakeChargerData(simulator)
        }
    val location: LocationPort = FakeLocation(simulator)

    // 음성 안내. 실제 소리는 안드로이드 기본 TTS로 내고, Fake가 말한 내용을 기록해 둔다.
    val tts = FakeTts(delegate = AndroidTts(app))
    val announcer = VoiceAnnouncer(tts)
    val phrases: VoicePhrases = AndroidVoicePhrases(app)

    // 설정과 진행 중인 여행은 기기에 저장해, 앱을 껐다 켜도 이어 간다(F5, B5).
    val settingsStore: SettingsStore = PrefsSettingsStore(app)
    val tripStore: TripStore = PrefsTripStore(app)

    // 음성 명령(F5). 아직 차량 STT SDK가 없어 안드로이드 기본 음성 인식을 쓴다. 마이크 권한이 있을 때만 듣는다.
    val stt: SttPort = AndroidSpeechStt(app) {
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    // assets 폴더의 파일을 UTF-8 텍스트로 읽는다(한글이 깨지지 않게).
    private fun readAsset(name: String): String =
        app.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
