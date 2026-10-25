# Chargis (차지스) — 앱 개발 계획서 (plan.md)

> 이 문서는 **LLM 코딩 에이전트가 읽고 바로 구현을 시작하기 위한 단일 기준 문서**입니다.
> 원본 기획: 현대자동차그룹 차량용 앱 개발 공모전 참가신청서(Chargis). 이 문서와 충돌하면 이 문서가 우선합니다.
> 작성일: 2026-10-24 / 1차 제출 목표: 2026-11-14 (마감 11-15 23:59)

> **현재 상태(2026-10-25):** M0~M2의 앱 쪽 작업과 M3의 F3 알림은 끝났다. 남은 것은 SDK 연동(10/28 설명회 뒤), 공공데이터 실키 검증,
> 중계 서버 배포 위치 결정, 시연 영상이다. 이 계획서 이후에 더한 기능(휴게소 밖 충전소, 충전구 호환, 배터리 온도와 예열, 요금,
> 목적지 도착 잔량, 고장 의심, 고도, 배터리 직접 입력, 시나리오 S4~S6)과 그 근거는 `docs/DECISIONS.md`에, 확인할 것은
> `docs/OPEN_ISSUES.md`에, 지금 동작은 `README.md`에 있다. 아래 본문은 처음 계획 그대로 두고, 체크리스트만 진행 상황에 맞췄다.

---

## 0. 에이전트 작업 지침 (가장 먼저 읽을 것)

### 0.1 목표
Pleos Connect 에뮬레이터(Android Studio AVD)에서 **끝까지 동작하는 데모**를 만든다.
핵심 흐름: `경로+배터리 읽기 → 휴게소 충전소 추천 → 목표 충전량 안내 → 경유지 추가 → 충전 중 시간 제한 알림`.

### 0.2 반드시 지킬 규칙
1. **SDK 시그니처를 추측하지 않는다.** Pleos Connect SDK(NaviHelper, Vehicle, TextToSpeech, SpeechToText, FusedLocation)의 실제 jar/aar/문서가 저장소의 `docs/sdk/` 또는 `libs/`에 있을 때만 실제 어댑터를 구현한다. 없으면 `Fake*` 구현체와 `TODO(SDK)` 스텁만 만든다. 존재하지 않는 API를 지어내지 않는다.
2. **모든 외부 의존은 포트(interface) 뒤에 둔다.** 도메인/추천 엔진/UI는 SDK·네트워크 클래스를 직접 import하지 않는다. (§6)
3. **항상 빌드가 통과하는 상태로 커밋한다.** 마일스톤마다 `./gradlew assembleDebug testDebugUnitTest`가 성공해야 한다.
4. **추천 엔진은 순수 Kotlin(안드로이드 의존 없음)** 으로 작성하고 단위 테스트를 먼저/함께 작성한다. (§7, §12)
5. **데모 모드(시뮬레이터)가 1급 기능이다.** SDK 없이도 시연 영상 전체를 재현할 수 있어야 한다. (§10)
6. **주행 중 UI는 카드 1장 + 음성뿐이다.** 주행 중 스크롤·텍스트 입력·작은 터치 타깃 금지. (§9)
7. **문자열은 `strings.xml`로** 분리한다(한국어 기본). 음성 문구도 동일. 하드코딩 금지.
8. **비밀값(API 키 등)은 커밋하지 않는다.** `local.properties` → `BuildConfig`로만 주입. 키가 없으면 번들 샘플 데이터로 동작해야 한다.
9. **개인정보 비노출(§15).** 코드·README·커밋 메타데이터·스크린샷·로그 어디에도 실명·전화번호·이메일을 넣지 않는다.
10. 막히면 멈추고 묻기보다, **가정을 `docs/DECISIONS.md`에 한 줄로 기록하고 진행**한다. 사용자 결정이 필수인 항목만 `docs/OPEN_ISSUES.md`에 모아 마지막에 한 번에 보고한다.

### 0.3 첫 응답에서 할 일
1. 이 문서를 요약하지 말고 **§17 Kickoff 작업**부터 바로 실행한다.
2. 저장소에 `docs/sdk/`가 있는지 확인하고, 없으면 "SDK 미제공 → Fake 우선" 모드로 진행한다고 `docs/DECISIONS.md`에 기록한다.

---

## 1. 제품 요약

| 항목 | 내용 |
|---|---|
| 한 줄 소개 | 전기차 장거리 주행 중 내비 경로와 배터리 상태를 읽어 **'어느 휴게소에서 몇 %까지 충전할지'** 알려 주고, **충전 시간 제한**까지 챙겨 주는 Pleos Connect용 휴게소 충전 비서 앱 |
| 해결할 문제 | ① 충전기 점유·대기 ② 시간 제한을 몰라 충전이 중간에 끊김 ③ 충전 판단 부담 ④ 동승자 대기 부담 |
| 타깃 | Pleos Connect 탑재 전기차 운전자 중 주말·명절 고속도로 장거리 이동자 |
| 차별점 | 충전소 "위치"가 아니라 **"얼마나, 얼마 동안"** 을 차량 데이터 + 경로로 계산해 차량 화면이 먼저 안내 |

