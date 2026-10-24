package com.chargis.app.data.publicapi

import com.chargis.app.domain.model.ChargerRule
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.StationLiveStatus
import com.chargis.app.port.ChargerDataPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/*
 * B5: 공공 충전소 데이터(한국환경공단 "전기자동차 충전소 정보" API, data.go.kr) 어댑터.
 *
 * 흐름: 시도별 충전기 목록(getChargerInfo)과 상태(getChargerStatus)를 받아
 *   → 충전소(statId)별로 묶고 → 휴게소 급속 충전소만 남기고
 *   → 지금 경로 위로 투영해 "경로상 몇 km 앞, 몇 km 우회"로 바꾼다.
 * 목록은 자주 바뀌지 않아 오래(기본 12시간), 상태는 짧게(기본 2분) 캐시한다.
 *
 * 주의: 응답 필드 이름은 공개 문서 기준으로 맞췄고 실제 키로 호출해 확인하지 못했다(docs/DECISIONS.md).
 * 그래서 파서는 숫자가 글자로 오거나 항목이 빠져도 넘어가도록 너그럽게 만들었다.
 */

/** 충전기 한 대(API의 item 한 줄). */
data class ChargerItem(
    val statId: String,
    val chgerId: String,
    val statNm: String,
    val lat: Double,
    val lng: Double,
    val chgerType: String?,
    val outputKw: Double?,
    val stat: Int?,               // 1 통신이상, 2 충전대기, 3 충전중, 4 운영중지, 5 점검중, 9 상태미확인
    val nowTsdt: String?,         // 지금 충전을 시작한 시각(충전 중일 때) "yyyyMMddHHmmss"
    val limitYn: String?,
    val limitDetail: String?
)

/** 충전기 상태 한 줄(getChargerStatus). */
data class ChargerStatus(val statId: String, val chgerId: String, val stat: Int?, val nowTsdt: String?)

/** API 응답(JSON)을 [ChargerItem] 목록으로 바꾼다. 순수 함수라 테스트에서 저장해 둔 응답으로 확인한다. */
object EvChargerParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseInfo(body: String): List<ChargerItem> = items(body).mapNotNull { o ->
        val statId = o.str("statId") ?: return@mapNotNull null
        val lat = o.num("lat") ?: return@mapNotNull null
        val lng = o.num("lng") ?: return@mapNotNull null
        // 삭제된 충전기는 뺀다.
        if (o.str("delYn") == "Y") return@mapNotNull null
        ChargerItem(
            statId = statId,
            chgerId = o.str("chgerId") ?: "",
            statNm = o.str("statNm") ?: statId,
            lat = lat,
            lng = lng,
            chgerType = o.str("chgerType"),
            outputKw = o.num("output"),
            stat = o.num("stat")?.toInt(),
            nowTsdt = o.str("nowTsdt")?.takeIf { it.isNotBlank() },
            limitYn = o.str("limitYn"),
            limitDetail = o.str("limitDetail")
        )
    }

    fun parseStatus(body: String): List<ChargerStatus> = items(body).mapNotNull { o ->
        val statId = o.str("statId") ?: return@mapNotNull null
        ChargerStatus(statId, o.str("chgerId") ?: "", o.num("stat")?.toInt(), o.str("nowTsdt")?.takeIf { it.isNotBlank() })
    }

    /** 결과 코드가 정상("00")이 아니면 오류로 본다. */
    fun checkResult(body: String) {
        val root = json.parseToJsonElement(body) as? JsonObject ?: error("응답이 JSON 객체가 아님")
        val code = root.str("resultCode")
        if (code != null && code != "00") error("API 오류 $code: ${root.str("resultMsg")}")
    }

    // {"items":{"item":[...]}} 또는 {"items":[...]} 둘 다 받아 준다.
    private fun items(body: String): List<JsonObject> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val items = root["items"] ?: return emptyList()
        val list: JsonElement? = (items as? JsonObject)?.get("item") ?: items
        return when (list) {
            is JsonArray -> list.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(list)
            else -> emptyList()
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
    private fun JsonObject.num(key: String): Double? = str(key)?.toDoubleOrNull()
}

/** 휴게소 충전소 하나(충전기 여러 대를 묶은 것). */
data class PublicStation(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val maxKw: Double,
    val totalChargers: Int,
    val availableChargers: Int?,
    val expectedWaitMin: Int?,
    val timeLimitMin: Int?,
    val note: String?
)

/** 충전기들을 충전소 단위로 묶는다. 휴게소 급속 충전기만 남긴다. */
object StationAggregator {

