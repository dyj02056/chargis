# PT 기술 Q&A 준비 자료

> 2차 PT(2026-12-03~04)의 기술 Q&A에서 팀원이 **앱의 구조와 로직을 직접 설명**할 수 있도록 정리했습니다. 생성형 AI 도구를 써서 개발했으므로, 심사위원은 "이 코드를 이해하고 있는가"를 물을 수 있습니다(설명회 31:40).
> 숫자와 파일 이름은 2026-10-28 코드 기준입니다. 코드가 바뀌면 이 문서도 함께 고칩니다.

---

## 1. 1분 설명

Chargis는 **내비가 정한 경로 위에서 "어느 충전소에서, 몇 %까지, 얼마 동안" 충전할지를 차가 먼저 말해 주는 앱**입니다.

1. **Vehicle SDK**로 배터리 %를 읽고, **NaviHelper SDK**로 순정 내비와 연결합니다.
2. 경로 위 충전소마다 "지금 들르면 **목적지에 몇 시에 도착하는가**"를 계산해 가장 빠른 곳을 고릅니다. 주행, 예상 대기, 충전, 들르는 시간을 모두 더합니다.
3. 목적지까지 필요한 에너지에 안전 마진을 더해 **목표 충전량**("50%까지")을 정합니다.
4. 운전자가 [경유지로 수락]을 누르면 순정 내비에 **경유지를 넣습니다**(NaviHelper `addWaypoint`).
5. 충전 중에는 **충전기 시간 제한**까지 남은 시간을 계산합니다. 제한 안에 목표에 못 닿으면 10분·5분 전에 경고하고, 끊기면 다음 충전소를 다시 추천합니다.
6. 모든 안내는 **TextToSpeech SDK**로 말하고, 음성 명령은 **SpeechToText SDK**로 듣습니다.

---

## 2. 구조 (한 장으로)

```
UI (Jetpack Compose)          DriveCardScreen(주행 중 카드 1장) · ParkedScreen(정차·충전) · ReceiptScreen
        ▲ StateFlow<TripUiState>
TripController                상태머신 + 화면 상태 조립. 0.5km 또는 30초마다 엔진을 다시 부른다
        │
도메인(순수 Kotlin)           RecommendationEngine · ChargeMath · AlertScheduler · AnnouncementGate · TripStateMachine
        │
포트(인터페이스)              NaviPort · VehiclePort · CarSignalsPort · ChargerDataPort · TtsPort · SttPort
        │
어댑터                        Pleos SDK(PleosCarSignals · PleosNavi · PleosTts · PleosStt) / 데모(Fake* + ScenarioSimulator) / 공공데이터
```

- **도메인과 UI는 SDK 클래스를 직접 쓰지 않습니다.** 구현체 선택은 `AppContainer.kt` 한 곳에서만 합니다. 그래서 같은 엔진이 데모 시뮬레이터, Pleos 에뮬레이터, (나중에) 실차에서 그대로 돕니다.
- **추천 엔진은 안드로이드 의존이 없는 순수 함수**입니다(`RecommendationEngine.recommend`). 입력이 같으면 결과가 같아 단위 테스트로 검증합니다.
- **하이브리드 데모:** 경로·시계·충전 진행은 시뮬레이터가 맡고, Pleos 차량 신호(배터리 %, 기어, 충전 스위치, 주행 중 화면 제한)와 순정 내비 경유지는 실제 SDK를 씁니다.

---

## 3. Pleos SDK를 어디에 썼나 (기술 활용성)

| SDK | 쓰는 곳(파일) | API | 퍼미션 | 핵심 기능과의 연결 |
|---|---|---|---|---|
| Vehicle 2.3.2 | `data/sdk/PleosCarSignals.kt` | `EvBattery` SoC 알림, 기어(`registerGearSelection`), 충전 스위치 | `CAR_ENERGY`, `CAR_DRIVE` | 발권할 때 출발 배터리, P면 가상 주행 정지, 충전 스위치 = 충전 시작·종료, 충전을 마친 뒤 D = 출발 |
| (AAOS) CarUxRestrictions | 같은 파일 | `CarUxRestrictionsManager` | - | 주행 중이면 정차 화면(상세·스크롤)을 가림 |
| NaviHelper 3.0.5 | `data/sdk/PleosNavi.kt`, `RoadDistanceRefiner.kt` | `addWaypoint`·`removeWaypoint`·`requestReRoute`, `requestTrip`, `getCurrentLocationInfo` | `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH` | 수락한 충전소를 순정 내비 경유지로 추가, 가까운 충전소 3곳의 실제 도로 거리 |
| TextToSpeech 3.3.1 | `data/sdk/PleosSpeech.kt`(PleosTts) | `speak`·`stop`, EventListener | `TTS_SERVICE` | 모든 안내를 차량 오디오로 말함(On-device, 앱 등록 불필요) |
| SpeechToText 3.3.1 | `data/sdk/PleosSpeech.kt`(PleosStt) | `request`·`stop`, ResultListener | `STT_SERVICE` | "수락", "다음", "충전 시작", "출발", "예열" 음성 명령 |

