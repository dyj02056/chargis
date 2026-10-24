package com.chargis.app.domain.session

/**
 * plan.md §8.3 알림 이벤트.
 * RULE_NOTICE = 충전 시작 시 "이 충전기는 40분 제한" 안내
 * LIMIT_T10 / LIMIT_T5 = 시간 제한 10분·5분 전 경고
 * TARGET_T5 = 목표 도달 5분 전 ("곧 출발 준비하세요")
 * TARGET_REACHED = 목표 % 도달
 * LIMIT_SHORTFALL = 목표에 못 미친 채 시간 제한으로 충전이 끊김
 */
enum class AlertType { RULE_NOTICE, LIMIT_T10, LIMIT_T5, TARGET_T5, TARGET_REACHED, LIMIT_SHORTFALL }

/**
 * 충전 세션 알림 스케줄러. 순수 Kotlin.
 * - 같은 이벤트는 세션당 한 번만 낸다(idempotent).
 * - 데모 가속으로 한 번에 여러 임계를 넘어도, 원래 시간 순서대로 낸다.
 * - 제한 10분·5분 전 알림은 제한 전에 목표에 닿지 못할 때만 낸다(곧 끝날 충전에 종료 경고를 하지 않도록).
 *
 * 충전 중에 "지금 어떤 음성 알림을 내야 하나?"를 판단하는 알람 시계 역할이다.
 * 충전 한 번마다 새로 만들고, 충전 상황이 갱신될 때마다 [evaluate]를 불러 낼 알림 목록을 받는다.
 */
class AlertScheduler(private val timeLimitMin: Int?) {
    // 이미 낸 알림을 기억해서 같은 말을 두 번 하지 않게 한다.
    private val fired = mutableSetOf<AlertType>()

    val firedAlerts: Set<AlertType> get() = fired

    /** 충전 시작 시: 제한이 있으면 운영 규칙 안내. */
    fun start(): List<AlertType> = if (timeLimitMin != null) fire(listOf(AlertType.RULE_NOTICE)) else emptyList()

    /** 현재 충전 상황 [s]를 보고, 지금 새로 내야 할 알림을 시간 순서대로 돌려준다. */
    fun evaluate(s: ChargingSnapshot): List<AlertType> {
        // (이벤트, 원래 일어났어야 할 경과 분) — 시간순 정렬용
        val due = mutableListOf<Pair<AlertType, Double>>()
        val limit = timeLimitMin?.toDouble()
        // 시간 제한 경고: 아직 목표 전이고, 제한 안에 목표를 못 채울 것 같을 때만
        if (!s.reached && limit != null && s.minutesToLimit != null && s.willHitLimit) {
            if (s.minutesToLimit <= 10.0) due += AlertType.LIMIT_T10 to limit - 10.0
            if (s.minutesToLimit <= 5.0) due += AlertType.LIMIT_T5 to limit - 5.0
        }
        // 목표 5분 전 안내: 제한에 걸리지 않고 정상적으로 목표에 닿는 경우에만
        if (!s.reached && !s.willHitLimit && s.minutesToTarget <= 5.0) {
            due += AlertType.TARGET_T5 to s.elapsedMin + s.minutesToTarget - 5.0
        }
        // 충전이 끝난 이유: 목표 도달이면 완료 안내, 아니면 시간 제한으로 끊겼다는 안내
        if (s.reached) due += AlertType.TARGET_REACHED to Double.MAX_VALUE
        else if (s.limitExpired && limit != null) due += AlertType.LIMIT_SHORTFALL to limit

        return fire(due.sortedBy { it.second }.map { it.first })
    }

    // 아직 내지 않은 알림만 남긴다. (Set.add는 처음 넣을 때만 true를 돌려준다.)
    private fun fire(candidates: List<AlertType>): List<AlertType> =
        candidates.filter { fired.add(it) }
}
