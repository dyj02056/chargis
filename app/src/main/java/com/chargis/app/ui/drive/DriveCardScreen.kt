package com.chargis.app.ui.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chargis.app.R
import com.chargis.app.domain.model.ReasonCode
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.ui.BigButton
import com.chargis.app.ui.DriveCard
import com.chargis.app.ui.theme.ChargisColors
import kotlin.math.roundToInt

/**
 * 주행 중 화면(plan.md §9.2): 카드 1장 + 큰 버튼만. 스크롤·텍스트 입력·리스트 없음.
 */
@Composable
fun DriveCardScreen(
    card: DriveCard,
    soc: Double,
    remainingKm: Double,
    onAccept: (String) -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = ChargisColors.Surface
        ) {
            Column(Modifier.padding(36.dp)) {
                when (card) {
                    is DriveCard.Recommend -> RecommendCard(card.rec, onAccept, onDecline)
                    is DriveCard.Congested -> CongestedCard(card.primary, card.alternative, onAccept)
                    is DriveCard.Navigating -> {
                        Headline(stringResource(R.string.card_navigating_headline, card.stationName))
                        Sub(
                            stringResource(
                                R.string.card_navigating_sub,
                                card.distanceKm.roundToInt(),
                                card.targetSoc.roundToInt()
                            )
                        )
                    }
                    is DriveCard.Status -> {
                        Headline(
                            stringResource(
                                if (card.chargePlanned) R.string.card_charge_planned_headline
                                else R.string.card_no_charge_headline
                            )
                        )
                        Sub(stringResource(R.string.card_status_sub, soc.roundToInt(), remainingKm.roundToInt()))
                    }
                    is DriveCard.NoSafeStop -> {
                        Headline(stringResource(R.string.card_no_safe_headline), warn = true)
                        Sub(
                            if (card.nearestName != null && card.nearestKm != null) {
                                stringResource(R.string.card_no_safe_sub, card.nearestName, card.nearestKm.roundToInt())
                            } else {
                                stringResource(R.string.card_no_safe_none)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendCard(rec: Recommendation, onAccept: (String) -> Unit, onDecline: () -> Unit) {
    Headline(
        stringResource(
            R.string.card_recommend_headline,
            rec.station.distanceAlongRouteKm.roundToInt(),
            rec.station.name
        )
    )
    Sub(stringResource(R.string.card_recommend_sub, rec.targetSocPercent.roundToInt(), rec.chargeMinutes))
    if (ReasonCode.TIME_LIMIT_RISK in rec.reasonCodes && rec.timeLimitMin != null) {
        Note(
            stringResource(
                R.string.card_time_limit_risk,
                rec.timeLimitMin,
                (rec.socAtTimeLimitPercent ?: 0.0).roundToInt()
            )
        )
    }
    if (rec.needsAnotherStop) Note(stringResource(R.string.card_need_another))
    Spacer(Modifier.height(28.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        BigButton(stringResource(R.string.card_accept), { onAccept(rec.station.id) }, Modifier.weight(1f))
        BigButton(stringResource(R.string.card_next_stop), onDecline, Modifier.weight(1f), primary = false)
    }
}

/** 혼잡 변형(§7.3): 대기 시간 vs 도착 시 배터리를 2열 큰 선택지로 비교. */
@Composable
private fun CongestedCard(primary: Recommendation, alternative: Recommendation, onAccept: (String) -> Unit) {
    Headline(stringResource(R.string.card_congested_title), warn = true)
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        listOf(primary, alternative).forEach { rec ->
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        R.string.card_recommend_headline,
                        rec.station.distanceAlongRouteKm.roundToInt(),
                        rec.station.name
                    ),
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    stringResource(
                        R.string.card_choice_line,
                        rec.station.expectedWaitMin?.let { stringResource(R.string.minutes_short, it) }
                            ?: stringResource(R.string.unknown),
                        rec.arrivalSocPercent.roundToInt()
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = ChargisColors.Muted
                )
                Spacer(Modifier.height(16.dp))
                BigButton(
                    stringResource(R.string.card_choose),
                    { onAccept(rec.station.id) },
                    Modifier.fillMaxWidth(),
                    primary = rec === alternative
                )
            }
        }
    }
}

@Composable
private fun Headline(text: String, warn: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.displayMedium,
        color = if (warn) ChargisColors.Warning else ChargisColors.OnSurface,
        maxLines = 2
    )
}

@Composable
private fun Sub(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.headlineMedium, color = ChargisColors.Accent, maxLines = 1)
}

@Composable
private fun Note(text: String) {
    Spacer(Modifier.height(6.dp))
    Text("⚠ $text", style = MaterialTheme.typography.titleLarge, color = ChargisColors.Warning, maxLines = 1)
}