- 공공 충전소 API와 오픈소스는 쓸 수 있지만 기술 활용성 점수에는 들어가지 않습니다(운영사무국 답변). 발표에서는 위 표를 앞에 둡니다.
- **안 쓴 것과 그 이유:** FusedLocation은 위치를 NaviHelper `getCurrentLocationInfo`로 이미 받아서 쓰지 않았습니다. 차량 제어(배터리 예열)는 SDK가 아직 공개되지 않아, 버튼 대신 "차량 화면에서 켜 주세요"라고 안내합니다(`VehiclePort.canControlPreconditioning`).

---

## 4. 계산 로직 (외워 둘 것)

`ChargeMath.kt`, `RecommendationEngine.kt`

```
socPerKm        = (1 / 전비) / 배터리 용량 × 100            // 1km당 쓰는 배터리 %
필요 SoC(거리)  = 거리 × socPerKm × 1.15(안전 마진) + 10(최소 도착 잔량)
목표 충전량     = 필요 SoC를 5% 단위로 올림(상한 80%)
목적지 도착(분) = 주행 + 도착 시점 예상 대기 + 충전 시간 + 들르는 시간 3분 [+ 경로 밖이면 진출입 5분]
순위            = 목적지 도착 + 위험 가산(도착 잔량 20% 미만, 시간 제한 위험, 72시간 무충전) [+ 휴게소·쉼터가 아니면 5분]
도착 시 대기    = 실시간 × w + 시간대 통계 × (1 − w),  w = 0.5^(도착까지 분 ÷ 45)
충전 시간       = 차종 충전 곡선으로 SoC 0.25%씩 적분(곡선이 없으면 80% 이하 0.75C, 초과 0.35C)
```

**S1 예시(77.4kWh, 5.0km/kWh):** 210km에는 72.4%가 필요한데 지금 60%라서 충전이 필요합니다. 90km 지점 B 휴게소에 도착하면 약 36.7%가 남습니다. 남은 120km에는 45.7%가 필요하므로 **50%**까지 충전하라고 안내합니다.

**왜 "점수"가 아니라 "도착 시각"인가:** 처음에는 가중치 점수(정규화)를 썼지만, 후보가 늘거나 줄면 순위가 흔들리고 "왜 이곳인가"를 설명하기 어려웠습니다. 분 단위로 바꾸니 "다음 후보보다 7분 빠름"처럼 근거를 그대로 말할 수 있습니다(DECISIONS 2026-10-25 B1).

**두 번 들르기(S3):** 한 번으로 못 가면 첫 충전소 목표를 5%씩 바꿔 보며, 두 번째 충전소까지 합친 도착 시각이 가장 빠른 조합을 고릅니다. 그래서 느린 충전기에서는 덜, 빠른 충전기에서는 더 채웁니다.

---

## 5. 상태머신

`domain/session/TripStateMachine.kt`. 전이표에 없는 조합은 무시합니다(버튼을 두 번 눌러도 안전).

```
Idle → TripPlanned → Driving ⇄ AwaitingDecision → NavigatingToCharger → Arrived → Charging → Done
                        ▲                                                  └──(충전 없이 출발)──┐   │
                        └──────────────────────── Resuming ◀────────────────────────────────┘◀──┘(출발)
어느 상태에서든: 목적지 도착 → Finished, 초기화 → Idle
```

- **재안내 금지:** `AnnouncementGate`가 이미 안내했거나 거절한 충전소를 다시 말하지 않게 막습니다. 엔진은 상태가 없게 유지합니다.
- **충전 알림:** `AlertScheduler`가 제한 10분·5분 전(목표에 못 닿을 때만), 목표 5분 전, 도달을 한 번씩만 냅니다.
- **여행 복원:** 진행 상태를 몇 초마다 DataStore에 저장해 앱이 꺼졌다 켜져도 같은 표로 이어 갑니다.

---

## 6. 예상 질문과 답

