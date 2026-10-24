# DECISIONS — 가정/결정 로그

한 줄에 하나씩, 날짜와 함께 기록한다. 바뀌면 지우지 말고 새 줄로 덮어쓴다.

- 2026-10-24 · `docs/sdk/`, `libs/` 없음 → **SDK 미제공, Fake 우선 모드**. 실제 어댑터는 `data/sdk/`에 `TODO(SDK)` 스텁만 둔다.
- 2026-10-24 · 빌드 툴체인: JDK 17(Temurin), Gradle 8.14.3, AGP 8.13.0, Kotlin 2.2.20, compileSdk/targetSdk 36, minSdk 28.
- 2026-10-24 · 개발 PC에 Android Studio 없음 → JDK 17·Gradle은 `~/tools`, Android SDK는 `%LOCALAPPDATA%\Android\Sdk`(Android Studio 기본 경로)에 CLI로 설치.
- 2026-10-24 · UI는 Jetpack Compose + Material3. Pleos 에뮬레이터에서 문제가 생기면 View 기반으로 전환한다.
- 2026-10-24 · 화면 폼팩터는 가로형 와이드 디스플레이로 가정한다. 레이아웃은 창 크기 기반으로 반응한다.
- 2026-10-24 · 커밋 작성자는 저장소 로컬 익명 값(`chargis-dev`)을 쓴다(§15).
- 2026-10-24 · plan.md에 정의가 없는 `StationLiveStatus`, `Priority`, `VoiceCommand`는 최소 형태로 정의했다(`domain/model`).
- 2026-10-24 · 추천 결과는 sealed `RecommendationResult`(NotNeeded / NoSafeStop / Recommended)로 반환한다. T4의 "가장 가까운 충전소 경고"는 NoSafeStop이 담는다.
- 2026-10-24 · 재안내 금지(§7.3 히스테리시스)는 엔진이 아니라 `AnnouncementGate`가 담당한다. 엔진은 무상태 순수 함수로 유지한다.
- 2026-10-24 · 점수 정규화는 후보 집합 안에서 min-max로 한다. 후보가 하나뿐이거나 값이 모두 같으면 그 항은 0.5로 둔다.
- 2026-10-24 · 혼잡 임계(`congestionWaitThresholdMin`, 기본 20분)를 `RecommendationConfig`에 추가했다.
- 2026-10-24 · ChargeMath의 cap은 `maxTargetSocPercent`가 아닌 고정 80%(`taperStartSocPercent`)로 둔다. 사용자가 상한을 바꿔도 감쇠 구간은 배터리 특성이므로 그대로다.