    fun aggregate(items: List<ChargerItem>, statuses: List<ChargerStatus>, now: LocalDateTime): List<PublicStation> {
        // 상태 API 값이 있으면 목록 API의 상태보다 최신이므로 덮어쓴다.
        val latest = statuses.associateBy { it.statId to it.chgerId }
        return items
            .filter { isRestStop(it.statNm) && isFast(it) }
            .map { item -> latest[item.statId to item.chgerId]?.let { s -> item.copy(stat = s.stat, nowTsdt = s.nowTsdt) } ?: item }
            .groupBy { it.statId }
            .map { (id, chargers) ->
                val known = chargers.filter { it.stat != null && it.stat != STAT_UNKNOWN && it.stat != STAT_COMM_ERROR }
                val available = known.count { it.stat == STAT_READY }
                val usable = chargers.count { it.stat != STAT_STOPPED && it.stat != STAT_MAINTENANCE }
                PublicStation(
                    id = id,
                    name = chargers.first().statNm,
                    location = GeoPoint(chargers.first().lat, chargers.first().lng),
                    maxKw = chargers.maxOf { it.outputKw ?: DEFAULT_FAST_KW },
                    totalChargers = usable,
                    availableChargers = if (known.isEmpty()) null else available,
                    expectedWaitMin = if (known.isEmpty()) null else estimateWait(known, available, now),
                    timeLimitMin = chargers.firstNotNullOfOrNull { parseLimit(it.limitDetail) },
                    note = chargers.firstNotNullOfOrNull { it.limitDetail?.takeIf { d -> d.isNotBlank() } }
                )
            }
    }

    /**
     * 빈 충전기가 있으면 대기 0분. 모두 사용 중이면, 가장 먼저 시작한 충전이 보통 세션 길이([TYPICAL_SESSION_MIN])를
     * 채우고 끝난다고 보고 남은 시간을 대기로 잡는다(최소 5분). 대략적인 추정이다.
     */
    fun estimateWait(chargers: List<ChargerItem>, available: Int, now: LocalDateTime): Int {
        if (available > 0) return 0
        val elapsed = chargers.filter { it.stat == STAT_CHARGING }.mapNotNull { c ->
            c.nowTsdt?.let { runCatching { LocalDateTime.parse(it, TS) }.getOrNull() }
                ?.let { java.time.Duration.between(it, now).toMinutes().toInt() }
        }
        val soonest = elapsed.maxOrNull() ?: return TYPICAL_SESSION_MIN / 2
        return max(MIN_WAIT, TYPICAL_SESSION_MIN - soonest)
    }

