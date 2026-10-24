package com.chargis.app.domain.model

/**
 * 운전자가 고르는 설정(F5). 앱을 껐다 켜도 남도록 SettingsStore에 저장한다.
 *
 * [profileId]: 차종. null이면 기본 차종
 * [maxTargetSocPercent]: 한 번에 채우라고 할 최대 %(기본 80). 높이면 덜 들르는 대신 오래 충전한다
 * [safetyMarginPercent]: 필요한 에너지에 얹는 여유(%). 높일수록 넉넉하게 계산한다
 * [voiceCommands]: "수락", "다음" 같은 말로 답하기
 * [onboarded]: 첫 실행 설정을 마쳤는지
 */
data class UserSettings(
    val profileId: String? = null,
    val maxTargetSocPercent: Double = 80.0,
    val safetyMarginPercent: Int = 15,
    val voiceCommands: Boolean = false,
    val onboarded: Boolean = false
) {
    /** 기본 추천 기준값에 이 설정을 덮어쓴다. */
    fun applyTo(base: RecommendationConfig): RecommendationConfig = base.copy(
        maxTargetSocPercent = maxTargetSocPercent,
        safetyMargin = safetyMarginPercent / 100.0
    )

    companion object {
        /** 설정 화면에서 고를 수 있는 값들 */
        val TARGET_CHOICES = listOf(70.0, 80.0, 90.0)
        val MARGIN_CHOICES = listOf(10, 15, 20)
    }
}
