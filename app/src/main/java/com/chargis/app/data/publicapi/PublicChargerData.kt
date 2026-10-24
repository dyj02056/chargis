package com.chargis.app.data.publicapi

import com.chargis.app.domain.model.ChargerRule
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.OpenHours
import com.chargis.app.domain.model.RouteState
import com.chargis.app.domain.model.StationKind
import com.chargis.app.domain.model.StationLiveStatus
import com.chargis.app.port.ChargerDataPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
 *   → 충전소(statId)별로 묶고 → 누구나 쓸 수 있는 급속 충전소만 남기고
 *   → 지금 경로 위로 투영해 "경로상 몇 km 앞, 편도 몇 km 우회"로 바꾼다.
 * 목록은 자주 바뀌지 않아 오래(기본 12시간), 상태는 짧게(기본 2분) 캐시한다.
 *
 * 후보로 남기는 충전소: 고속도로·국도 휴게소, 졸음쉼터, 상업시설(마트·주유소 등), 공공시설, 관광지 등.
 * 빼는 충전소: 완속, 아파트·사업장·학교처럼 외부인이 쓰기 어려운 시설, "입주민 전용" 같은 이용자 제한이 걸린 곳.
 *
 * limitYn/limitDetail은 충전 시간 제한이 아니라 "이용자 제한 여부/사유"다(data.go.kr 서비스 설명의
 * "이용자 제한 여부" 항목). 그래서 이용자 제한 판단에 쓰고, 시간 제한은 "1회 40분 이내"처럼 분 단위 제한이
 * 분명히 적힌 문구가 있을 때만 꺼낸다. 없으면 시간 제한 "정보 없음"으로 둔다.
 *
 * 주의: 응답 필드 이름과 시설 구분 코드는 공개 문서와 공개 구현 사례 기준으로 맞췄고 실제 키로 호출해
 * 확인하지 못했다(docs/DECISIONS.md). 그래서 파서는 숫자가 글자로 오거나 항목이 빠져도 넘어가도록 너그럽게 만들었다.
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
    val limitYn: String?,         // 이용자 제한 여부 "Y"/"N"
    val limitDetail: String?,     // 이용자 제한 사유. 예) "입주민 외 이용불가"
    val kind: String? = null,     // 시설 구분(대) 예) "C0" 휴게시설
    val kindDetail: String? = null, // 시설 구분(소) 예) "C001" 고속도로 휴게소, "C003" 쉼터
    val useTime: String? = null,  // 이용 가능 시간. 예) "24시간 이용가능", "09:00~22:00"
    val parkingFree: String? = null, // 주차료 무료 여부 "Y"/"N"
    val note: String? = null,     // 충전소 안내
    val busiId: String? = null    // 운영기관 아이디. 시간 제한 규칙표(charger_rules.json)를 찾을 때 쓴다
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
            limitDetail = o.str("limitDetail"),
            kind = o.str("kind"),
            kindDetail = o.str("kindDetail"),
            useTime = o.str("useTime"),
            parkingFree = o.str("parkingFree"),
            note = o.str("note"),
            busiId = o.str("busiId")
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

/** 공용 급속 충전소 하나(충전기 여러 대를 묶은 것). */
data class PublicStation(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val kind: StationKind,
    val maxKw: Double,
    val totalChargers: Int,
    val availableChargers: Int?,
    val expectedWaitMin: Int?,
    val timeLimitMin: Int?,
    val note: String?,
    val openHours: OpenHours? = null,
    val parkingFree: Boolean? = null,
    val operatorId: String? = null
)

/**
 * 운영기관·시설 종류별 충전 시간 제한 규칙표(assets/charger_rules.json).
 * 공공데이터에는 시간 제한 항목이 없어서, 확인한 규칙을 이 표에 출처와 함께 적어 둔다.
 * 위에서부터 처음 맞는 규칙을 쓴다. operatorId·kind가 비어 있으면 모든 값에 맞는다.
 * 표에도 없으면 시간 제한은 "정보 없음"이 된다(제한 없음이 아니다).
 */
class ChargerRuleTable(private val rules: List<Rule>) {
    @Serializable
    data class Rule(
        val operatorId: String? = null,
        val kind: StationKind? = null,
        val timeLimitMin: Int,
        val source: String            // 이 규칙을 어디서 확인했는지(문서·안내문). 비워 두지 않는다
    )

    fun timeLimitFor(operatorId: String?, kind: StationKind): Int? =
        rules.firstOrNull { (it.operatorId == null || it.operatorId == operatorId) && (it.kind == null || it.kind == kind) }?.timeLimitMin