### 1.1 핵심 기능 4가지
| ID | 기능 | 요약 |
|---|---|---|
| F1 | 경로상 충전소 추천 | 경로상 충전소 후보(고속도로 휴게소·졸음쉼터 우선, 국도 휴게소·국도변·IC 근처·상업·공공시설은 대안)를 도착 시 배터리·출력·가용 대수·대기·왕복 우회로 비교해 1순위 제안 (2026-10-25 범위 확장, docs/DECISIONS.md) |
| F2 | 목표 충전량 코치 | 목적지/다음 충전지까지 필요 에너지 + 안전 마진 → "OO%까지만 충전" |
| F3 | 충전 시간 제한 알림 | 충전 시작 시 운영 규칙 안내, 제한 도달 전 남은 시간·예상 충전량 알림 |
| F4 | 경유지 자동 추가 | 추천 수락 시 내비 경유지로 등록 + 재탐색 |

**구현 우선순위:** F1 → F2 → F3 → F4 (신청서 일정과 동일). 단, 데모 시나리오는 4개가 모두 보여야 한다.

---

## 2. 확정 / 미확정 구분

| 구분 | 항목 | 상태 | 처리 방침 |
|---|---|---|---|
| 확정 | Kotlin, Gradle 8.0+, Android API 28+, Pleos Connect Emulator(AVD) | 확정 | 그대로 사용 |
| 확정 | 주행 중: 카드 1장 + TTS / 정차·충전 중: 상세 | 확정 | §9 |
| 미확정 | Vehicle SDK로 SoC·주행가능거리 수신 가능 여부 | **10/28 설명회에서 확인** | `VehiclePort`: SDK 어댑터 ↔ 수동 입력 이중안 |
| 미확정 | 공공 충전소 데이터(위치·출력·규칙·실시간 상태) 사용 가능 여부/호출 방식 | **10/28 확인** | `ChargerDataPort`: 실시간 ↔ 정적+시간대 혼잡 통계 이중안 |
| 미확정 | 동승석 화면 지원 여부 | 확인 후 확정 | 일단 동일 화면의 "남은 시간·출발 예정 시각"을 크게 표시 |
| 미확정 | 에뮬레이터 해상도/폼팩터 | 확인 필요 | **가정:** 가로형 와이드 디스플레이. 창 크기 기반 반응형으로 작성 |
| 미확정 | 차종별 프로필 | 초기엔 공통 기본값 | `VehicleProfile` 목록 + 차종 선택 화면은 후반 |
| 선택 | STT 음성 응답("수락", "다음 휴게소") | 선택 | 버튼 우선, STT는 후순위 |

> 미확정 항목은 **코드에서 분기하지 말고 포트 뒤에서 구현체 교체**로 해결한다.

---

## 3. 기술 스택 / 환경

- 언어: Kotlin. 빌드: Gradle 8.0+ (AGP 8.x, JDK 17). `minSdk 28`. `compileSdk/targetSdk`는 설치된 최신 안정 버전.
- UI: **Jetpack Compose + Material3** *(가정: 에뮬레이터에서 정상 동작. 문제 시 `docs/DECISIONS.md`에 기록하고 View 기반으로 전환)*
- 비동기: Kotlin Coroutines + Flow. 상태 홀더: `ViewModel` + `StateFlow`.
- DI: **수동 DI**(`AppContainer`). Hilt/Dagger 사용 안 함(빌드 복잡도 최소화).
- 직렬화: kotlinx.serialization (번들 JSON 로딩).
- 네트워크(선택): OkHttp 또는 Ktor 중 하나만. 키 없으면 호출하지 않는다.
- 테스트: JUnit4/5 + kotlinx-coroutines-test + Turbine(선택).
- 패키지 베이스(가칭): `com.chargis.app` (실명/개인 식별 요소 금지)

---

## 4. 아키텍처

```
┌────────────────────────── UI (Compose) ──────────────────────────┐
│  DriveCardScreen   ParkedDetailScreen   ChargingScreen   Settings│
└───────────────▲──────────────────────────────────────────────────┘
                │ StateFlow<UiState>
        ┌───────┴────────┐
        │  TripViewModel │  ← 상태머신(§8.1) 보유
        └───────┬────────┘
   ┌────────────┼──────────────┬──────────────────┐
   ▼            ▼              ▼                  ▼
RecommendationEngine   ChargingSessionTracker   AlertScheduler   VoiceAnnouncer
 (순수 Kotlin)            (순수 Kotlin)           (순수 Kotlin)     (TtsPort 사용)
   ▲                         ▲
   │ 입력: Route, VehicleState, Stations, Profile
┌──┴──────────────────────── Ports (interface) ────────────────────┐
│ NaviPort  VehiclePort  ChargerDataPort  TtsPort  SttPort  LocationPort │
└──┬───────────────┬───────────────────────────────────────────────┘
   │               │
 Fake* (시뮬레이터)  Sdk* / Public* (실제 어댑터, SDK 확보 후)
```

