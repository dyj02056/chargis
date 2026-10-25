package com.chargis.app.port

import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.ConnectorType
import com.chargis.app.domain.model.Priority
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.UserSettings
import com.chargis.app.domain.model.VehicleState
import com.chargis.app.domain.model.VoiceCommand
import kotlinx.coroutines.flow.Flow

/*
 * "포트(Port)"는 앱이 바깥 세상(차량, 내비, 충전소 서버, 스피커)과 연결되는 콘센트 모양을 정해 둔 것이다.
 * interface는 "이런 기능이 있어야 한다"는 약속만 적고, 실제 동작은 따로 만든 구현체가 채운다.
 *
 * 덕분에 같은 앱 코드에 플러그만 바꿔 꽂을 수 있다.
 * - 데모/테스트: 가짜 데이터를 내보내는 Fake 구현체 (data/fake/Fakes.kt)
 * - 실제 차량: 현대차그룹 SDK를 쓰는 구현체 (SDK 제공 시 추가 예정)
 *
 * Flow = 시간이 흐르며 새 값이 계속 흘러나오는 데이터 줄기. 예) 배터리 %가 바뀔 때마다 새 값 도착
 * suspend fun = 결과가 나올 때까지 기다릴 수 있는 함수(네트워크 요청처럼 시간이 걸리는 작업)
 */

/** 내비게이션 연결. 현재 경로를 읽고, 충전소를 경유지로 추가할 수 있다. */
interface NaviPort {
    val routeState: Flow<RouteState?>
    suspend fun addWaypoint(station: ChargingStation): Result<Unit>   // F4: 경유지 추가 + 재탐색
    suspend fun removeWaypoint(station: ChargingStation): Result<Unit> // 수락한 경유지를 다른 곳으로 바꿀 때 이전 것을 지운다
    suspend fun requestReRoute(): Result<Unit>                        // 경로 다시 찾기
}

/** 차량 연결. 배터리 %와 주행/정차 상태를 읽는다. */
interface VehiclePort {
    val vehicleState: Flow<VehicleState>        // SoC를 못 받으면 앱이 운전자 입력으로 추정한다(ManualSocEstimator)
    suspend fun isSupported(): Boolean          // SoC 수신 가능 여부

    /** 배터리 예열(컨디셔닝)을 켜거나 끈다. 차량이 지원하지 않거나 SDK에 기능이 없으면 실패를 돌려준다. */
    suspend fun requestPreconditioning(on: Boolean): Result<Unit> =
        Result.failure(UnsupportedOperationException("preconditioning not supported"))
}

/** 충전소 정보 연결. 경로 위 충전소 목록을 준다(빈 대수·대기 같은 실시간 상황도 목록에 담겨 온다). */
interface ChargerDataPort {
    /**
     * 경로 정보를 받아 "경로상 충전소" 후보 반환 (distanceAlongRoute 포함).
     * [connectors]를 주면 그 충전구를 쓸 수 있는 충전기만 세어 빈 대수·출력을 정한다(null = 거르지 않음).
     */
    suspend fun stationsAlongRoute(route: RouteState, connectors: Set<ConnectorType>? = null): List<ChargingStation>
}

/** 음성 출력(TTS = Text To Speech, 글자를 소리로 읽어 줌). */
interface TtsPort {
    suspend fun speak(text: String, priority: Priority = Priority.NORMAL)
}

/** 음성 인식(STT = Speech To Text, 말을 글자로 바꿈). [allowed]에 있는 명령만 알아듣는다. */
interface SttPort {
    fun listenForCommand(allowed: Set<VoiceCommand>): Flow<VoiceCommand>
}

/** 운전자 설정 저장소(F5). 값이 바뀌면 [settings]로 새 값이 흘러나온다. */
interface SettingsStore {
    val settings: Flow<UserSettings>
    suspend fun update(transform: (UserSettings) -> UserSettings)
}

/**
 * 진행 중인 여행 저장소(B5). 앱이 꺼졌다 켜져도 같은 표로 이어 가게 한다.
 * 내용은 TripController가 만든 JSON 글자 하나다. null을 저장하면 지운다.
 */
interface TripStore {
    suspend fun load(): String?
    suspend fun save(json: String?)
}
