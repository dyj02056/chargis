package com.chargis.app.domain.engine

import com.chargis.app.domain.model.RecommendationConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * 충전 계산식(ChargeMath)이 맞게 계산하는지 확인하는 테스트.
 * @Test가 붙은 함수 하나가 검사 하나다. assertEquals(기대값, 실제값, 허용 오차)는
 * 두 값이 오차 범위 안에서 같지 않으면 테스트를 실패로 표시한다.
 */
class ChargeMathTest {
    private val config = RecommendationConfig()

    // 1km에 약 0.26%를 쓰는지 (1 ÷ 5 ÷ 77.4 × 100)
    @Test
    fun socPerKm_defaultProfile() {
        assertEquals(0.2584, ChargeMath.socPerKm(defaultProfile), 0.0001)
    }

    // 45.66은 50으로 올리고, 이미 50이면 55로 올리지 않고 그대로 둔다.
    @Test
    fun ceilToStep_roundsUpButKeepsExactMultiples() {
        assertEquals(50.0, ChargeMath.ceilToStep(45.66, 5.0), 1e-9)
        assertEquals(50.0, ChargeMath.ceilToStep(50.0, 5.0), 1e-9)
    }

    /**
     * T2: 도착 20%, 50kW, 시간제한 40분, 목표 80%
     * 80%까지는 약 74분이 걸리는데 40분 제한이면 약 52%에서 끊긴다는 것을 확인한다.
     */
    @Test
    fun t2_chargeTimeAndSocAtLimit() {
        val minutes = ChargeMath.chargeMinutes(20.0, 80.0, 50.0, defaultProfile, config)
        assertEquals(74.3, minutes, 0.1)
        val socAtLimit = ChargeMath.socAfterMinutes(20.0, 40.0, 50.0, defaultProfile, config)
        assertEquals(52.3, socAtLimit, 0.1)
    }

    // 30%→90% 걸리는 시간을 구한 뒤, 그 시간만큼 충전하면 정확히 90%가 되는지(80% 감속 구간을 넘어가도)
    @Test
    fun socAfterMinutes_isInverseOfChargeMinutes_acrossTaper() {
        val minutes = ChargeMath.chargeMinutes(30.0, 90.0, 150.0, defaultProfile, config)
        assertEquals(90.0, ChargeMath.socAfterMinutes(30.0, minutes, 150.0, defaultProfile, config), 1e-6)
    }

    // 아무리 오래 충전해도 100%를 넘지 않는지
    @Test
    fun socAfterMinutes_neverExceeds100() {
        assertEquals(100.0, ChargeMath.socAfterMinutes(50.0, 10_000.0, 150.0, defaultProfile, config), 1e-9)
    }
}