### 디렉터리 구조
```
chargis/
├─ plan.md
├─ docs/
│  ├─ DECISIONS.md        # 가정/결정 로그 (한 줄씩)
│  ├─ OPEN_ISSUES.md      # 사용자 결정 필요 항목
│  └─ sdk/                # (사용자가 제공) SDK 문서/샘플
├─ app/src/main/
│  ├─ assets/
│  │  ├─ sample_stations.json   # 가상 샘플(실제 시설명 사용 금지, "OO휴게소" 형태)
│  │  ├─ sample_route.json      # 데모 경로(가상 좌표/거리)
│  │  └─ vehicle_profiles.json
│  ├─ res/values/strings.xml
│  └─ java/com/chargis/app/
│     ├─ domain/
│     │  ├─ model/            # §5
│     │  ├─ engine/           # RecommendationEngine, ChargeMath
│     │  └─ session/          # ChargingSessionTracker, AlertScheduler
│     ├─ port/                # §6 interfaces
│     ├─ data/
│     │  ├─ fake/             # FakeNavi, FakeVehicle, FakeChargerData, FakeTts
│     │  ├─ sdk/              # SdkNavi, SdkVehicle, SdkTts ... (TODO(SDK) 스텁)
│     │  └─ charger/          # PublicChargerData (공공 데이터), StaticStatsData
│     ├─ sim/                 # ScenarioSimulator, DemoController
│     ├─ voice/               # VoiceAnnouncer, 문구 조립
│     ├─ ui/                  # drive/ parked/ charging/ settings/ theme/
│     └─ AppContainer.kt, MainActivity.kt
└─ app/src/test/              # engine/session 단위 테스트
```

---

## 5. 도메인 모델 (Kotlin 초안)

```kotlin
data class GeoPoint(val lat: Double, val lon: Double)

data class VehicleProfile(
    val id: String,
    val displayName: String,
    val batteryKwh: Double,          // 사용 가능 용량
    val efficiencyKmPerKwh: Double,  // 평균 전비
    val maxChargeKw: Double          // 차량 최대 수용 출력
)

enum class DataSource { SDK, MANUAL, SIMULATED }

data class VehicleState(
    val socPercent: Double,          // 0..100
    val rangeKm: Double?,            // 주행가능거리(없으면 null)
    val isDriving: Boolean,          // 주행/정차 구분 (SDK 불가 시 시뮬레이터/수동)
    val source: DataSource
)

data class RouteState(
    val current: GeoPoint,
    val destination: GeoPoint?,
    val remainingDistanceKm: Double,
    val remainingTimeMin: Int?
)

data class ChargerRule(
    val timeLimitMin: Int?,          // null = 제한 없음 또는 미상
    val amountLimitKrw: Int?,
    val note: String? = null
)

data class ChargingStation(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val kind: StationKind,            // 2026-10-25: isRestStop → 시설 종류(휴게소/쉼터/상업/공공 등)
    val distanceAlongRouteKm: Double, // 현재 위치 기준 경로상 거리
    val detourKm: Double,             // 경로에서 충전소까지 편도 거리. 엔진은 왕복으로 센다(휴게소는 0)
    val maxKw: Double,                // 해당 소의 최대 출력
    val totalChargers: Int,
    val availableChargers: Int?,      // null = 실시간 미상
    val expectedWaitMin: Int?,        // 실시간 or 시간대별 통계 기반 추정
    val rule: ChargerRule
)

data class ScoreWeights(
    val arrivalSoc: Double = 0.25,
    val power: Double = 0.25,
    val availability: Double = 0.20,
    val detour: Double = 0.10,
    val wait: Double = 0.20
)

data class RecommendationConfig(
    val safetyMargin: Double = 0.15,          // 필요 에너지에 곱할 (1+margin)
    val minArrivalSocPercent: Double = 10.0,  // 도착 시 최소 잔량
    val maxTargetSocPercent: Double = 80.0,   // 기본 권장 상한(사용자 조정 가능)
    val roundUpStepPercent: Double = 5.0,     // 목표 %를 5% 단위로 올림
    val avgPowerFactor: Double = 0.75,        // ≤80% 구간 평균 출력 계수 (가정, 튜닝 대상)
    val taperFactorAboveCap: Double = 0.35,   // >80% 구간 추가 감쇠 계수 (가정)
    val announceWithinKm: Double = 50.0,      // 추천 지점 N km 전 안내
    val weights: ScoreWeights = ScoreWeights()
)

data class Recommendation(
    val station: ChargingStation,
    val arrivalSocPercent: Double,
    val targetSocPercent: Double,
    val chargeMinutes: Int,
    val waitMinutes: Int,
    val timeLimitMin: Int?,
    val willHitTimeLimit: Boolean,
    val socAtTimeLimitPercent: Double?,       // 제한 시각에 도달 가능한 SoC
    val needsAnotherStop: Boolean,            // 목표(상한) 충전으로도 목적지 미도달
    val score: Double,
    val reasonCodes: List<ReasonCode>
)

enum class ReasonCode { MAX_POWER, LOW_WAIT, ENOUGH_CHARGERS, LATEST_SAFE_STOP, NO_DETOUR, TIME_LIMIT_RISK }
```

