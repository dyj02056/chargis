package com.chargis.app.domain.session

import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.domain.model.VehicleProfile

/**
 * 충전 한 번을 통째로 맡는 묶음(B5에서 TripController로부터 분리).
 * 남은 시간을 재는 [ChargingSessionTracker]와 음성 알림 시점을 정하는 [AlertScheduler]를 함께 들고,
 * 충전 중 목표를 바꾸는 일(F2)도 여기서 처리한다. 순수 Kotlin.
 *
 * [startSec]·[fromSoc]는 충전을 시작한 시각과 그때의 배터리 %(영수증용)다.
 */
class ChargeSession(
    private val profile: VehicleProfile,
    private val config: RecommendationConfig,
    val stationMaxKw: Double,
    targetSoc: Double,
    val timeLimitMin: Int?,
    val startSec: Double,
    val fromSoc: Double,
    firedAlerts: Set<AlertType> = emptySet()
) {
    var targetSoc: Double = targetSoc
        private set
    private var tracker = newTracker(targetSoc)
    private val scheduler = AlertScheduler(timeLimitMin, firedAlerts)

    /** 가장 최근 충전 상황. 화면과 알림 문구가 같은 값을 쓴다. */
    var snapshot: ChargingSnapshot? = null
        private set

    /** 이미 낸 알림(여행 저장용). */
    val firedAlerts: Set<AlertType> get() = scheduler.firedAlerts

    /** 충전 시작 안내(시간 제한이 있으면 운영 규칙). */
    fun start(nowSec: Double, soc: Double): List<AlertType> {
        snapshot = tracker.update(nowSec, soc)
        return scheduler.start()
    }

    /** 새 측정값을 반영하고, 지금 내야 할 알림을 시간 순서대로 돌려준다. */
    fun update(nowSec: Double, soc: Double): List<AlertType> {
        val snap = tracker.update(nowSec, soc)
        snapshot = snap
        return scheduler.evaluate(snap)
    }

    /**
     * 충전 중 목표를 [newTarget]으로 바꾼다(F2). 남은 시간 보정은 새로 시작하고,
     * "곧 목표 도달" 안내는 새 목표 기준으로 다시 낼 수 있게 한다.
     */
    fun retarget(newTarget: Double, nowSec: Double, soc: Double) {
        targetSoc = newTarget
        tracker = newTracker(newTarget)
        scheduler.rearmTarget()
        snapshot = tracker.update(nowSec, soc)
    }

    private fun newTracker(target: Double) = ChargingSessionTracker(
        profile = profile,
        config = config,
        stationMaxKw = stationMaxKw,
        targetSoc = target,
        timeLimitMin = timeLimitMin,
        startSec = startSec
    )
}