    /** "1회 40분 이내", "최대 40분" 같은 문구에서 분을 꺼낸다. */
    fun parseLimit(detail: String?): Int? =
        detail?.let { Regex("""(\d{1,3})\s*분""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun isRestStop(name: String) = name.contains("휴게소")

    // 완속(AC 완속 "02")은 고속도로 충전에 맞지 않아 뺀다. 출력이 알려져 있으면 40kW 이상만 남긴다.
    private fun isFast(c: ChargerItem) = c.chgerType != TYPE_AC_SLOW && (c.outputKw == null || c.outputKw >= MIN_FAST_KW)

    private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private const val STAT_COMM_ERROR = 1
    private const val STAT_READY = 2
    private const val STAT_CHARGING = 3
    private const val STAT_STOPPED = 4
    private const val STAT_MAINTENANCE = 5
    private const val STAT_UNKNOWN = 9
    private const val TYPE_AC_SLOW = "02"
    private const val MIN_FAST_KW = 40.0
    private const val DEFAULT_FAST_KW = 50.0
    private const val TYPICAL_SESSION_MIN = 35
    private const val MIN_WAIT = 5
}

/**
 * 지도 좌표를 경로 위 거리로 바꾼다. 경로 폴리라인이 없으므로(내비 SDK 확인 전) 지금 위치→목적지 직선을 경로로 본다.
 * 직선 위로 내린 위치를 실제 남은 거리 비율로 늘려 "경로상 거리"로, 직선에서 떨어진 거리를 "우회 거리"로 쓴다.
 */
object RouteProjector {

    /** (경로상 거리 km, 우회 km). 경로 앞쪽이 아니거나 [corridorKm]보다 멀면 null. */
    fun project(route: RouteState, point: GeoPoint, corridorKm: Double): Pair<Double, Double>? {
        val dest = route.destination ?: return null
        val (dx, dy) = toKm(route.current, dest)
        val (px, py) = toKm(route.current, point)
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-6) return null
        val along = (px * dx + py * dy) / len
        val lateral = kotlin.math.abs(px * dy - py * dx) / len
        if (along <= 0 || along >= len || lateral > corridorKm) return null
        // 직선 거리를 실제 도로 거리로 늘리는 비율(도로는 직선보다 길다)
        val roadFactor = (route.remainingDistanceKm / len).coerceAtLeast(1.0)
        return along * roadFactor to lateral * DETOUR_FACTOR
    }

    // 작은 범위에서는 위도·경도 차이를 km로 바로 바꿔도 충분히 정확하다(등장방형 근사).
    private fun toKm(origin: GeoPoint, p: GeoPoint): Pair<Double, Double> {
        val x = (p.lon - origin.lon) * KM_PER_DEG_LON * cos(Math.toRadians(origin.lat))
        val y = (p.lat - origin.lat) * KM_PER_DEG_LAT
        return x to y
    }

    private const val KM_PER_DEG_LAT = 110.57
    private const val KM_PER_DEG_LON = 111.32
    private const val DETOUR_FACTOR = 1.3
}

/** HTTP 호출만 맡는 얇은 층. 테스트에서는 가짜로 바꿔 끼운다. */
fun interface EvChargerHttp {
    suspend fun get(operation: String, params: Map<String, String>): String
}

/** data.go.kr 호출 구현. [serviceKey]는 local.properties의 chargis.chargerApiKey에서 온다. */
class UrlEvChargerHttp(private val serviceKey: String, private val timeoutMs: Int = 8_000) : EvChargerHttp {
    override suspend fun get(operation: String, params: Map<String, String>): String = withContext(Dispatchers.IO) {
        val query = (params + mapOf("serviceKey" to serviceKey, "dataType" to "JSON"))
            .entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8.name())}" }
        val conn = URL("$BASE/$operation?$query").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val BASE = "https://apis.data.go.kr/B552584/EvCharger"
    }
}

/**
 * 공공데이터 기반 [ChargerDataPort]. [zcodes]는 경로가 지나는 시도 코드(예: 11 서울, 41 경기, 51 강원).
 * [nowMillis]는 캐시 만료 판단용 시계(테스트에서 바꿔 끼운다).
 */
class PublicChargerData(
    private val http: EvChargerHttp,
    private val zcodes: List<String>,
    private val corridorKm: Double = 3.0,
    private val infoTtlMs: Long = 12 * 60 * 60 * 1000L,
    private val statusTtlMs: Long = 2 * 60 * 1000L,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val nowLocal: () -> LocalDateTime = LocalDateTime::now
) : ChargerDataPort {
    private val mutex = Mutex()
    private var info: Cached<List<ChargerItem>>? = null
    private var status: Cached<List<ChargerStatus>>? = null
    private var lastStations: List<PublicStation> = emptyList()

    private class Cached<T>(val value: T, val at: Long)

    override suspend fun stationsAlongRoute(route: RouteState): List<ChargingStation> {
        val stations = load()
        return stations.mapNotNull { st ->
            val (along, detour) = RouteProjector.project(route, st.location, corridorKm) ?: return@mapNotNull null
            ChargingStation(
                id = st.id,
                name = st.name,
                location = st.location,
                isRestStop = true,
                distanceAlongRouteKm = along,
                detourKm = detour,
                maxKw = st.maxKw,
                totalChargers = st.totalChargers,
                availableChargers = st.availableChargers,
                expectedWaitMin = st.expectedWaitMin,
                rule = ChargerRule(timeLimitMin = st.timeLimitMin, amountLimitKrw = null, note = st.note)
            )
        }.sortedBy { it.distanceAlongRouteKm }
    }

    override fun liveStatus(stationId: String): Flow<StationLiveStatus?> = flow {
        val st = load().firstOrNull { it.id == stationId }
        emit(st?.let { StationLiveStatus(it.id, it.availableChargers, it.expectedWaitMin) })
    }

    // 캐시가 살아 있으면 그대로, 만료됐으면 다시 받는다. 상태만 실패하면 목록 API의 상태로 버틴다.
    private suspend fun load(): List<PublicStation> = mutex.withLock {
        val now = nowMillis()
        val infoFresh = info?.takeIf { now - it.at < infoTtlMs }
        val statusFresh = status?.takeIf { now - it.at < statusTtlMs }
        if (infoFresh != null && statusFresh != null) return lastStations
        val items = infoFresh?.value ?: fetchAll("getChargerInfo", EvChargerParser::parseInfo).also { info = Cached(it, now) }
        val statuses = statusFresh?.value
            ?: runCatching { fetchAll("getChargerStatus", EvChargerParser::parseStatus, mapOf("period" to "10")) }
                .getOrDefault(emptyList()).also { status = Cached(it, now) }
        lastStations = StationAggregator.aggregate(items, statuses, nowLocal())
        lastStations
    }

    private suspend fun <T> fetchAll(op: String, parse: (String) -> List<T>, extra: Map<String, String> = emptyMap()): List<T> =
        zcodes.flatMap { z ->
            val body = http.get(op, extra + mapOf("pageNo" to "1", "numOfRows" to "9999", "zcode" to z))
            EvChargerParser.checkResult(body)
            parse(body)
        }
}

/**
 * 1순위 공급원이 실패하거나 빈 목록을 주면 예비 공급원으로 넘어간다.
 * 예) 공공데이터 → (네트워크 오류) → 번들 샘플 데이터
 */
class FallbackChargerData(
    private val primary: ChargerDataPort,
    private val fallback: ChargerDataPort,
    private val onFallback: (Throwable?) -> Unit = {}
) : ChargerDataPort {
    override suspend fun stationsAlongRoute(route: RouteState): List<ChargingStation> {
        val result = runCatching { primary.stationsAlongRoute(route) }
        val list = result.getOrNull()
        if (list != null && list.isNotEmpty()) return list
        onFallback(result.exceptionOrNull())
        return fallback.stationsAlongRoute(route)
    }

    override fun liveStatus(stationId: String): Flow<StationLiveStatus?> = flow {
        val live = runCatching { var v: StationLiveStatus? = null; primary.liveStatus(stationId).collect { v = it }; v }.getOrNull()
        if (live != null) emit(live) else fallback.liveStatus(stationId).collect { emit(it) }
    }
}
