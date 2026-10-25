package com.chargis.app.ui.parked

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chargis.app.R
import com.chargis.app.domain.model.UserSettings
import com.chargis.app.domain.session.TripPhase

import com.chargis.app.ui.TripUiState
import com.chargis.app.ui.theme.Ticket
import com.chargis.app.ui.ticket.RouteStrip
import com.chargis.app.ui.ticket.TicketStock

/**
 * 정차·충전 중 화면(plan.md §9.3). 상세와 스크롤을 허용한다.
 * 차가 멈춰 있을 때는 운전자가 화면을 자세히 볼 수 있으므로, 주행 화면보다 정보를 많이 보여 준다.
 *
 * 여행 단계에 따라 다른 화면을 고른다.
 * - Idle → 시나리오 고르기(발권) 화면 (01-home.png)
 * - Arrived → 충전소 도착 화면 (05-arrived.png)
 * - Charging → 충전 중 화면 (06-charging.png, 13-limit-warning.png)
 * - Done → 충전 완료 화면 (07-charge-done.png, 14-limit-ended.png)
 * - Finished → 정산 영수증 화면 (09-finished.png, 16-limit-receipt.png)
 */
@Composable
fun ParkedScreen(
    state: TripUiState,
    onStartScenario: (String) -> Unit,
    onStartCharging: () -> Unit,
    onStopCharging: () -> Unit,
    onDepart: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
    onAdjustTarget: (Int) -> Unit = {},
    onSettings: ((UserSettings) -> UserSettings) -> Unit = {},
    onCorrectSoc: (Int) -> Unit = {}
) {
    val stop = state.stop
    when {
        state.phase == TripPhase.Idle -> IssueScreen(state, onStartScenario, onSettings, modifier)
        state.phase == TripPhase.Finished && state.receipt != null -> ReceiptScreen(state, onRestart, modifier)
        stop != null && state.phase == TripPhase.Arrived -> StopScreen(state, stop, modifier) { ArrivedBody(stop, onStartCharging, onDepart, onAdjustTarget, onCorrectSoc) }
        stop != null && state.phase == TripPhase.Charging -> StopScreen(state, stop, modifier) { ChargingBody(stop, onStopCharging, onAdjustTarget, onCorrectSoc) }
        stop != null && state.phase == TripPhase.Done -> StopScreen(state, stop, modifier) {
            DoneBody(stop, state.socPercent, state.receipt?.charges?.lastOrNull(), onDepart, onCorrectSoc)
        }
        // 그 밖의 정차(예: 주행 중 잠깐 멈춤)는 간단한 상태 화면
        else -> TicketStock(modifier.fillMaxSize()) {
            Column(Modifier.padding(40.dp)) {
                Text(stringResource(R.string.parked_status, state.scenarioTitle), style = MaterialTheme.typography.displaySmall, color = Ticket.colors.ink)
                Spacer(Modifier.weight(1f))
                RouteStrip(state.routeMarks, state.progress)
            }
        }
    }
}
