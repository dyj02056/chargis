package com.chargis.app.data.publicapi

import com.chargis.app.domain.model.ConnectorType
import com.chargis.app.domain.model.GeoPoint
import com.chargis.app.domain.model.OpenHours
import com.chargis.app.domain.model.StationKind
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max

// 충전기 → 충전소 묶기와 대기·고장 추정(PublicChargerData.kt에서 분리).

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
    val operatorId: String? = null,
    val connectors: Set<ConnectorType>? = null,
    val brokenChargers: Int? = null,
    val hoursSinceLastCharge: Double? = null
)

/** 충전기들을 충전소 단위로 묶는다. 누구나 쓸 수 있는 급속 충전기만 남긴다. */
object StationAggregator {

    /**
     * [connectors]를 주면 그 충전구를 쓸 수 있는 충전기만 센다(타입을 모르는 충전기는 남긴다).
     * 예) DC콤보 차에게 차데모 전용 충전기는 빈 대수에도, 출력에도 넣지 않는다.
     */
    fun aggregate(
        items: List<ChargerItem>,
        statuses: List<ChargerStatus>,
        now: LocalDateTime,
        connectors: Set<ConnectorType>? = null
    ): List<PublicStation> {
        // 상태 API 값이 있으면 목록 API의 상태보다 최신이므로 덮어쓴다.
        val latest = statuses.associateBy { it.statId to it.chgerId }
        return items
            .filter { isFast(it) && StationKinds.isOpenFacility(it) && !isUserRestricted(it) && fits(it, connectors) }
            .map { item ->
                latest[item.statId to item.chgerId]?.let { s -> item.copy(stat = s.stat, nowTsdt = s.nowTsdt, lastTedt = s.lastTedt ?: item.lastTedt) } ?: item
            }
            .groupBy { it.statId }
            .map { (id, chargers) ->
                val known = chargers.filter { it.stat != null && it.stat != STAT_UNKNOWN && it.stat != STAT_COMM_ERROR }
                val available = known.count { it.stat == STAT_READY }
                val broken = chargers.count { it.stat == STAT_STOPPED || it.stat == STAT_MAINTENANCE }
                val usable = chargers.size - broken
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
                    operatorId = first.busiId,
                    connectors = chargers.mapNotNull { ConnectorCodes.of(it.chgerType) }.takeIf { it.isNotEmpty() }?.flatten()?.toSet(),
                    brokenChargers = broken,
                    hoursSinceLastCharge = hoursSinceLastCharge(chargers, now)
                )
            }
    }

    /**
     * 마지막으로 충전이 끝난 뒤 몇 시간 지났는지(충전소 안 충전기 중 가장 최근). 지금 충전 중인 충전기가 있으면 0.
     * 시각을 하나도 모르면 null. 오랫동안 아무도 충전하지 못했다면 고장이 의심된다(엔진이 순위를 낮춘다).
     */
    fun hoursSinceLastCharge(chargers: List<ChargerItem>, now: LocalDateTime): Double? {
        if (chargers.any { it.stat == STAT_CHARGING }) return 0.0
        val ends = chargers.mapNotNull { c -> c.lastTedt?.let { runCatching { LocalDateTime.parse(it, TS) }.getOrNull() } }
        val latest = ends.maxOrNull() ?: return null
        return java.time.Duration.between(latest, now).toMinutes().coerceAtLeast(0) / 60.0
    }

    // 차의 충전구와 맞는지. 차 또는 충전기 타입을 모르면 맞는다고 본다.
    private fun fits(c: ChargerItem, connectors: Set<ConnectorType>?): Boolean {
        val mine = connectors ?: return true
        val theirs = ConnectorCodes.of(c.chgerType) ?: return true
        return mine.any { it in theirs }
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
