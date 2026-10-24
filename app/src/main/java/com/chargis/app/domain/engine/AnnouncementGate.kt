package com.chargis.app.domain.engine

import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.model.RecommendationResult

/**
 * plan.md §7.3 안내 시점과 히스테리시스.
 * - 1순위가 [announceWithinKm] 안에 들어오면 한 번만 안내한다.
 * - 이미 안내/수락/거절한 휴게소는 다시 안내하지 않는다.
 * - 수락한 추천은 점수 차가 [switchScoreGap] 이상 벌어지거나 후보에서 사라질 때만 바꾼다.
 */
class AnnouncementGate(
    private val announceWithinKm: Double,
    private val switchScoreGap: Double = 0.15
) {
    private val handled = mutableSetOf<String>()
    private val declined = mutableSetOf<String>()

    val declinedIds: Set<String> get() = declined

    fun shouldAnnounce(rec: Recommendation): Boolean =
        rec.station.id !in handled && rec.station.distanceAlongRouteKm <= announceWithinKm

    fun markAnnounced(stationId: String) {
        handled += stationId
    }

    fun markDeclined(stationId: String) {
        handled += stationId
        declined += stationId
    }

    /** 수락한 [accepted]를 [latest] 결과로 바꿔야 하면 새 추천을, 유지해야 하면 null을 돌려준다. */
    fun replacementFor(accepted: Recommendation, latest: RecommendationResult): Recommendation? {
        if (latest !is RecommendationResult.Recommended) return null
        val all = listOf(latest.primary) + latest.alternatives
        val same = all.firstOrNull { it.station.id == accepted.station.id } ?: return latest.primary
        val best = latest.primary
        return if (best.station.id != same.station.id && best.score - same.score >= switchScoreGap) best else null
    }

    fun reset() {
        handled.clear()
        declined.clear()
    }
}
