package com.chargis.app.domain.session

import com.chargis.app.domain.engine.ChargeMath
import com.chargis.app.domain.engine.defaultProfile
import com.chargis.app.domain.model.RecommendationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 충전 중 음성 알림 테스트(T8). 충전을 처음부터 끝까지 가상으로 돌려 보고,
 * 어떤 알림이 어떤 순서로 몇 번 나왔는지 목록으로 비교한다.
 */
class AlertSchedulerTest {
    private val config = RecommendationConfig()

    /** 충전 한 번을 dt초 간격으로 모델대로 돌리며 나온 알림을 순서대로 모은다. 제한에 닿으면 충전기가 끊는다. */
    private fun runSession(fromSoc: Double, target: Double, kw: Double, limit: Int?, dtSec: Double): List<AlertType> {
        val tracker = ChargingSessionTracker(defaultProfile, config, kw, target, limit, startSec = 0.0)
        val scheduler = AlertScheduler(limit)
        val out = scheduler.start().toMutableList()
        var t = 0.0
        var soc = fromSoc
        while (true) {
            val snap = tracker.update(t, soc)
            out += scheduler.evaluate(snap)
            if (snap.reached || snap.limitExpired) break
            val step = if (limit != null) minOf(dtSec, limit * 60.0 - t) else dtSec
            soc = minOf(target, ChargeMath.socAfterMinutes(soc, step / 60.0, kw, defaultProfile, config))
            t += step
        }
        return out
    }

    /** T8: 제한에 걸리는 충전(20→80%, 50kW, 40분 제한) — 제한 T-10, T-5 각 1회, 순서대로, 그리고 미달 확정. */
    @Test
    fun t8_limitWarningsFireOnceInOrder() {
        val alerts = runSession(20.0, 80.0, 50.0, limit = 40, dtSec = 10.0)
        assertEquals(
            listOf(AlertType.RULE_NOTICE, AlertType.LIMIT_T10, AlertType.LIMIT_T5, AlertType.LIMIT_SHORTFALL),
            alerts
        )
    }

    /** T8: 제한 안에 끝나는 충전(37→50%, 100kW, 40분 제한) — 목표 T-5 1회, 제한 경고 없음, 목표 도달. */
    @Test
    fun t8_targetSoonFiresOnceWhenTargetBeatsLimit() {
        val alerts = runSession(37.0, 50.0, 100.0, limit = 40, dtSec = 10.0)
        assertEquals(listOf(AlertType.RULE_NOTICE, AlertType.TARGET_T5, AlertType.TARGET_REACHED), alerts)
    }

    /** 데모 가속(한 번에 30분씩 진행)에서도 같은 이벤트가 같은 순서로 나온다. */
    @Test
    fun t8_orderHeldUnderAcceleration() {
        val fine = runSession(20.0, 80.0, 50.0, limit = 40, dtSec = 10.0)
        val coarse = runSession(20.0, 80.0, 50.0, limit = 40, dtSec = 1800.0)
        assertEquals(fine, coarse)
    }

    // 시간 제한이 없는 충전기면 규칙 안내와 제한 경고가 아예 나오지 않는지
    @Test
    fun noLimitMeansNoRuleNoticeOrLimitWarnings() {
        val alerts = runSession(30.0, 60.0, 100.0, limit = null, dtSec = 10.0)
        assertEquals(listOf(AlertType.TARGET_T5, AlertType.TARGET_REACHED), alerts)
    }

    // 같은 상황을 두 번 평가해도 같은 알림을 두 번 내지 않는지(32분 시점 = 제한 10분 전 이내)
    @Test
    fun repeatedEvaluationDoesNotRefire() {
        val tracker = ChargingSessionTracker(defaultProfile, config, 50.0, 80.0, 40, startSec = 0.0)
        val scheduler = AlertScheduler(40)
        val snap = tracker.update(32 * 60.0, 45.0)
        val first = scheduler.evaluate(snap)
        val again = scheduler.evaluate(tracker.update(32 * 60.0 + 1, 45.01))
        assertEquals(listOf(AlertType.LIMIT_T10), first)
        assertTrue(again.isEmpty())
    }
}
