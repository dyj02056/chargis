package com.chargis.app.domain.engine

import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.WaitSource
import kotlin.math.exp
import kotlin.math.roundToInt

/** 예상 대기와 그 출처. [minutes]가 null이면 아무 정보도 없다는 뜻이다. */
data class WaitEstimate(val minutes: Int?, val source: WaitSource)

/**
 * B4: "지금" 대기가 아니라 "도착했을 때" 대기를 예측한다. 순수 함수.
 *
 * 40분 뒤 도착할 충전소라면, 지금 30분 대기라는 실시간 값보다 그 시간대의 평소 대기가 더 맞을 수 있다.
 * 그래서 두 값을 섞는다. 도착이 가까울수록 실시간 값을, 멀수록 시간대별 통계를 더 믿는다.
 *
 *   실시간 비중 w = 0.5 ^ (도착까지 남은 분 ÷ 반감기)
 *   예측 = w × 실시간 + (1 − w) × 통계(도착 시각)
 *
 * 예) 반감기 45분, 45분 뒤 도착 → 실시간 50%, 통계 50%
 */
object WaitForecast {

    fun predict(
        station: ChargingStation,
        minutesAhead: Double,
        arrivalMinuteOfDay: Int?,
        halfLifeMin: Double
    ): WaitEstimate {
        val live = station.expectedWaitMin
        val hist = arrivalMinuteOfDay?.let { m -> station.waitByHour?.let { historical(it, m) } }
        return when {
            live != null && hist != null -> {
                val w = exp(-minutesAhead.coerceAtLeast(0.0) * LN2 / halfLifeMin)
                val blended = (w * live + (1 - w) * hist).roundToInt()
                // 실시간 비중이 압도적이면 실시간으로, 아니면 섞은 값으로 표시한다.
                WaitEstimate(blended, if (w >= LIVE_DOMINANT) WaitSource.LIVE else WaitSource.BLENDED)
            }
            live != null -> WaitEstimate(live, WaitSource.LIVE)
            hist != null -> WaitEstimate(hist.roundToInt(), WaitSource.FORECAST)
            else -> WaitEstimate(null, WaitSource.UNKNOWN)
        }
    }

    /** 24칸 시간대 표에서 [minuteOfDay]의 값을 앞뒤 시간대 사이로 직선 보간해 꺼낸다. 예) 13:30 → 13시와 14시의 중간 */
    private fun historical(byHour: List<Int>, minuteOfDay: Int): Double? {
        if (byHour.size != HOURS) return null
        val m = ((minuteOfDay % DAY_MIN) + DAY_MIN) % DAY_MIN
        val h = m / 60
        val t = (m % 60) / 60.0
        return byHour[h] + (byHour[(h + 1) % HOURS] - byHour[h]) * t
    }

    private const val HOURS = 24
    private const val DAY_MIN = 24 * 60
    private const val LN2 = 0.6931471805599453
    private const val LIVE_DOMINANT = 0.8   // 실시간 비중이 80% 이상이면 "실시간"으로 표시
}