| 질문 | 답(근거) |
|---|---|
| 순정 내비도 충전소를 추천하는데 무엇이 다른가? | 순정 내비는 "어디서"를 정합니다. Chargis는 그 경로 위에서 **얼마나·얼마 동안**과 **충전기 시간 제한**을 다룹니다. 제한 경고와 미달 시 재추천, 대기 대 도착 배터리 비교, 동승자용 출발 예정 시각이 차별점입니다. |
| 실제 차에서도 되는가? | 차량 신호와 내비 경유지는 실제 SDK로 Pleos 에뮬레이터에서 확인했습니다. 경로와 시계는 아직 데모 시뮬레이터입니다. 순정 내비 목적지로 시작하는 실제 주행 모드는 다음 단계입니다(TripController가 시뮬레이터 시계에 묶여 있어 "여행 진행원" 인터페이스로 떼어 내야 함). |
| 충전소 데이터는 어디서 오나? | 데모는 가상 샘플입니다. 환경부 공공데이터 어댑터와 키를 숨기는 중계 서버(`server/`)를 만들고 테스트했지만, 실제 키로는 아직 확인하지 못했습니다. |
| 시간 제한 정보는 어디서 오나? | 공공데이터에는 시간 제한 항목이 없습니다. 운영기관별로 확인한 규칙을 출처와 함께 `charger_rules.json`에 적으면 그 값을 쓰고, 모르면 "정보 없음"으로 표시합니다. 데모의 "40분"은 가정값입니다. |
| 주행 중 운전자 주의 분산은? | Distraction Optimization을 선언했고, 주행 중에는 카드 1장과 큰 버튼 2개만 보입니다. 같은 충전소는 한 번만 안내하고, CarUxRestrictions가 걸리면 상세 화면을 가립니다. 모든 안내는 음성으로도 나갑니다. |
| 음성 인식이 안내 음성을 다시 듣지 않나? | 아직 확인하지 못했습니다(OPEN_ISSUES). Pleos 에뮬레이터 마이크로 확인할 계획입니다. |
| 배터리 온도·충전 출력은? | Vehicle SDK에 없습니다. 온도는 바깥 기온으로 어림하고, 출력은 충전 곡선에서 읽은 값을 "추정 출력"으로 표시합니다. |
| 전비가 사양과 다르면? | 출발 직후에는 기온으로 어림하고, 달리면서 최근 40km의 실제 배터리 감소로 보정합니다(S4). 오르막은 고도 몫을 따로 빼서 "전비가 나쁘다"로 오해하지 않습니다(S6). |
| 차량에서 배터리 %를 못 받으면? | 설정의 "배터리 값 → 직접 입력"으로 출발 배터리에서 이어 계산하고, 충전소에 도착할 때 계기판 값으로 고치게 합니다. |
| 테스트는? | JVM 단위·화면 테스트 125개(Robolectric 포함)와 중계 서버 테스트 7개를 GitHub Actions에서 푸시마다 돌립니다. |
| AI를 어디까지 썼나? | 코드 작성과 문서화에 생성형 AI 보조를 썼습니다. 설계 결정과 가정은 모두 `docs/DECISIONS.md`에 날짜와 이유를 남겼고, 이 문서의 구조와 공식을 팀원이 직접 설명합니다. |

---

## 7. PT 현장 시연 순서 (Pleos 에뮬레이터)

PT에서는 실차 없이 Pleos Playground 에뮬레이터로 검증합니다. 아래 순서는 2026-10-28에 실제로 돌려 본 것입니다.

**준비**

```bash
adb install -r apk/JSW_Lab_Chargis_release.apk
```

```bash
adb forward tcp:8088 tcp:8088
```

- 브라우저에서 `127.0.0.1:8088`(Virtual Console)을 열어 둡니다. 페이지를 열어 둬야 차량 값이 바뀝니다(닫혀 있으면 `emulator_inactive`).
- Virtual Console에서 **Battery SOC를 60%**(S1) 또는 **45%**(S3)로, 기어를 **P**로 둡니다. 앱은 차량 배터리 %를 출발 배터리로 씁니다.
- 순정 내비에 목적지를 먼저 정해 두면, [경유지로 수락]을 눌렀을 때 내비가 "경유지 1개 포함"으로 다시 탐색합니다. 목적지가 없으면 내비 화면이 앞으로 나오므로, 앱 아이콘을 눌러 Chargis로 돌아옵니다.

**S1 흐름(약 3분, release 빌드는 실제 속도라 debug 빌드의 DEV 배속을 씀)**

1. S1 [발권] → 기어 **D** → "이번 이동은 중간 충전 1회가 필요합니다"(Pleos TTS)
2. B 휴게소 추천 카드 → [경유지로 수락] → "경유지 등록" 도장, 순정 내비에 경유지 추가
3. B 휴게소 도착(주행 중에는 상세 화면이 가려짐) → 기어 **P** → 도착 화면(목표 50%, 예상 9분, 제한 40분, 예상 요금)
4. Virtual Console **Charge Switch On** → 충전 화면(목표까지·제한까지·출발 예정) → 충전 완료 도장
5. Charge Switch Off → 기어 **D**(또는 [출발]) → 목적지 도착 → 정산 영수증(아낀 시간 18분)

**S3 흐름(F3 충전 시간 제한)**

1. Battery SOC 45% → S3 [발권] → "중간 충전 2회" → E 휴게소 추천(⚠ 40분 제한 안에 약 43%) → 수락
2. E 도착 → P → Charge Switch On → "10분 뒤 충전이 종료됩니다…", "5분 뒤…" → "제한 종료" 도장(43%)
3. 기어 D → F 휴게소 55% 재추천

**Virtual Console을 스크립트로 바꿀 때(리허설용)**

```bash
curl -X POST "http://127.0.0.1:8088/browser/session?client=rehearsal"
```

```bash
curl -X PUT "http://127.0.0.1:8088/vehicle/ev-battery?field=soc&value=60"
```

```bash
curl -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=Drive"
```

```bash
curl -X PUT "http://127.0.0.1:8088/vehicle/ev-battery?field=charge_switch&value=true"
```

- 세션은 3초 동안만 살아 있으므로, 스크립트로만 조작할 때는 첫 명령을 1초마다 다시 보냅니다.
