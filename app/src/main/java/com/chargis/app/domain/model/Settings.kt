package com.chargis.app.domain.model

/**
 * 운전자가 고르는 설정(F5). 앱을 껐다 켜도 남도록 SettingsStore에 저장한다.
 *
 * [profileId]: 차종. null이면 기본 차종
 * [maxTargetSocPercent]: 한 번에 채우라고 할 최대 %(기본 80). 높이면 덜 들르는 대신 오래 충전한다
 * [safetyMarginPercent]: 필요한 에너지에 얹는 여유(%). 높일수록 넉넉하게 계산한다
 * [voiceCommands]: "수락", "다음" 같은 말로 답하기
 * [onboarded]: 첫 실행 설정을 마쳤는지
 * [destinationSocPercent]: 목적지에 도착할 때 남길 배터리(%). 목적지에 충전기가 없으면 높인다
 * [manualSoc]: 배터리 %를 차량에서 받지 않고 운전자가 직접 넣는다(차량 SDK로 받을 수 없을 때의 대안)
 */
data class UserSettings(
    val profileId: String? = null,
    val maxTargetSocPercent: Double = 80.0,
    val safetyMarginPercent: Int = 15,
    val voiceCommands: Boolean = false,
    val onboarded: Boolean = false,
    val includeNonRoadside: Boolean = true,  // 휴게소·쉼터 밖 충전소(국도변·상업·공공시설)도 후보로 볼지
    val destinationSocPercent: Int = 10,
    val manualSoc: Boolean = false
) {
    /** 기본 추천 기준값에 이 설정을 덮어쓴다. */
    fun applyTo(base: RecommendationConfig): RecommendationConfig = base.copy(
        maxTargetSocPercent = maxTargetSocPercent,
        safetyMargin = safetyMarginPercent / 100.0,
        includeNonRoadside = includeNonRoadside,
        destinationSocPercent = destinationSocPercent.toDouble()
    )

    companion object {
        /** 설정 화면에서 고를 수 있는 값들 */
        val TARGET_CHOICES = listOf(70.0, 80.0, 90.0)
        val MARGIN_CHOICES = listOf(10, 15, 20)
        val DESTINATION_CHOICES = listOf(10, 20, 30)
    }
}
