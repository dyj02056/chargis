package com.chargis.app.data.asset

import com.chargis.app.domain.model.ChargerRule
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.CurvePoint
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.OpenHours
import com.chargis.app.domain.model.StationKind
import com.chargis.app.domain.model.VehicleProfile
import com.chargis.app.sim.Scenario
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 번들 JSON(assets) → 도메인 모델. 파일 읽기는 호출자가 맡아 JVM 테스트에서도 그대로 쓴다.
 *
 * 앱에 함께 들어 있는 샘플 데이터 파일 3개를 읽어 앱이 쓰는 모양으로 바꾼다.
 * - vehicle_profiles.json: 차종 사양
 * - sample_stations.json: 충전소 목록(휴게소, 졸음쉼터, 국도변·상업시설 충전소)
 * - sample_route.json: 데모 시나리오(어떤 경로로, 어느 충전소들을 지나는지)
 *
 * DTO(Data Transfer Object)는 JSON 파일 모양 그대로 받아 두는 임시 상자다.
 * 파일 모양과 앱 내부 모양을 나눠 두면, 파일 형식이 바뀌어도 이 파일만 고치면 된다.
 */
class AssetCatalog(
    profilesJson: String,
    stationsJson: String,
    routesJson: String
) {
    private val profileFile = json.decodeFromString<ProfilesDto>(profilesJson)
    private val stationDtos = json.decodeFromString<StationsDto>(stationsJson).stations.associateBy { it.id }
    private val routeDtos = json.decodeFromString<RoutesDto>(routesJson).scenarios

    val profiles: List<VehicleProfile> = profileFile.profiles.map { it.toDomain() }
    // 기본 차종. 지정된 id가 없으면 목록의 첫 번째를 쓴다.
    val defaultProfile: VehicleProfile =
        profiles.firstOrNull { it.id == profileFile.defaultProfileId } ?: profiles.first()

    val scenarios: List<Scenario> = routeDtos.map { it.toScenario() }

    fun scenario(id: String): Scenario = scenarios.first { it.id == id }

    // 시나리오 한 건을 만든다. 시나리오가 충전소 id만 갖고 있으므로 충전소 목록에서 찾아 붙이고,
    // 시나리오별로 다르게 정한 혼잡도(overrides, 예: 명절이라 대기 30분)가 있으면 덮어쓴다.
    private fun ScenarioDto.toScenario(): Scenario {
        val overrides = stationOverrides.associateBy { it.id }
        val stations = stationIds.map { id ->
            val base = stationDtos.getValue(id)
            val o = overrides[id]
            base.copy(
                availableChargers = o?.availableChargers ?: base.availableChargers,
                expectedWaitMin = o?.expectedWaitMin ?: base.expectedWaitMin,
                waitByHour = o?.waitByHour ?: base.waitByHour
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
            outsideTempC = outsideTempC,
            consumptionFactor = consumptionFactor,
            start = start.toDomain(),
            destination = destination.toDomain(),
            stations = stations
        )
    }

    private companion object {
        // JSON에 모르는 항목이 있어도 오류 없이 무시한다(파일에 메모용 항목을 더해도 괜찮도록).
        val json = Json { ignoreUnknownKeys = true }
    }
}

// ---- 아래는 JSON 파일 모양을 그대로 옮긴 DTO들 ----
// @Serializable은 "이 상자는 JSON에서 자동으로 채울 수 있다"는 표시다. 값이 없을 때 쓸 기본값(= null 등)도 정해 둔다.

@Serializable
private data class ProfilesDto(val defaultProfileId: String, val profiles: List<ProfileDto>)

@Serializable
private data class ProfileDto(
    val id: String,
    val displayName: String,
    val batteryKwh: Double,
    val efficiencyKmPerKwh: Double,
    val maxChargeKw: Double,
    val chargeCurve: List<CurvePointDto>? = null
) {
    fun toDomain() = VehicleProfile(
        id, displayName, batteryKwh, efficiencyKmPerKwh, maxChargeKw,
        chargeCurve = chargeCurve?.map { CurvePoint(it.soc, it.kw) }?.sortedBy { it.socPercent }
    )
}

@Serializable
private data class CurvePointDto(val soc: Double, val kw: Double)

@Serializable
private data class StationsDto(val stations: List<StationDto>)

@Serializable
private data class StationDto(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val routeKm: Double,
    val detourKm: Double = 0.0,          // 경로에서 충전소까지 편도 거리(km)
    val kind: String = "HIGHWAY_REST_STOP", // StationKind 이름. 예) "SHELTER", "COMMERCIAL"
    val maxKw: Double,
    val totalChargers: Int,
    val availableChargers: Int? = null,
    val expectedWaitMin: Int? = null,
    val timeLimitMin: Int? = null,
    val amountLimitKrw: Int? = null,
    val waitByHour: List<Int>? = null,
    val useTime: String? = null,         // 이용 가능 시간. 예) "09:00~22:00". 없으면 미상
    val parkingFree: Boolean? = null
) {
    /** distanceAlongRouteKm에는 경로 출발점 기준 거리(routeKm)를 담는다. 현재 위치 기준 변환은 FakeChargerData가 한다. */
    fun toDomain() = ChargingStation(
        id = id,
        name = name,
        location = GeoPoint(lat, lon),
        kind = StationKind.valueOf(kind),
        distanceAlongRouteKm = routeKm,
        detourKm = detourKm,
        maxKw = maxKw,
        totalChargers = totalChargers,
        availableChargers = availableChargers,
        expectedWaitMin = expectedWaitMin,
        rule = ChargerRule(timeLimitMin = timeLimitMin, amountLimitKrw = amountLimitKrw),
        waitByHour = waitByHour?.takeIf { it.size == 24 },
        openHours = OpenHours.parse(useTime),
        parkingFree = parkingFree
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
    val outsideTempC: Double? = null,
    val consumptionFactor: Double = 1.0,
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
    val expectedWaitMin: Int? = null,
    val waitByHour: List<Int>? = null
)
