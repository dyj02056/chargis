package com.chargis.app.ui.parked

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chargis.app.R
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.session.TripPhase
import com.chargis.app.ui.BigButton
import com.chargis.app.ui.StopInfo
import com.chargis.app.ui.TripUiState
import com.chargis.app.ui.theme.ChargisColors
import kotlin.math.roundToInt

/** 정차·충전 중 화면(plan.md §9.3). 상세 정보와 스크롤을 허용한다. */
@Composable
fun ParkedScreen(
    state: TripUiState,
    onStartScenario: (String) -> Unit,
    onStartCharging: () -> Unit,
    onStopCharging: () -> Unit,
    onDepart: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.widthIn(max = 1100.dp).fillMaxWidth()) {
            val stop = state.stop
            when {
                state.phase == TripPhase.Idle -> {
                    Title(stringResource(R.string.parked_title_idle))
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        state.scenarios.forEach { s ->
                            BigButton(
                                stringResource(R.string.parked_start_scenario, s.title),
                                { onStartScenario(s.id) },
                                Modifier.weight(1f)
                            )
                        }
                    }
                }
                state.phase == TripPhase.Arrived && stop != null -> {
                    Title(stringResource(R.string.parked_arrived_title, stop.stationName))
                    Big(
                        stringResource(
                            R.string.parked_arrived_sub,
                            stop.currentSoc.roundToInt(),
                            stop.targetSoc.roundToInt(),
                            stop.minutesToTarget
                        )
                    )
                    Muted(
                        stop.timeLimitMin?.let { stringResource(R.string.parked_rule_limit, it) }
                            ?: stringResource(R.string.parked_rule_unknown)
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        BigButton(stringResource(R.string.parked_start_charging), onStartCharging, Modifier.weight(1f))
                        BigButton(stringResource(R.string.parked_skip_charging), onDepart, Modifier.weight(1f), primary = false)
                    }
                }
                state.phase == TripPhase.Charging && stop != null -> {
                    ChargingPanel(stop)
                    Spacer(Modifier.height(24.dp))
                    BigButton(stringResource(R.string.parked_stop_charging), onStopCharging, Modifier.fillMaxWidth(), primary = false)
                }
                state.phase == TripPhase.Done -> {
                    Title(stringResource(R.string.parked_done_title, state.socPercent.roundToInt()))
                    Spacer(Modifier.height(24.dp))
                    BigButton(stringResource(R.string.parked_depart), onDepart, Modifier.fillMaxWidth())
                }
                state.phase == TripPhase.Finished -> {
                    Title(stringResource(R.string.parked_finished_title, state.socPercent.roundToInt()))
                    Spacer(Modifier.height(24.dp))
                    BigButton(stringResource(R.string.parked_restart), onRestart, Modifier.fillMaxWidth())
                }
                else -> Title(stringResource(R.string.card_status_sub, state.socPercent.roundToInt(), state.remainingKm.roundToInt()))
            }

            if (state.candidates.isNotEmpty() && state.phase != TripPhase.Idle && state.phase != TripPhase.Finished) {
                Spacer(Modifier.height(36.dp))
                CandidateTable(state.candidates)
            }
        }
    }
}

/** 충전 화면: 큰 게이지 + 목표까지/제한까지/출발 예정을 크게(동승자도 읽을 수 있게). */
@Composable
private fun ChargingPanel(stop: StopInfo) {
    Title(stringResource(R.string.parked_charging_title, stop.stationName))
    Spacer(Modifier.height(16.dp))
    Text(
        "${stop.currentSoc.roundToInt()}% → ${stop.targetSoc.roundToInt()}%",
        style = MaterialTheme.typography.displayLarge,
        color = ChargisColors.Accent
    )
    LinearProgressIndicator(
        progress = { (stop.currentSoc / stop.targetSoc).toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(28.dp).padding(vertical = 4.dp)
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        Big(stringResource(R.string.parked_to_target, stop.minutesToTarget))
        Big(
            stop.minutesToLimit?.let { stringResource(R.string.parked_to_limit, it) }
                ?: stringResource(R.string.parked_no_limit)
        )
        Big(stringResource(R.string.parked_depart_at, stop.departAt))
    }
}

/** 후보 비교 표(도착 SoC·출력·가용 대수·대기). 미상 값은 "정보 없음"으로 표기. */
@Composable
private fun CandidateTable(candidates: List<Recommendation>) {
    Text(stringResource(R.string.parked_candidates), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    TableRow(
        stringResource(R.string.parked_col_station),
        stringResource(R.string.parked_col_arrival),
        stringResource(R.string.parked_col_power),
        stringResource(R.string.parked_col_available),
        stringResource(R.string.parked_col_wait),
        header = true
    )
    val unknown = stringResource(R.string.unknown)
    candidates.forEach { r ->
        val s = r.station
        TableRow(
            s.name,
            "${r.arrivalSocPercent.roundToInt()}%",
            stringResource(R.string.kw_short, s.maxKw.roundToInt()),
            s.availableChargers?.let { stringResource(R.string.available_fmt, it, s.totalChargers) } ?: unknown,
            s.expectedWaitMin?.let { stringResource(R.string.minutes_short, it) } ?: unknown
        )
    }
}

@Composable
private fun TableRow(vararg cells: String, header: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        cells.forEachIndexed { i, c ->
            Text(
                c,
                modifier = Modifier.weight(if (i == 0) 1.6f else 1f),
                style = MaterialTheme.typography.bodyLarge,
                color = if (header) ChargisColors.Muted else ChargisColors.OnSurface
            )
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.displayMedium)

@Composable
private fun Big(text: String) = Text(text, style = MaterialTheme.typography.headlineMedium)

@Composable
private fun Muted(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, color = ChargisColors.Muted)
