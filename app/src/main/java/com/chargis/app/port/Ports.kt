package com.chargis.app.port

import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.Priority
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.StationLiveStatus
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
    suspend fun requestReRoute(): Result<Unit>                        // 경로 다시 찾기
}

/** 차량 연결. 배터리 %와 주행/정차 상태를 읽는다. */
interface VehiclePort {
    val vehicleState: Flow<VehicleState>        // SDK 불가 시 Manual 구현체가 사용자 입력값을 방출
    suspend fun isSupported(): Boolean          // SoC 수신 가능 여부
}

/** 충전소 정보 연결. 경로 위 휴게소 충전소 목록과 실시간 혼잡도를 준다. */
interface ChargerDataPort {
    // 경로 정보를 받아 "경로상 휴게소 충전소" 후보 반환 (distanceAlongRoute 포함)
    suspend fun stationsAlongRoute(route: RouteState): List<ChargingStation>
    fun liveStatus(stationId: String): Flow<StationLiveStatus?>   // 불가 시 empty flow
}

/** 음성 출력(TTS = Text To Speech, 글자를 소리로 읽어 줌). */
interface TtsPort {
    suspend fun speak(text: String, priority: Priority = Priority.NORMAL)
}

/** 음성 인식(STT = Speech To Text, 말을 글자로 바꿈). [allowed]에 있는 명령만 알아듣는다. */
interface SttPort {
    fun listenForCommand(allowed: Set<VoiceCommand>): Flow<VoiceCommand>
}

/** 현재 위치(GPS). */
interface LocationPort {
    val location: Flow<GeoPoint>
}
