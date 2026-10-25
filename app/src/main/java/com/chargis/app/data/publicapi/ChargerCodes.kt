package com.chargis.app.data.publicapi

import com.chargis.app.domain.model.ConnectorType
import com.chargis.app.domain.model.StationKind

// 공공데이터 시설 구분·충전기 타입 코드표(PublicChargerData.kt에서 분리). 실제 응답으로 확인이 필요하다.

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

/** 충전기 타입 코드(chgerType) → 충전구 종류. 08~10은 새로 생긴 코드라 실제 응답으로 확인이 필요하다(docs/OPEN_ISSUES.md). */
object ConnectorCodes {
    fun of(chgerType: String?): Set<ConnectorType>? = when (chgerType?.trim()) {
        "01" -> setOf(ConnectorType.CHADEMO)
        "03" -> setOf(ConnectorType.CHADEMO, ConnectorType.AC3)
        "04", "08" -> setOf(ConnectorType.DC_COMBO)
        "05" -> setOf(ConnectorType.CHADEMO, ConnectorType.DC_COMBO)
        "06" -> setOf(ConnectorType.CHADEMO, ConnectorType.AC3, ConnectorType.DC_COMBO)
        "07" -> setOf(ConnectorType.AC3)
        "09" -> setOf(ConnectorType.NACS)
        "10" -> setOf(ConnectorType.DC_COMBO, ConnectorType.NACS)
        else -> null   // "02"(AC 완속)은 따로 빼고, 모르는 코드는 미상
    }
}