    companion object {
        val EMPTY = ChargerRuleTable(emptyList())

        @Serializable
        private data class File(val rules: List<Rule> = emptyList())

        /** 규칙표 JSON을 읽는다. 형식이 틀리면 빈 표로 본다(앱이 멈추지 않게). */
        fun parse(json: String): ChargerRuleTable =
            runCatching { ChargerRuleTable(Json { ignoreUnknownKeys = true }.decodeFromString(File.serializer(), json).rules) }
                .getOrDefault(EMPTY)
    }
}

/**
 * 시설 구분 코드(kind/kindDetail)를 [StationKind]로 바꾸고, 외부인이 쓰기 어려운 시설을 가려낸다.
 * 코드표는 공개 구현 사례에서 옮겼다(실제 응답으로 확인 필요, docs/OPEN_ISSUES.md).
 */
object StationKinds {
    /**
     * 외부인이 자유롭게 쓰기 어려운 시설(시설 구분 소분류).
     * 골프장, 서비스센터·정비소, 군부대, 오피스텔·단독주택, 공동주택(아파트·빌라·기숙사 등), 사업장,
     * 병원·종교시설·경찰서·복지관·수련원·금융기관, 학교·교육원·학원.
     */
    val EXCLUDED_DETAILS = setOf(
        "E004", "F001", "F002", "G001", "G005", "G006",
        "H001", "H002", "H003", "H004", "H005",
        "I001", "I002", "I004", "I006", "I007", "I008",
        "J001", "J002", "J003"
    )

    /** 이 충전기가 있는 시설이 외부인에게 열려 있다고 볼 수 있는지(시설 종류만 본다). */
    fun isOpenFacility(item: ChargerItem): Boolean =
        item.kindDetail !in EXCLUDED_DETAILS && item.kind != "H0"

    /** 시설 구분 코드로 종류를 정한다. 코드가 없으면 이름으로 짐작한다("휴게소", "쉼터"). */
    fun of(item: ChargerItem): StationKind = when (item.kindDetail) {
        "C001" -> StationKind.HIGHWAY_REST_STOP
        "C002" -> StationKind.LOCAL_REST_STOP
        "C003" -> StationKind.SHELTER
        else -> when (item.kind) {
            "A0", "B0" -> StationKind.PUBLIC          // 공공시설, 주차시설(공영주차장 등)
            "E0" -> StationKind.COMMERCIAL            // 상업시설(마트, 주유소, 음식점 등)
            "C0" -> byName(item.statNm) ?: StationKind.HIGHWAY_REST_STOP
            null, "" -> byName(item.statNm) ?: StationKind.OTHER
            else -> StationKind.OTHER                 // 관광, 근린생활, 교육문화, 기타
        }
    }

    private fun byName(name: String): StationKind? = when {
        name.contains("쉼터") -> StationKind.SHELTER
        name.contains("휴게소") -> StationKind.HIGHWAY_REST_STOP
        else -> null
    }
}

/** 충전기들을 충전소 단위로 묶는다. 누구나 쓸 수 있는 급속 충전기만 남긴다. */
object StationAggregator {

