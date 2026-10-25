package com.chargis.app.data.publicapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// 공공 충전소 API 응답(JSON) 파싱(PublicChargerData.kt에서 분리).

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
    val busiId: String? = null,   // 운영기관 아이디. 시간 제한 규칙표(charger_rules.json)를 찾을 때 쓴다
    val lastTedt: String? = null  // 마지막 충전 종료 시각 "yyyyMMddHHmmss". 오래됐으면 고장일 수 있다
)

/** 충전기 상태 한 줄(getChargerStatus). */
data class ChargerStatus(val statId: String, val chgerId: String, val stat: Int?, val nowTsdt: String?, val lastTedt: String? = null)

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
            busiId = o.str("busiId"),
            lastTedt = o.str("lastTedt")?.takeIf { it.isNotBlank() }
        )
    }

    fun parseStatus(body: String): List<ChargerStatus> = items(body).mapNotNull { o ->
        val statId = o.str("statId") ?: return@mapNotNull null
        ChargerStatus(
            statId, o.str("chgerId") ?: "", o.num("stat")?.toInt(),
            o.str("nowTsdt")?.takeIf { it.isNotBlank() }, o.str("lastTedt")?.takeIf { it.isNotBlank() }
        )
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