**기본 차종 프로필(플레이스홀더, 실제 차종 확인 전까지 사용):**
`id=default, batteryKwh=77.4, efficiencyKmPerKwh=5.0, maxChargeKw=150`
→ 모든 값은 `assets/vehicle_profiles.json`에서 읽고, 코드에 상수로 박지 않는다.

---

## 6. 포트(인터페이스)와 구현체

```kotlin
interface NaviPort {
    val routeState: Flow<RouteState?>
    suspend fun addWaypoint(station: ChargingStation): Result<Unit>   // F4: 경유지 추가 + 재탐색
    suspend fun requestReRoute(): Result<Unit>
}

interface VehiclePort {
    val vehicleState: Flow<VehicleState>        // SDK 불가 시 Manual 구현체가 사용자 입력값을 방출
    suspend fun isSupported(): Boolean          // SoC 수신 가능 여부
}

interface ChargerDataPort {
    // 경로 정보를 받아 "경로상 충전소"(휴게소·쉼터·국도변·상업·공공시설) 후보 반환 (distanceAlongRoute 포함)
    suspend fun stationsAlongRoute(route: RouteState): List<ChargingStation>
    fun liveStatus(stationId: String): Flow<StationLiveStatus?>   // 불가 시 empty flow
}

interface TtsPort { suspend fun speak(text: String, priority: Priority = Priority.NORMAL) }
interface SttPort { fun listenForCommand(allowed: Set<VoiceCommand>): Flow<VoiceCommand> }  // 선택
interface LocationPort { val location: Flow<GeoPoint> }
```

| 포트 | Fake (먼저) | 실제 (SDK/데이터 확보 후) | 폴백 |
|---|---|---|---|
| NaviPort | `FakeNavi` (시뮬레이터 구동) | `SdkNavi` → NaviHelper: getCurrentLocationInfo, getDestinationInfo, getRouteStateInfo, getTBTInfo, getChargerOperatorInfo, addWaypoint, requestReRoute, addListener | 없음(데모는 Fake) |
| VehiclePort | `FakeVehicle` | `SdkVehicle` → Vehicle SDK(SoC, 주행가능거리; 권한 승인 필요) | `ManualVehicle`: 사용자가 정차 중 %를 입력(터치/음성) |
| ChargerDataPort | `FakeChargerData`(sample_stations.json) | `PublicChargerData`(공공 충전소 데이터) | `StaticStatsData`: 정적 출력·대수 + 시간대별 혼잡 통계 |
| TtsPort | `FakeTts`(로그+화면 자막) | `SdkTts` → TextToSpeech SDK | Android 기본 `TextToSpeech`(SDK 불가 시) |
| SttPort | `FakeStt`(버튼 이벤트) | `SdkStt` → SpeechToText SDK | 버튼만 사용 |
| LocationPort | `FakeLocation` | `SdkLocation` → FusedLocation SDK | NaviPort의 현재 위치 |

**어댑터 작성 규칙:** SDK 호출 실패/null은 모두 `Result`/null-safe로 처리하고, UI에는 "정보 없음" 상태로 전달한다. 앱이 크래시하면 안 된다.

---

## 7. 추천 엔진 명세 (F1, F2)

### 7.1 수식 (모두 `ChargeMath`에 순수 함수로)
```
socPerKm            = (1 / efficiencyKmPerKwh) / batteryKwh * 100            // %/km
arrivalSoc(d)       = soc - d * socPerKm
requiredSoc(dist)   = dist * socPerKm * (1 + safetyMargin) + minArrivalSocPercent
```

