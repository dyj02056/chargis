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

/*
 * 화면 코드는 Jetpack Compose로 만들었다. @Composable이 붙은 함수 하나가 화면 조각 하나를 그린다.
 * 레고처럼 작은 조각(글자, 칸, 버튼)을 조립해 큰 화면을 만든다.
 * - Box: 겹쳐 쌓기, Column: 위에서 아래로, Row: 왼쪽에서 오른쪽으로 배치
 * - Modifier: 크기, 여백, 배경색 같은 꾸밈을 붙이는 방법
 * - dp, sp: 화면 크기와 상관없이 일정하게 보이는 길이 단위(sp는 글자 크기용)
 * 상태(state)가 바뀌면 Compose가 바뀐 부분만 알아서 다시 그린다.
 */

/**
 * isDriving 하나로 주행(표 한 칸) ↔ 정차(상세) 화면을 즉시 전환한다(plan.md §9.1).
 * 앱 화면의 가장 바깥 틀이다. 위에서부터 [머리 줄 → 본문(주행 또는 정차 화면) → 자막 줄] 순서로 쌓는다.
 */
@Composable
fun ChargisRoot(controller: TripController) {
    // 컨트롤러의 상태를 구독한다. 값이 바뀔 때마다 이 화면이 다시 그려진다.
    val state by controller.state.collectAsStateWithLifecycle()
    val c = Ticket.colors

    CompositionLocalProvider(LocalContentColor provides c.ink) {
        // safeDrawingPadding: 상태 표시줄이나 화면 모서리에 내용이 가리지 않게 여백을 둔다.
        Box(Modifier.fillMaxSize().background(c.ground).safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                TicketHeader(state)
                // weight(1f): 머리 줄과 자막 줄을 뺀 나머지 공간을 모두 차지한다.
                Box(Modifier.weight(1f).padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
                    // 운전 중에는 꼭 필요한 정보만, 멈춰 있을 때는 자세한 정보를 보여 준다(운전자 안전).
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
            // 개발용 빌드에서만 오른쪽 위에 시연 도구를 띄운다(build.gradle.kts의 DEV_OVERLAY).
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
    // 펼침/접힘 상태. rememberSaveable이라 화면을 돌려도 유지된다.
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
            // 시간 배속: 1배, 5배, 20배, 60배
            Row(Modifier.padding(horizontal = 6.dp)) {
                listOf(1, 5, 20, 60).forEach { m ->
                    DevButton(stringResource(R.string.dev_speed, m), active = dev.speedMultiplier == m) { controller.devSetSpeed(m) }
                }
            }
            // 일시정지/재개, 10km·30km 앞으로 건너뛰기
            Row(Modifier.padding(horizontal = 6.dp).padding(bottom = 6.dp)) {
                DevButton(stringResource(if (dev.paused) R.string.dev_resume else R.string.dev_pause), active = dev.paused, onClick = controller::devTogglePause)
                listOf(10, 30).forEach { km ->
                    DevButton(stringResource(R.string.dev_jump, km), active = false) { controller.devJump(km.toDouble()) }
                }
            }
        }
    }
}

// 개발자 오버레이의 작은 버튼. 선택된 버튼은 강조색으로 표시한다. 최소 48dp로 손가락으로 누르기 쉽게 했다.
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
