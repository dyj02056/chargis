package com.chargis.app.domain.engine

import com.chargis.app.domain.model.ChargerRule
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.DataSource
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.VehicleProfile
import com.chargis.app.domain.model.VehicleState

val defaultProfile = VehicleProfile(
    id = "default",
    displayName = "기본 차종",
    batteryKwh = 77.4,
    efficiencyKmPerKwh = 5.0,
    maxChargeKw = 150.0
)

fun route(remainingKm: Double) = RouteState(
    current = GeoPoint(0.0, 0.0),
    destination = GeoPoint(1.0, 1.0),
    remainingDistanceKm = remainingKm,
    remainingTimeMin = null
)

fun vehicle(soc: Double) = VehicleState(
    socPercent = soc,
    rangeKm = null,
    isDriving = true,
    source = DataSource.SIMULATED
)

fun station(
    id: String,
    distanceKm: Double,
    maxKw: Double = 100.0,
    total: Int = 4,
    available: Int? = 2,
    waitMin: Int? = 5,
    detourKm: Double = 0.0,
    timeLimitMin: Int? = null
) = ChargingStation(
    id = id,
    name = "$id 휴게소",
    location = GeoPoint(0.0, 0.0),
    isRestStop = true,
    distanceAlongRouteKm = distanceKm,
    detourKm = detourKm,
    maxKw = maxKw,
    totalChargers = total,
    availableChargers = available,
    expectedWaitMin = waitMin,
    rule = ChargerRule(timeLimitMin = timeLimitMin, amountLimitKrw = null)
)