### 7.2 알고리즘
```
입력: route, vehicleState, stations, profile, config

1. 충전 필요 여부
   if requiredSoc(route.remainingDistanceKm) <= soc  → "이번 구간 충전 불필요" 반환(추천 없음)

2. 후보 필터 (하드 조건)
   - 0 < station.distanceAlongRouteKm
   - arrivalSoc(station.distanceAlongRouteKm + station.detourKm) >= minArrivalSocPercent
   → 조건 만족 후보 중 가장 먼 것 = "마지막 안전 휴게소(LATEST_SAFE_STOP)"

3. 후보별 목표 충전량(F2)
   remainingAfter = route.remainingDistanceKm - station.distanceAlongRouteKm
   needed = requiredSoc(remainingAfter)
   if needed <= maxTargetSocPercent:  target = ceilToStep(needed, roundUpStepPercent); needsAnotherStop = false
   else:                              target = maxTargetSocPercent;                      needsAnotherStop = true
   if target <= arrivalSoc:           이 후보는 충전 불필요 → 후보에서 제외

4. 충전 시간 추정 (세션 트래커도 같은 함수 사용)
   P = min(station.maxKw, profile.maxChargeKw)
   cap(80%) 이하 구간 출력 = P * avgPowerFactor
   cap 초과 구간 출력     = P * avgPowerFactor * taperFactorAboveCap
   chargeMinutes = Σ (구간 kWh / 구간 출력) * 60

5. 시간 제한 평가 (F3와 공유)
   if rule.timeLimitMin != null:
       socAtLimit = 시간제한 내 충전 가능한 SoC (4의 역산)
       willHitTimeLimit = chargeMinutes > timeLimitMin
       if willHitTimeLimit and socAtLimit < target → 목표 미달 가능성(TIME_LIMIT_RISK)
       if 목적지 도달에 필요한 SoC(needed) > socAtLimit → needsAnotherStop = true

6. 점수 (0..1 정규화 후 가중합)
   score = w.arrivalSoc*norm(arrivalSoc) + w.power*norm(P) + w.availability*norm(available/total)
         - w.detour*norm(detourKm) - w.wait*norm(waitMinutes)
   availableChargers == null 이면 availability 항은 중립값(0.5)으로 처리하고 reason에 표시하지 않는다.

7. 정렬: score 내림차순 → 1순위 + 대안 최대 2개 반환
```

### 7.3 추가 규칙
- **안내 시점:** 1순위 휴게소까지 `announceWithinKm` 이하가 되면 1회 음성 안내. 이미 안내/수락/거절한 휴게소는 재안내 금지.
- **히스테리시스:** 수락된 추천은 상태가 조금 변해도 뒤집지 않는다(점수 차가 크게 벌어지거나 후보가 불가능해질 때만 재계산).
- **혼잡 변형(명절):** 1순위의 `expectedWaitMin`이 임계(기본 20분) 초과면 다음 휴게소/대안을 함께 제시하고 "대기 시간 vs 도착 시 배터리" 비교 카드 표시.
- **사용자 조정:** 상한(기본 80%), 안전 마진, 최소 도착 잔량을 설정 화면에서 변경 가능. 변경값은 DataStore에 저장.
- **휴게소 밖 충전소(2026-10-25 추가):** 졸음쉼터·국도 휴게소·국도변/IC 근처·상업·공공시설도 후보. `detourKm`(편도)를 왕복으로 세고, 편도 0.5km를 넘게 벗어나면 진출입 5분을 더한다. 휴게소·쉼터가 아니면 순위에만 5분 가산. 이용 가능 시간을 알면 도착 후 30분 동안 열려 있는 곳만 후보. 아파트·사업장 등 외부인이 쓰기 어려운 시설과 이용자 제한(`limitYn`/`limitDetail`) 충전기는 제외. `limitDetail`은 시간 제한이 아니라 이용자 제한 사유다.
- 모든 수치는 **가정 기본값**이며 `RecommendationConfig`로만 조정한다.

---

## 8. 충전 세션 & 알림 (F3)

### 8.1 상태머신 (`TripViewModel`)
```
Idle ──(목적지 설정)──▶ TripPlanned ──(충전 필요 판단)──▶ Driving
Driving ──(추천 안내)──▶ AwaitingDecision ──수락──▶ NavigatingToCharger ──도착/정차──▶ Arrived
                                  └─거절/무시─▶ Driving(다음 후보)
Arrived ──(충전 시작 감지/버튼)──▶ Charging ──(목표 도달 or 중단)──▶ Done ──▶ Resuming ──▶ Driving
```
- 충전 시작 감지: SDK로 불가하면 **"충전 시작" 버튼**(정차 상태에서만 활성)으로 대체.
- 충전 중 현재 SoC: VehiclePort 값 사용, 불가 시 `ChargeMath` 시간 기반 추정.

### 8.2 `ChargingSessionTracker` 출력
`currentSoc, targetSoc, minutesToTarget, minutesToLimit(null 가능), predictedSocAtLimit, departAtEpoch`
- 남은 시간은 최근 N초 SoC 증가율 이동평균으로 보정, 샘플 부족 시 §7.2-4의 모델값 사용.

