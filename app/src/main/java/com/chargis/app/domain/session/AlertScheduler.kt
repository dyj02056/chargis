package com.chargis.app.domain.session

/** plan.md §8.3 알림 이벤트. */
enum class AlertType { RULE_NOTICE, LIMIT_T10, LIMIT_T5, TARGET_T5, TARGET_REACHED, LIMIT_SHORTFALL }

/**
 * 충전 세션 알림 스케줄러. 순수 Kotlin.
 * - 같은 이벤트는 세션당 한 번만 낸다(idempotent).
 * - 데모 가속으로 한 번에 여러 임계를 넘어도, 원래 시간 순서대로 낸다.
 * - 제한 10분·5분 전 알림은 제한 전에 목표에 닿지 못할 때만 낸다(곧 끝날 충전에 종료 경고를 하지 않도록).
 */
class AlertScheduler(private val timeLimitMin: Int?) {
    private val fired = mutableSetOf<AlertType>()

    val firedAlerts: Set<AlertType> get() = fired

    /** 충전 시작 시: 제한이 있으면 운영 규칙 안내. */
    fun start(): List<AlertType> = if (timeLimitMin != null) fire(listOf(AlertType.RULE_NOTICE)) else emptyList()

    fun evaluate(s: ChargingSnapshot): List<AlertType> {
        // (이벤트, 원래 일어났어야 할 경과 분) — 시간순 정렬용
        val due = mutableListOf<Pair<AlertType, Double>>()
        val limit = timeLimitMin?.toDouble()
        if (!s.reached && limit != null && s.minutesToLimit != null && s.willHitLimit) {
            if (s.minutesToLimit <= 10.0) due += AlertType.LIMIT_T10 to limit - 10.0
            if (s.minutesToLimit <= 5.0) due += AlertType.LIMIT_T5 to limit - 5.0
        }
        if (!s.reached && !s.willHitLimit && s.minutesToTarget <= 5.0) {
            due += AlertType.TARGET_T5 to s.elapsedMin + s.minutesToTarget - 5.0
        }
        if (s.reached) due += AlertType.TARGET_REACHED to Double.MAX_VALUE
        else if (s.limitExpired && limit != null) due += AlertType.LIMIT_SHORTFALL to limit

        return fire(due.sortedBy { it.second }.map { it.first })
    }

    private fun fire(candidates: List<AlertType>): List<AlertType> =
        candidates.filter { fired.add(it) }
}
