package com.chargis.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chargis.app.BuildConfig
import com.chargis.app.R
import com.chargis.app.ui.drive.DriveCardScreen
import com.chargis.app.ui.parked.ParkedScreen
import com.chargis.app.ui.theme.FigureStyle
import com.chargis.app.ui.theme.Ticket

/** isDriving 하나로 주행(표 한 칸) ↔ 정차(상세) 화면을 즉시 전환한다(plan.md §9.1). */
@Composable
fun ChargisRoot(controller: TripController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val c = Ticket.colors

    CompositionLocalProvider(LocalContentColor provides c.ink) {
        Box(Modifier.fillMaxSize().background(c.ground).safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                TicketHeader(state)
                Box(Modifier.weight(1f).padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
                    if (state.isDriving) {
                        DriveCardScreen(
                            card = state.card,
                            eta = state.eta,
                            remainingKm = state.remainingKm,
                            meta = com.chargis.app.ui.drive.TicketMeta(state.ticketNo, state.departAt),
                            routeMarks = state.routeMarks,
                            progress = state.progress,
                            onAccept = controller::accept,
                            onDecline = controller::decline
                        )
                    } else {
                        ParkedScreen(
                            state = state,
                            onStartScenario = controller::startScenario,
                            onStartCharging = controller::startCharging,
                            onStopCharging = controller::stopCharging,
                            onDepart = controller::depart,
                            onRestart = controller::restart
                        )
                    }
                }
                CaptionBar(state.caption)
            }
            if (BuildConfig.DEV_OVERLAY) {
                DevOverlay(state.dev, controller, Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 32.dp))
            }
        }
    }
}

/** 데모 전용 개발자 오버레이(배속·일시정지·점프). 기본은 접힌 꼬리표 하나. 릴리스 빌드에선 없다. */
@Composable
private fun DevOverlay(dev: DevState, controller: TripController, modifier: Modifier = Modifier) {
    val c = Ticket.colors
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier
            .background(c.stock, RoundedCornerShape(6.dp))
            .border(1.dp, c.rule, RoundedCornerShape(6.dp)),
        horizontalAlignment = Alignment.End
    ) {
        Row(
            Modifier
                .clickable(role = Role.Button) { open = !open }
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (open) Text(stringResource(R.string.dev_clock, dev.clock, dev.traveledKm), style = FigureStyle.Small.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize), color = c.inkMuted)
            Text(stringResource(R.string.dev_toggle), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        }
        if (open) {
            Row(Modifier.padding(horizontal = 6.dp)) {
                listOf(1, 5, 20, 60).forEach { m ->
                    DevButton(stringResource(R.string.dev_speed, m), active = dev.speedMultiplier == m) { controller.devSetSpeed(m) }
                }
            }
            Row(Modifier.padding(horizontal = 6.dp).padding(bottom = 6.dp)) {
                DevButton(stringResource(if (dev.paused) R.string.dev_resume else R.string.dev_pause), active = dev.paused, onClick = controller::devTogglePause)
                listOf(10, 30).forEach { km ->
                    DevButton(stringResource(R.string.dev_jump, km), active = false) { controller.devJump(km.toDouble()) }
                }
            }
        }
    }
}

@Composable
private fun DevButton(text: String, active: Boolean, onClick: () -> Unit) {
    val c = Ticket.colors
    Box(
        Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .widthIn(min = 56.dp)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (active) c.stamp else c.ink)
    }
}