### 8.3 알림 이벤트 (`AlertScheduler`)
| 이벤트 | 조건 | 채널 |
|---|---|---|
| `RULE_NOTICE` | 충전 시작 시 `timeLimitMin != null` | 화면 + TTS: "이 충전기는 {n}분 제한이 있습니다" |
| `LIMIT_T10` / `LIMIT_T5` | 제한까지 10분/5분 남음 & 목표 미도달 | TTS + 배너: "{m}분 뒤 충전이 종료됩니다. 지금 약 {x}%, 종료 시 약 {y}% 예상" |
| `TARGET_T5` | 목표 도달 5분 전 | TTS: "곧 목표 충전량입니다. 이동 준비하세요" |
| `TARGET_REACHED` | 목표 도달 | TTS + "출발 가능" 버튼 → Resuming |
| `LIMIT_SHORTFALL` | 제한 내 목표 미달 확정 | 다음 충전지 재추천 제안 |
- 같은 이벤트는 세션당 1회만 발화(idempotent). 데모 가속 시에도 순서 보장.

---

## 9. UI/UX 명세

### 9.1 모드 전환
`VehicleState.isDriving == true` → **DriveMode**, 아니면 **ParkedMode**. 전환 시 입력 가능한 요소가 즉시 바뀐다.

### 9.2 DriveMode (주행 중)
- 화면에는 **카드 1장**만. 큰 글씨(핵심 문구 1~2줄) + 큰 버튼 2개(**수락 / 다음 휴게소**).
- 스크롤, 텍스트 입력, 리스트, 작은 아이콘 버튼 금지. 터치 타깃은 크게(≥ 64dp 권장).
- 모든 안내는 TTS 동반. 카드 예: `40km 앞 OO휴게소 · 65%까지 충전`
- 카드 종류: 추천 / 혼잡 대안 비교(2열 큰 선택지) / 충전 필요 없음(숨김 또는 최소 표시) / 경로 변경 알림.

### 9.3 ParkedMode (정차·충전 중)
- **ChargingScreen**: 큰 게이지(현재 → 목표), `목표까지 N분`, `제한까지 N분`, `출발 예정 HH:MM`을 크게. 동승자도 읽을 수 있게 대비/글자 크기 확보.
- **상세 화면**: 후보 비교 표(도착 SoC·출력·가용 대수·대기), 설정(상한/마진/차종), 수동 SoC 입력.
- 수동 SoC 입력은 ParkedMode에서만 활성.

### 9.4 접근성/표현
- 고대비 다크 테마 기본(차량 화면). 색에만 의존하지 말고 아이콘·텍스트 병행.
- 불확실 데이터는 숨기지 말고 "정보 없음 / 통계 기반"으로 표기(예: 실시간 가용 대수 미상).

---

## 10. 데모 시뮬레이터 (시연 영상의 핵심)

`ScenarioSimulator`가 `FakeNavi/FakeVehicle/FakeChargerData/FakeLocation`을 한 시계(clock)로 구동한다.

### 10.1 요구사항
- 시나리오를 JSON(`sample_route.json`)으로 정의: 총거리, 시작 SoC, 휴게소 목록(경로상 거리·출력·대수·시간제한·혼잡), 속도 프로파일.
- **시간 배속**(1×/5×/20×/60×)과 일시정지, 특정 지점으로 점프. 개발자 오버레이로 토글(릴리스 빌드에선 숨김 가능).
- 시나리오 2종:
  1. **S1 기본(서울→속초 가상 경로):** 출발 SoC 60%, 총 210km. 중간 충전 1회 필요 → 추천 → 수락 → 경유지 추가 → 도착 → 충전 → 시간 제한 알림 → 재출발.
  2. **S2 혼잡(명절):** 1순위 휴게소 대기 30분 → 대안 비교 카드 → 다음 휴게소 선택.
- 휴게소 이름은 **가상 표기("OO휴게소", "△△휴게소")** 로 하고 실제 시설·사업자 상표를 쓰지 않는다.
- 시연 영상은 에뮬레이터 화면 녹화로 **S1을 3분 이내**에 재현 가능해야 한다.

### 10.2 결정성
같은 시나리오 + 같은 시드 → 같은 이벤트 순서/시각. 테스트에서 가상 시계(`TestScope`)로 검증한다.

---

## 11. 음성 문구 템플릿 (`strings.xml`, 한국어)

| 키 | 문구 |
|---|---|
| `tts_trip_summary` | 이번 이동은 중간 충전 {n}회가 필요합니다. |
| `tts_recommend` | {km}킬로미터 앞 {name}에서 충전을 추천합니다. 목적지까지는 {target}퍼센트면 충분합니다. |
| `tts_need_another` | 이번 충전만으로는 목적지까지 부족해 다음 충전이 한 번 더 필요합니다. |
| `tts_waypoint_added` | {name}을 경유지로 추가했습니다. |
| `tts_rule_notice` | 이 충전기는 {n}분 제한이 있습니다. |
| `tts_limit_warn` | {m}분 뒤 충전이 종료됩니다. 현재 약 {x}퍼센트, 종료 시 약 {y}퍼센트가 예상됩니다. |
| `tts_target_soon` | 곧 목표 충전량입니다. 이동 준비하세요. |
| `tts_resume` | 충전을 마쳤습니다. 길 안내를 이어 갑니다. |

