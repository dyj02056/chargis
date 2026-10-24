package com.chargis.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chargis.app.BuildConfig
import com.chargis.app.R
import com.chargis.app.ui.drive.DriveCardScreen
import com.chargis.app.ui.parked.ParkedScreen
import com.chargis.app.ui.theme.ChargisColors

/** isDriving 하나로 주행(카드 1장) ↔ 정차(상세) 화면을 즉시 전환한다(plan.md §9.1). */
@Composable
fun ChargisRoot(controller: TripController) {
    val state by controller.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(ChargisColors.Background).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            if (state.isDriving) {
                StatusStrip(state.socPercent, state.remainingKm, state.dataSource)
                DriveCardScreen(
                    card = state.card,
                    soc = state.socPercent,
                    remainingKm = state.remainingKm,
                    onAccept = controller::accept,
                    onDecline = controller::decline,
                    modifier = Modifier.weight(1f)
                )
            } else {
                ParkedScreen(
                    state = state,
                    onStartScenario = controller::startScenario,
                    onStartCharging = controller::startCharging,
                    onStopCharging = controller::stopCharging,
                    onDepart = controller::depart,
                    onRestart = controller::restart,
                    modifier = Modifier.weight(1f)
                )
            }
            CaptionBar(state.caption)
        }
        if (BuildConfig.DEV_OVERLAY) {
            DevOverlay(state.dev, controller, Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 12.dp))
        }
    }
}

/** 데모 전용 개발자 오버레이(배속·일시정지·점프). 릴리스 빌드에선 숨긴다. */
@Composable
private fun DevOverlay(dev: DevState, controller: TripController, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color(0xCC303030), RoundedCornerShape(12.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.End
    ) {
        Text(
            stringResource(R.string.dev_clock, dev.clock, dev.traveledKm),
            style = MaterialTheme.typography.labelMedium,
            color = ChargisColors.Muted
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(1, 5, 20, 60).forEach { m ->
                TextButton(onClick = { controller.devSetSpeed(m) }) {
                    Text(
                        stringResource(R.string.dev_speed, m),
                        color = if (dev.speedMultiplier == m) ChargisColors.Accent else ChargisColors.OnSurface
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = controller::devTogglePause) {
                Text(stringResource(if (dev.paused) R.string.dev_resume else R.string.dev_pause), color = ChargisColors.OnSurface)
            }
            listOf(10, 30).forEach { km ->
                TextButton(onClick = { controller.devJump(km.toDouble()) }) {
                    Text(stringResource(R.string.dev_jump, km), color = ChargisColors.OnSurface)
                }
            }
        }
    }
}
