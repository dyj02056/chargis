package com.chargis.app.domain.engine

import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.model.RecommendationResult

/**
 * plan.md §7.3 안내 시점과 히스테리시스.
 * - 1순위가 [announceWithinKm] 안에 들어오면 한 번만 안내한다.
 * - 이미 안내/수락/거절한 휴게소는 다시 안내하지 않는다.
 * - 수락한 추천은 점수 차가 [switchScoreGap] 이상 벌어지거나 후보에서 사라질 때만 바꾼다.
 *
 * 운전자를 귀찮게 하지 않기 위한 "문지기"다. 추천 엔진은 매 순간 새로 계산하므로,
 * 그대로 안내하면 같은 말을 반복하거나 추천이 이리저리 바뀔 수 있다.
 * 히스테리시스란 "작은 변화에는 반응하지 않고, 충분히 큰 변화가 있을 때만 바꾼다"는 뜻이다.
 * (에어컨이 온도가 조금 오르내릴 때마다 켜졌다 꺼지지 않는 것과 같은 원리)
 */
class AnnouncementGate(
    private val announceWithinKm: Double,
    private val switchScoreGap: Double = 0.15
) {
    // 이미 다룬(안내했거나 거절한) 휴게소 id
    private val handled = mutableSetOf<String>()
    // 운전자가 거절한 휴게소 id. 추천 후보에서도 뺀다.
    private val declined = mutableSetOf<String>()

    val declinedIds: Set<String> get() = declined

    /** 아직 안내한 적 없고, 충분히 가까워졌으면(기본 50km 이내) 안내해도 된다. */
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
        // 수락한 휴게소가 새 후보 목록에서 사라졌으면(예: 갈 수 없게 됨) 새 1순위로 바꾼다.
        val same = all.firstOrNull { it.station.id == accepted.station.id } ?: return latest.primary
        val best = latest.primary
        // 새 1순위가 수락한 곳보다 점수가 충분히(0.15 이상) 높을 때만 바꾼다.
        return if (best.station.id != same.station.id && best.score - same.score >= switchScoreGap) best else null
    }

    /** 새 여행을 시작할 때 기억을 모두 지운다. */
    fun reset() {
        handled.clear()
        declined.clear()
    }
}