TTS는 짧게(문장당 15초 이내), 주행 중에는 한 번에 한 문장만 발화한다. 중복 발화는 `VoiceAnnouncer`가 큐/우선순위로 방지.

---

## 12. 테스트 전략

### 12.1 단위 테스트(필수, 엔진/세션)
기본 프로필(77.4kWh, 5.0km/kWh, 150kW) 기준 → `socPerKm ≈ 0.2584 %/km`.

| # | 입력 | 기대 결과(허용오차 ±0.1, 구현 후 손계산과 교차 확인) |
|---|---|---|
| T1 | soc 60%, 남은 210km, 후보 A: 경로상 90km | 충전 필요 판단 true(필요 72.4% > 60%). A 도착 SoC ≈ **36.7%**, 목표 **50%**(필요 45.66% → 5% 올림), `needsAnotherStop=false` |
| T2 | 도착 20%, 50kW 소, 시간제한 40분, 목표 80% | 효과 출력 37.5kW, 충전시간 ≈ **74.3분**, `willHitTimeLimit=true`, 제한 시점 SoC ≈ **52.3%** |
| T3 | soc 90%, 남은 100km | 충전 불필요(필요 ≈ 36.7%) → 추천 없음 |
| T4 | 모든 후보 도착 SoC < 10% | 추천 없음 + "가능한 가장 가까운 충전소" 경고 상태 |
| T5 | `availableChargers=null` 후보 | availability 중립 처리, 크래시 없음 |
| T6 | 1순위 대기 30분(임계 20분 초과) | 대안 포함 결과(2개 이상) 반환 |
| T7 | 같은 추천 재평가 반복 | 이미 안내한 휴게소 재안내 없음(히스테리시스) |
| T8 | 알림 스케줄 | 제한 T-10/T-5, 목표 T-5 이벤트 각 1회, 순서 정확 |

### 12.2 통합/시나리오 테스트
- `ScenarioSimulator` S1/S2를 가상 시계로 끝까지 실행하고 상태 전이 순서를 검증(Idle→…→Driving).
- 어댑터 계약 테스트: 모든 `Fake*`와 실제 어댑터가 동일한 포트 계약(null/오류 처리)을 만족.

### 12.3 수동 점검(에뮬레이터)
DriveMode에서 스크롤/입력이 불가능한지, ParkedMode 전환이 즉시 되는지, 야간 테마 가독성.

---

## 13. 마일스톤 & 작업 체크리스트

> 일정은 신청서 기준. 오늘 = 10/24(토). 각 단계 끝에 빌드·테스트 통과 + `docs/DECISIONS.md` 갱신.

### M0 · 10/24–10/25 · 뼈대 + 엔진
- [x] Gradle 프로젝트 생성(Compose, minSdk 28), 패키지 구조, `AppContainer`
- [x] 도메인 모델(§5), 포트(§6), `ChargeMath`
- [x] `RecommendationEngine` + T1~T7 테스트 통과
- [x] `assets/` 샘플 JSON, `vehicle_profiles.json`

### M1 · 10/25–10/28 · 시뮬레이터 + 핵심 화면
- [x] `ScenarioSimulator` + `Fake*` 구현, 배속/점프 컨트롤
- [x] `TripViewModel` 상태머신(§8.1) — 실제로는 `TripController`(안드로이드 의존 없음)와 `TripStateMachine`
- [x] DriveCard(추천/수락/다음), ParkedDetail, 최소 ChargingScreen
- [x] `FakeTts`(자막) + 기본 `TextToSpeech` 폴백
- [x] **10/28 설명회용 질문 목록 확정(§14)**

### ◆ 10/28 · 설명회 후 조정
- [ ] 확인 결과를 `docs/DECISIONS.md`에 반영, 이중안 확정, 미지원 기능은 폴백 구현체로 고정
- [ ] SDK 문서/샘플을 `docs/sdk/`에 두고 에이전트에게 재전달

### M2 · 10/29–11/7 · F1/F2 실데이터화
- [x] `ChargerDataPort` 실제/정적 구현 — 공공데이터 어댑터(실키 검증 전)와 번들 샘플. 시간대별 혼잡 통계는 샘플에만 있다
- [ ] `VehiclePort` SDK 확정 — 대안인 배터리 직접 입력(추정 + 정차 중 고치기)은 구현했다
- [x] 후보 비교 표, 혼잡 대안 카드(S2), 설정 화면(상한/마진/목적지 잔량/충전소 범위/배터리 값/음성), 차종 선택
- [ ] `SdkNavi` 경로/위치 연동(SDK 확보 시)

### M3 · 11/8–11/13 · F3/F4 + 영상
- [x] `ChargingSessionTracker` + `AlertScheduler`(§8) + ChargingScreen 완성(동승자 큰 표시)
- [ ] `addWaypoint`/`requestReRoute` 연동(F4) — Fake 내비로 동작, 실패 시 안내 문구는 구현. SDK 연동은 남음
- [ ] 시연 영상용 S1 리허설, 화면 녹화 점검(개인정보 비노출)

