package com.chargis.app.domain.engine

import com.chargis.app.domain.model.RecommendationConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ChargeMathTest {
    private val config = RecommendationConfig()

    @Test
    fun socPerKm_defaultProfile() {
        assertEquals(0.2584, ChargeMath.socPerKm(defaultProfile), 0.0001)
    }

    @Test
    fun ceilToStep_roundsUpButKeepsExactMultiples() {
        assertEquals(50.0, ChargeMath.ceilToStep(45.66, 5.0), 1e-9)
        assertEquals(50.0, ChargeMath.ceilToStep(50.0, 5.0), 1e-9)
    }

    /** T2: 도착 20%, 50kW, 시간제한 40분, 목표 80% */
    @Test
    fun t2_chargeTimeAndSocAtLimit() {
        val minutes = ChargeMath.chargeMinutes(20.0, 80.0, 50.0, defaultProfile, config)
        assertEquals(74.3, minutes, 0.1)
        val socAtLimit = ChargeMath.socAfterMinutes(20.0, 40.0, 50.0, defaultProfile, config)
        assertEquals(52.3, socAtLimit, 0.1)
    }

    @Test
    fun socAfterMinutes_isInverseOfChargeMinutes_acrossTaper() {
        val minutes = ChargeMath.chargeMinutes(30.0, 90.0, 150.0, defaultProfile, config)
        assertEquals(90.0, ChargeMath.socAfterMinutes(30.0, minutes, 150.0, defaultProfile, config), 1e-6)
    }

    @Test
    fun socAfterMinutes_neverExceeds100() {
        assertEquals(100.0, ChargeMath.socAfterMinutes(50.0, 10_000.0, 150.0, defaultProfile, config), 1e-9)
    }
}
