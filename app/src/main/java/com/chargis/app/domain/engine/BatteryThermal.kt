package com.chargis.app.domain.engine

import kotlin.math.max
import kotlin.math.min

/**
 * 배터리 온도와 급속 충전 속도. 순수 Kotlin.
 *
 * 리튬이온 배터리는 차가우면 충전 전류를 많이 받지 못한다. 그래서 겨울에 차가운 배터리로 휴게소에 들어가면
 * 같은 충전기라도 훨씬 오래 걸린다. 많은 전기차는 충전소로 가는 동안 배터리를 미리 데우는 "배터리 예열(컨디셔닝)"을 지원한다.
 *
 * 이 파일은 두 가지를 어림한다.
 * 1. [chargePowerFactor]: 배터리 온도에 따라 차가 받아들이는 충전 출력이 평소의 몇 배인지
 * 2. [tempAfter]: 주행·예열·충전을 몇 분 하면 배터리 온도가 어떻게 되는지
 * 계수는 공개 자료의 대략적인 경향을 따른 가정값이다(docs/DECISIONS.md). 실제 차는 배터리 관리 장치가 정한다.
 */
object BatteryThermal {

    /** 지금 무엇을 하고 있는지. 하는 일에 따라 배터리가 데워지는 속도가 다르다. */
    enum class Mode { DRIVING, PRECONDITIONING, CHARGING }

    /**
     * 배터리 온도에 따른 충전 수용 배율. 20°C 이상이면 1.0(제 속도), -10°C 이하면 0.25, 그 사이는 직선으로 잇는다.
     * 예) 0°C → 0.5. 150kW를 받던 차가 75kW만 받는다. 겨울에 예열 없이 들어간 전기차가 최고 출력의 3~5할만
     * 받는다는 공개 시험 결과의 대략적인 경향에 맞췄다. 온도를 모르면 1.0(보정하지 않음).
     */
    fun chargePowerFactor(tempC: Double?): Double {
        if (tempC == null) return 1.0
        val t = (tempC - COLD_C) / (WARM_C - COLD_C)
        return (COLD_FACTOR + (1.0 - COLD_FACTOR) * t).coerceIn(COLD_FACTOR, 1.0)
    }

    /**
     * 지금 [tempC]에서 [minutes]분 동안 [mode]를 한 뒤의 배터리 온도.
     * - 주행: 분당 0.05°C씩 15°C까지(주행 열로 조금씩만 데워진다)
     * - 예열: 분당 1°C씩 25°C까지(히터로 데운다. 대신 배터리를 쓴다, [PRECONDITION_KW])
     * - 충전: 분당 0.4°C씩 35°C까지(충전하면서 스스로 데워진다)
     * 이미 그보다 따뜻하면 그대로 둔다.
     */
    fun tempAfter(tempC: Double, minutes: Double, mode: Mode): Double {
        if (minutes <= 0) return tempC
        val (rate, ceiling) = when (mode) {
            Mode.DRIVING -> DRIVE_RATE to DRIVE_CEIL_C
            Mode.PRECONDITIONING -> PRECONDITION_RATE to PRECONDITION_CEIL_C
            Mode.CHARGING -> CHARGE_RATE to CHARGE_CEIL_C
        }
        if (tempC >= ceiling) return tempC
        return min(ceiling, tempC + rate * minutes)
    }

    /**
     * [minutesAhead]분 뒤 충전소에 도착했을 때의 충전 수용 배율. 예열 중이면 예열 속도로, 아니면 주행 속도로 데워진다고 본다.
     * 온도를 모르면 1.0.
     */
    fun factorOnArrival(tempC: Double?, minutesAhead: Double, preconditioning: Boolean): Double {
        if (tempC == null) return 1.0
        val mode = if (preconditioning) Mode.PRECONDITIONING else Mode.DRIVING
        return chargePowerFactor(tempAfter(tempC, max(0.0, minutesAhead), mode))
    }

    /** 예열 히터가 쓰는 출력(kW, 가정). 30분 켜면 약 2kWh(77.4kWh 배터리의 약 2.6%)를 쓴다. */
    const val PRECONDITION_KW = 4.0
    /** 도착 몇 분 전부터 예열을 권하는지. 영하에서 충분히 데우려면 20~30분이 걸린다. */
    const val PRECONDITION_LEAD_MIN = 30.0

    private const val WARM_C = 20.0
    private const val COLD_C = -10.0
    private const val COLD_FACTOR = 0.25
    private const val DRIVE_RATE = 0.05
    private const val DRIVE_CEIL_C = 15.0
    private const val PRECONDITION_RATE = 1.0
    private const val PRECONDITION_CEIL_C = 25.0
    private const val CHARGE_RATE = 0.4
    private const val CHARGE_CEIL_C = 35.0
}
