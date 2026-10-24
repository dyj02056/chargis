package com.chargis.app.domain.session

import com.chargis.app.domain.engine.defaultProfile
import com.chargis.app.domain.model.RecommendationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingSessionTrackerTest {
    private val config = RecommendationConfig()

    /** 샘플이 하나뿐이면 모델값: T2와 같은 조건(20→80%, 50kW, 40분 제한). */
    @Test
    fun usesModelWhenSamplesAreScarce() {
        val tracker = ChargingSessionTracker(defaultProfile, config, 50.0, 80.0, 40, startSec = 0.0)
        val s = tracker.update(0.0, 20.0)
        assertEquals(74.3, s.minutesToTarget, 0.1)
        assertEquals(40.0, s.minutesToLimit!!, 1e-9)
        assertEquals(52.3, s.predictedSocAtLimit!!, 0.1)
        assertTrue(s.willHitLimit)
        assertEquals(40.0 * 60, s.departAtSec, 1e-6) // 제한 종료가 먼저
    }

    /** 실제 충전이 모델의 절반 속도면 남은 시간이 두 배로 보정된다. */
    @Test
    fun correctsWithMeasuredRate() {
        val tracker = ChargingSessionTracker(defaultProfile, config, 50.0, 80.0, null, startSec = 0.0)
        // 모델: 37.5kW → 분당 약 0.807%. 절반 속도로 2분 동안 0.807% 증가했다고 가정.
        tracker.update(0.0, 30.0)
        val s = tracker.update(120.0, 30.0 + 0.807)
        val model = ChargingSessionTracker(defaultProfile, config, 50.0, 80.0, null, startSec = 0.0).update(0.0, 30.807)
        assertEquals(model.minutesToTarget * 2, s.minutesToTarget, model.minutesToTarget * 0.05)
    }

    @Test
    fun reachedAndExpiredFlags() {
        val tracker = ChargingSessionTracker(defaultProfile, config, 100.0, 50.0, 10, startSec = 0.0)
        val reached = tracker.update(300.0, 50.0)
        assertTrue(reached.reached)
        assertEquals(0.0, reached.minutesToTarget, 1e-9)
        val other = ChargingSessionTracker(defaultProfile, config, 50.0, 80.0, 10, startSec = 0.0)
        val expired = other.update(600.0, 40.0)
        assertTrue(expired.limitExpired)
        assertFalse(expired.reached)
    }
}
