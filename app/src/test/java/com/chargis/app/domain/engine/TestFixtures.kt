package com.chargis.app.domain.engine

import com.chargis.app.domain.model.ChargerRule
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.DataSource
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.OpenHours
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.StationKind
import com.chargis.app.domain.model.VehicleProfile
import com.chargis.app.domain.model.VehicleState

// 테스트에서 되풀이해 쓰는 재료(픽스처)를 만드는 함수들이다.
// 테스트마다 긴 데이터를 다 적지 않고 station("B", 46.0)처럼 바뀌는 값만 적으면 되게 했다.

// 기본 차종: 배터리 77.4kWh, 전비 5km/kWh, 최대 150kW
val defaultProfile = VehicleProfile(
    id = "default",
    displayName = "기본 차종",
    batteryKwh = 77.4,
    efficiencyKmPerKwh = 5.0,
    maxChargeKw = 150.0
)

// 목적지까지 [remainingKm] 남은 경로
fun route(remainingKm: Double) = RouteState(
    current = GeoPoint(0.0, 0.0),
    destination = GeoPoint(1.0, 1.0),
    remainingDistanceKm = remainingKm,
    remainingTimeMin = null
)

// 배터리 [soc]%로 주행 중인 차
fun vehicle(soc: Double) = VehicleState(
    socPercent = soc,
    rangeKm = null,
    isDriving = true,
    source = DataSource.SIMULATED
)

// [distanceKm] 앞에 있는 충전소(기본은 고속도로 휴게소). 나머지 값은 평범한 기본값(100kW, 4대 중 2대 빔, 대기 5분)
fun station(
    id: String,
    distanceKm: Double,
    maxKw: Double = 100.0,
    total: Int = 4,
    available: Int? = 2,
    waitMin: Int? = 5,
    detourKm: Double = 0.0,
    timeLimitMin: Int? = null,
    kind: StationKind = StationKind.HIGHWAY_REST_STOP,
    openHours: OpenHours? = null
) = ChargingStation(
    id = id,
    name = "$id 휴게소",
    location = GeoPoint(0.0, 0.0),
    kind = kind,
    distanceAlongRouteKm = distanceKm,
    detourKm = detourKm,
    maxKw = maxKw,
    totalChargers = total,
    availableChargers = available,
    expectedWaitMin = waitMin,
    rule = ChargerRule(timeLimitMin = timeLimitMin, amountLimitKrw = null),
    openHours = openHours
)
