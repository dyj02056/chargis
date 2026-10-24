package com.chargis.app.data.asset

import com.chargis.app.domain.model.ChargerRule
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.VehicleProfile
import com.chargis.app.sim.Scenario
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 번들 JSON(assets) → 도메인 모델. 파일 읽기는 호출자가 맡아 JVM 테스트에서도 그대로 쓴다. */
class AssetCatalog(
    profilesJson: String,
    stationsJson: String,
    routesJson: String
) {
    private val profileFile = json.decodeFromString<ProfilesDto>(profilesJson)
    private val stationDtos = json.decodeFromString<StationsDto>(stationsJson).stations.associateBy { it.id }
    private val routeDtos = json.decodeFromString<RoutesDto>(routesJson).scenarios

    val profiles: List<VehicleProfile> = profileFile.profiles.map { it.toDomain() }
    val defaultProfile: VehicleProfile =
        profiles.firstOrNull { it.id == profileFile.defaultProfileId } ?: profiles.first()

    val scenarios: List<Scenario> = routeDtos.map { it.toScenario() }

    fun scenario(id: String): Scenario = scenarios.first { it.id == id }

    private fun ScenarioDto.toScenario(): Scenario {
        val overrides = stationOverrides.associateBy { it.id }
        val stations = stationIds.map { id ->
            val base = stationDtos.getValue(id)
            val o = overrides[id]
            base.copy(
                availableChargers = o?.availableChargers ?: base.availableChargers,
                expectedWaitMin = o?.expectedWaitMin ?: base.expectedWaitMin
            ).toDomain()
        }.sortedBy { it.distanceAlongRouteKm }
        return Scenario(
            id = id,
            title = title,
            seed = seed,
            profile = profiles.firstOrNull { it.id == profileId } ?: defaultProfile,
            totalDistanceKm = totalDistanceKm,
            startSoc = startSoc,
            speedKmh = speedKmh,
            startHour = startHour,
            start = start.toDomain(),
            destination = destination.toDomain(),
            stations = stations
        )
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class ProfilesDto(val defaultProfileId: String, val profiles: List<ProfileDto>)

@Serializable
private data class ProfileDto(
    val id: String,
    val displayName: String,
    val batteryKwh: Double,
    val efficiencyKmPerKwh: Double,
    val maxChargeKw: Double
) {
    fun toDomain() = VehicleProfile(id, displayName, batteryKwh, efficiencyKmPerKwh, maxChargeKw)
}

@Serializable
private data class StationsDto(val stations: List<StationDto>)

@Serializable
private data class StationDto(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val routeKm: Double,
    val detourKm: Double = 0.0,
    val isRestStop: Boolean = true,
    val maxKw: Double,
    val totalChargers: Int,
    val availableChargers: Int? = null,
    val expectedWaitMin: Int? = null,
    val timeLimitMin: Int? = null,
    val amountLimitKrw: Int? = null
) {
    /** distanceAlongRouteKm에는 경로 출발점 기준 거리(routeKm)를 담는다. 현재 위치 기준 변환은 FakeChargerData가 한다. */
    fun toDomain() = ChargingStation(
        id = id,
        name = name,
        location = GeoPoint(lat, lon),
        isRestStop = isRestStop,
        distanceAlongRouteKm = routeKm,
        detourKm = detourKm,
        maxKw = maxKw,
        totalChargers = totalChargers,
        availableChargers = availableChargers,
        expectedWaitMin = expectedWaitMin,
        rule = ChargerRule(timeLimitMin = timeLimitMin, amountLimitKrw = amountLimitKrw)
    )
}

@Serializable
private data class RoutesDto(val scenarios: List<ScenarioDto>)

@Serializable
private data class ScenarioDto(
    val id: String,
    val title: String,
    val seed: Int,
    val profileId: String,
    val totalDistanceKm: Double,
    val startSoc: Double,
    val speedKmh: Double,
    val startHour: Int,
    val start: PointDto,
    val destination: PointDto,
    val stationIds: List<String>,
    val stationOverrides: List<OverrideDto> = emptyList()
)

@Serializable
private data class PointDto(val lat: Double, val lon: Double) {
    fun toDomain() = GeoPoint(lat, lon)
}

@Serializable
private data class OverrideDto(
    val id: String,
    val availableChargers: Int? = null,
    val expectedWaitMin: Int? = null
)