### M4 · 11/14 · 제출
- [ ] 릴리스 점검, README(개인정보 없음), 화면 시안/영상/저장소 링크 정리
- [ ] 구현하지 못한 기능은 시안+계획으로 표기

### (1차 합격 시) M5 · 11/23–12/2 · 안정화
- [ ] F3/F4 완성, 크래시/ANR 점검, PT 시연 시나리오 확정, 시연용 APK

---

## 14. 10/28 설명회 확인 질문 (사전 작성)

1. Vehicle SDK로 받을 수 있는 항목은? (SoC, 주행가능거리, 기어/속도, 충전 상태·충전 출력) 권한 승인 절차와 에뮬레이터 제공 여부는?
2. NaviHelper의 경유지 추가(`addWaypoint`) 후 `requestReRoute` 호출 순서/제약, 경로상 지점 목록(경로 폴리라인) 조회 가능 여부는?
3. `getChargerOperatorInfo`로 얻는 정보 범위(사업자, 출력, 규칙, 실시간 상태)는?
4. 공공 충전소 데이터 사용 가능 여부, 호출 방식, 키 발급, 호출 제한은? 앱에 번들해도 되는지?
5. 에뮬레이터 화면 해상도/폼팩터, 동승석 화면 지원 여부, 앱 마켓 입점 시 UI 제한(주행 중 규정)은?
6. TTS/STT SDK 사용 제약(발화 우선순위, 주행 중 허용 규정)은?
7. 탑재 차종 목록과 차종별 배터리 용량·전비·충전 성능 값의 공식 출처는?

> 답변은 `docs/DECISIONS.md`에 그대로 기록한다.

---

## 15. 개인정보 · 제출 유의 (에이전트가 지킬 것)

- 코드, 주석, README, 커밋 작성자(`git config user.name/email`은 익명 값 사용), 앱 정보, 스크린샷, 시연 영상, 로그에 **실명·전화번호·이메일·계정정보를 넣지 않는다.**
- 패키지명/앱 표기는 `Chargis`와 가칭 패키지 `com.chargis.app`만 사용.
- 외부 서비스 로그인 계정 생성, 결제, 키 하드코딩 금지.
- 샘플 데이터는 **가상 데이터**로 만들고 실제 시설·사업자 상표를 사용하지 않는다.
- 저장소를 공개/공유하기 전 `grep`으로 개인정보 문자열이 없는지 점검하는 스크립트(`scripts/check_privacy.sh`)를 만든다. (패턴은 사용자가 로컬에서 채우도록 비워 두고 구조만 제공)

---

## 16. 완료 정의 (DoD)

**1차 제출(11/14):**
- [ ] 에뮬레이터에서 S1 시나리오가 처음부터 끝까지 중단 없이 재생된다(F1, F2, F3 최소, F4는 Fake 또는 SDK).
- [ ] 주행 중 화면에 카드 1장 + 음성만 노출되고, 정차 시 상세가 열린다.
- [ ] 추천 엔진 단위 테스트(T1~T8) 전부 통과, `assembleDebug` 성공.
- [ ] SDK/데이터 미확정 항목은 폴백 구현체로 동작하며, 화면에 "통계 기반/직접 입력" 등 출처가 구분되어 표시된다.
- [ ] 시연 영상 3분 이내 녹화 가능, 개인정보 노출 없음.
- [ ] `docs/DECISIONS.md`, `docs/OPEN_ISSUES.md` 최신화.

**최종(12/3–12/4 PT):** F3/F4 실동작, 시연용 APK 빌드, 크래시 없음.

---

## 17. Kickoff — 지금 바로 실행할 작업 (순서대로)

1. `docs/DECISIONS.md`, `docs/OPEN_ISSUES.md` 생성. SDK 존재 여부 확인 후 모드(Fake 우선 / 실제 연동) 기록.
2. Gradle 프로젝트 생성: Kotlin, Compose, `minSdk 28`, 패키지 `com.chargis.app`, 수동 DI 골격. `./gradlew assembleDebug` 통과 확인.
3. `domain/model` 전부(§5), `port/` 인터페이스(§6) 작성.
4. `ChargeMath` + `RecommendationEngine` 구현 후 T1~T7 테스트 작성·통과. (§7, §12)
5. `assets/vehicle_profiles.json`, `sample_stations.json`, `sample_route.json`(S1) 작성(가상 데이터).
6. `ScenarioSimulator` + `Fake*` 구현, 개발자 오버레이(배속/점프).
7. `TripViewModel` + DriveCard 화면까지 연결해 **S1의 "추천 안내 → 수락" 구간**이 에뮬레이터에서 보이게 한다.
8. 완료 시: 무엇이 동작하는지 3줄 이내 보고 + `OPEN_ISSUES.md`에 쌓인 사용자 결정 항목 목록.
