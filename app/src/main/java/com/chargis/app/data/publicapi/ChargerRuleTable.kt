package com.chargis.app.data.publicapi

import com.chargis.app.domain.model.StationKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// 운영기관·시설 종류별 시간 제한·요금 규칙표(PublicChargerData.kt에서 분리).

/**
 * 운영기관·시설 종류별 충전 시간 제한·요금 규칙표(assets/charger_rules.json).
 * 공공데이터에는 시간 제한과 요금 항목이 없어서, 확인한 규칙을 이 표에 출처와 함께 적어 둔다.
 * 위에서부터 그 값(시간 제한 또는 요금)이 있는 규칙 중 처음 맞는 것을 쓴다. operatorId·kind가 비어 있으면 모든 값에 맞는다.
 * 표에도 없으면 시간 제한·요금은 "정보 없음"이 된다(제한 없음, 무료가 아니다).
 */
class ChargerRuleTable(private val rules: List<Rule>) {
    @Serializable
    data class Rule(
        val operatorId: String? = null,
        val kind: StationKind? = null,
        val timeLimitMin: Int? = null,
        val pricePerKwh: Double? = null, // 급속 충전 요금(원/kWh, 회원가 기준으로 적는다)
        val source: String            // 이 규칙을 어디서 확인했는지(문서·안내문). 비워 두지 않는다
    )

    fun timeLimitFor(operatorId: String?, kind: StationKind): Int? =
        rules.firstOrNull { it.timeLimitMin != null && matches(it, operatorId, kind) }?.timeLimitMin

    fun priceFor(operatorId: String?, kind: StationKind): Double? =
        rules.firstOrNull { it.pricePerKwh != null && matches(it, operatorId, kind) }?.pricePerKwh

    private fun matches(rule: Rule, operatorId: String?, kind: StationKind) =
        (rule.operatorId == null || rule.operatorId == operatorId) && (rule.kind == null || rule.kind == kind)

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