    fun aggregate(items: List<ChargerItem>, statuses: List<ChargerStatus>, now: LocalDateTime): List<PublicStation> {
        // 상태 API 값이 있으면 목록 API의 상태보다 최신이므로 덮어쓴다.
        val latest = statuses.associateBy { it.statId to it.chgerId }
        return items
            .filter { isFast(it) && StationKinds.isOpenFacility(it) && !isUserRestricted(it) }
            .map { item -> latest[item.statId to item.chgerId]?.let { s -> item.copy(stat = s.stat, nowTsdt = s.nowTsdt) } ?: item }
            .groupBy { it.statId }
            .map { (id, chargers) ->
                val known = chargers.filter { it.stat != null && it.stat != STAT_UNKNOWN && it.stat != STAT_COMM_ERROR }
                val available = known.count { it.stat == STAT_READY }
                val usable = chargers.count { it.stat != STAT_STOPPED && it.stat != STAT_MAINTENANCE }
                val first = chargers.first()
                PublicStation(
                    id = id,
                    name = first.statNm,
                    location = GeoPoint(first.lat, first.lng),
                    kind = StationKinds.of(first),
                    maxKw = chargers.maxOf { it.outputKw ?: DEFAULT_FAST_KW },
                    totalChargers = usable,
                    availableChargers = if (known.isEmpty()) null else available,
                    expectedWaitMin = if (known.isEmpty()) null else estimateWait(known, available, now),
                    timeLimitMin = chargers.firstNotNullOfOrNull { parseLimit(it.limitDetail) ?: parseLimit(it.note) },
                    note = chargers.firstNotNullOfOrNull { it.limitDetail?.takeIf { d -> d.isNotBlank() } },
                    openHours = chargers.firstNotNullOfOrNull { OpenHours.parse(it.useTime) },
                    parkingFree = first.parkingFree?.let { if (it == "Y") true else if (it == "N") false else null },
                    operatorId = first.busiId
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

    /**
     * "1회 40분 이내", "최대 40분", "40분 제한" 같은 충전 시간 제한 문구에서 분을 꺼낸다.
     * "입주민 외 이용불가"처럼 시간 제한이 아닌 문구나, "10분 거리"처럼 제한이 아닌 숫자는 null.
     */
    fun parseLimit(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val m = LIMIT_AFTER.find(text) ?: LIMIT_BEFORE.find(text) ?: return null
        return m.groupValues[1].toIntOrNull()?.takeIf { it in 10..240 }
    }

    /**
     * 이용자 제한이 걸린 충전기인지. limitYn이 "Y"인데 사유가 비었거나 사유가 시간 제한뿐이 아니면 제한으로 본다.
     * limitYn이 "N"이어도 사유·이용 시간에 "입주민", "외부인" 같은 말이 있으면 제한으로 본다.
     */
    fun isUserRestricted(item: ChargerItem): Boolean {
        val texts = listOfNotNull(item.limitDetail, item.useTime)
        if (texts.any { t -> RESTRICT_WORDS.any { t.contains(it) } }) return true
        if (item.limitYn != "Y") return false
        val detail = item.limitDetail?.trim().orEmpty()
        return detail.isEmpty() || parseLimit(detail) == null
    }

    // 완속(AC 완속 "02")은 이동 중 충전에 맞지 않아 뺀다. 출력이 알려져 있으면 40kW 이상만 남긴다.
    private fun isFast(c: ChargerItem) = c.chgerType != TYPE_AC_SLOW && (c.outputKw == null || c.outputKw >= MIN_FAST_KW)

    private val LIMIT_AFTER = Regex("""(\d{1,3})\s*분\s*(?:이내|제한|까지|초과\s*금지)""")
    private val LIMIT_BEFORE = Regex("""(?:최대|1회)\s*(\d{1,3})\s*분""")
    private val RESTRICT_WORDS = listOf(
        "비개방", "외부인", "외부차량", "입주민", "거주자", "관계자", "직원 전용", "직원전용", "이용불가", "이용 불가"
    )

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
 * 직선 위로 내린 위치를 실제 남은 거리 비율로 늘려 "경로상 거리"로, 직선에서 떨어진 거리를 "편도 우회 거리"로 쓴다.
 * 돌아오는 거리는 추천 엔진이 왕복으로 다시 센다.
 */
object RouteProjector {

    /** (경로상 거리 km, 편도 우회 km). 경로 앞쪽이 아니거나 [corridorKm]보다 멀면 null. */
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

/**
 * 충전소 API 호출 구현.
 * - [baseUrl]: 기본은 data.go.kr. 키를 앱에 넣지 않으려면 키를 붙여 주는 중계 서버(프록시) 주소를 넣는다.
 * - [serviceKey]: data.go.kr 키. 비어 있으면 붙이지 않는다(프록시가 붙인다고 본다).
 * 앱에 넣은 키는 APK에서 꺼낼 수 있으므로, 배포용(release) 빌드는 키를 넣지 않고 프록시를 쓴다(app/build.gradle.kts).
 * 오류 메시지에는 주소를 넣지 않는다(키가 로그에 남지 않게).
 */
class UrlEvChargerHttp(
    private val serviceKey: String?,
    private val baseUrl: String = DEFAULT_BASE,
    private val timeoutMs: Int = 8_000
) : EvChargerHttp {
    override suspend fun get(operation: String, params: Map<String, String>): String = withContext(Dispatchers.IO) {
        val conn = URL(urlFor(operation, params)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} ($operation)")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 호출 주소. 테스트에서 키가 붙는지 확인할 수 있게 따로 뺐다. */
    fun urlFor(operation: String, params: Map<String, String>): String {
        val key = serviceKey?.takeIf { it.isNotBlank() }?.let { mapOf("serviceKey" to it) }.orEmpty()
        val query = (params + key + mapOf("dataType" to "JSON"))
            .entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8.name())}" }
        return "${baseUrl.trimEnd('/')}/$operation?$query"
    }

    companion object {
        const val DEFAULT_BASE = "https://apis.data.go.kr/B552584/EvCharger"
    }
}

/**
 * 공공데이터 기반 [ChargerDataPort].
 * [zcodes]를 주면 그 시도만, 비워 두면(null) 경로가 지나는 시도를 [SidoCodes]로 그때그때 고른다.
 * 목록·상태는 시도마다 따로 캐시해서, 경로가 새 시도로 넘어가면 그 시도만 새로 받는다.
 * [nowMillis]는 캐시 만료 판단용 시계(테스트에서 바꿔 끼운다).
 */
class PublicChargerData(
    private val http: EvChargerHttp,
    private val zcodes: List<String>? = null,
    private val corridorKm: Double = 3.0,
    private val rules: ChargerRuleTable = ChargerRuleTable.EMPTY,
    private val infoTtlMs: Long = 12 * 60 * 60 * 1000L,
    private val statusTtlMs: Long = 2 * 60 * 1000L,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val nowLocal: () -> LocalDateTime = LocalDateTime::now
) : ChargerDataPort {
    private val mutex = Mutex()
    private val info = mutableMapOf<String, Cached<List<ChargerItem>>>()
    private val status = mutableMapOf<String, Cached<List<ChargerStatus>>>()
    private var lastZones: List<String> = emptyList()
    private var lastStations: List<PublicStation> = emptyList()

    private class Cached<T>(val value: T, val at: Long)

    override suspend fun stationsAlongRoute(route: RouteState): List<ChargingStation> {
        val stations = load(zcodes ?: SidoCodes.along(route))
        return stations.mapNotNull { st ->
            // 반대 방향 휴게소("(서울방향)" 등)는 본선에서 들어갈 수 없으므로 뺀다.
            if (st.kind.isRoadside && RestStopDirection.isOpposite(st.name, st.location, route)) return@mapNotNull null
            val (along, detour) = RouteProjector.project(route, st.location, corridorKm) ?: return@mapNotNull null
            ChargingStation(
                id = st.id,
                name = st.name,
                location = st.location,
                kind = st.kind,
                distanceAlongRouteKm = along,
                // 휴게소·쉼터는 본선에 붙어 있어 직선 경로와의 거리가 투영 오차일 뿐이므로 우회 0으로 본다.
                detourKm = if (st.kind == StationKind.HIGHWAY_REST_STOP || st.kind == StationKind.SHELTER) 0.0 else detour,
                maxKw = st.maxKw,
                totalChargers = st.totalChargers,
                availableChargers = st.availableChargers,
                expectedWaitMin = st.expectedWaitMin,
                // 시간 제한: 안내 문구에서 읽은 값 → 규칙표 → 둘 다 없으면 "정보 없음"
                rule = (st.timeLimitMin ?: rules.timeLimitFor(st.operatorId, st.kind)).let { limit ->
                    ChargerRule(timeLimitMin = limit, amountLimitKrw = null, note = st.note, timeLimitKnown = limit != null)
                },
                openHours = st.openHours,
                parkingFree = st.parkingFree
            )
        }.sortedBy { it.distanceAlongRouteKm }
    }

    // 실시간 상태는 마지막으로 경로를 받았던 시도들에서 찾는다.
    override fun liveStatus(stationId: String): Flow<StationLiveStatus?> = flow {
        val st = load(zcodes ?: lastZones).firstOrNull { it.id == stationId }
        emit(st?.let { StationLiveStatus(it.id, it.availableChargers, it.expectedWaitMin) })
    }

    /**
     * [zones] 시도의 충전소. 시도마다 캐시가 살아 있으면 그대로, 만료됐으면 그 시도만 다시 받는다.
     * 상태만 실패하면 목록 API의 상태로 버틴다. 아무것도 새로 받지 않았고 시도도 같으면 지난 결과를 그대로 쓴다.
     */
    private suspend fun load(zones: List<String>): List<PublicStation> = mutex.withLock {
        val now = nowMillis()
        var changed = zones != lastZones
        val items = zones.flatMap { z ->
            info[z]?.takeIf { now - it.at < infoTtlMs }?.value
                ?: fetch(z, "getChargerInfo", EvChargerParser::parseInfo).also { info[z] = Cached(it, now); changed = true }
        }
        val statuses = zones.flatMap { z ->
            status[z]?.takeIf { now - it.at < statusTtlMs }?.value
                ?: runCatching { fetch(z, "getChargerStatus", EvChargerParser::parseStatus, mapOf("period" to "10")) }
                    .getOrDefault(emptyList()).also { status[z] = Cached(it, now); changed = true }
        }
        if (changed) {
            lastZones = zones
            lastStations = StationAggregator.aggregate(items, statuses, nowLocal())
        }
        lastStations
    }

    private suspend fun <T> fetch(zone: String, op: String, parse: (String) -> List<T>, extra: Map<String, String> = emptyMap()): List<T> {
        val body = http.get(op, extra + mapOf("pageNo" to "1", "numOfRows" to "9999", "zcode" to zone))
        EvChargerParser.checkResult(body)
        return parse(body)
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
