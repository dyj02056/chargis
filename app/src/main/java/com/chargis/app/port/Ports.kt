package com.chargis.app.port

import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.Priority
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.StationLiveStatus
import com.chargis.app.domain.model.VehicleState
import com.chargis.app.domain.model.VoiceCommand
import kotlinx.coroutines.flow.Flow

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
    // 경로 정보를 받아 "경로상 휴게소 충전소" 후보 반환 (distanceAlongRoute 포함)
    suspend fun stationsAlongRoute(route: RouteState): List<ChargingStation>
    fun liveStatus(stationId: String): Flow<StationLiveStatus?>   // 불가 시 empty flow
}

interface TtsPort {
    suspend fun speak(text: String, priority: Priority = Priority.NORMAL)
}

interface SttPort {
    fun listenForCommand(allowed: Set<VoiceCommand>): Flow<VoiceCommand>
}

interface LocationPort {
    val location: Flow<GeoPoint>
}
