package com.chargis.app.ui.parked

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chargis.app.R
import com.chargis.app.domain.model.UserSettings
import com.chargis.app.domain.session.TripPhase
import com.chargis.app.ui.CandidateTable
import com.chargis.app.ui.ChargeRecord
import com.chargis.app.ui.MeasureField
import com.chargis.app.ui.ScenarioOption

import com.chargis.app.ui.StopInfo
import com.chargis.app.ui.TripReceipt
import com.chargis.app.ui.TripUiState
import com.chargis.app.ui.wonText
import com.chargis.app.ui.theme.FigureStyle
import com.chargis.app.ui.theme.Ticket
import com.chargis.app.ui.ticket.Field
import com.chargis.app.ui.ticket.Figure
import com.chargis.app.ui.ticket.HairlineH
import com.chargis.app.ui.ticket.HairlineV
import com.chargis.app.ui.ticket.RouteStrip
import com.chargis.app.ui.ticket.Stamp
import com.chargis.app.ui.ticket.TearLine
import com.chargis.app.ui.ticket.TicketButton
import com.chargis.app.ui.ticket.TicketStock
import com.chargis.app.ui.ticket.WarnGlyph
import kotlin.math.roundToInt

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

// ---- 발권 ----

/**
 * 첫 화면. 왼쪽에 앱 이름과 소개, 그 아래 지금 설정 요약과 [설정] 버튼, 오른쪽에 고를 수 있는 시나리오 표들.
 * 설정 칸이 많아 첫 화면에 다 늘어놓지 않고 [설정]을 누르면 따로 연다([SettingsScreen]).
 * 처음 실행이면 설정 안내 문구를 보여 주고, 발권하면 설정을 마친 것으로 기록한다.
 */
@Composable
private fun IssueScreen(
    state: TripUiState,
    onStart: (String) -> Unit,
    onSettings: ((UserSettings) -> UserSettings) -> Unit,
    modifier: Modifier
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    if (editing) {
        SettingsScreen(state, onSettings, onDone = { editing = false; onSettings { it.copy(onboarded = true) } }, modifier = modifier)
        return
    }
    val c = Ticket.colors
    val issue = { id: String ->
        if (!state.settings.onboarded) onSettings { it.copy(onboarded = true) }
        onStart(id)
    }
    Row(modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        Column(Modifier.weight(0.85f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium.copy(fontSize = MaterialTheme.typography.displayMedium.fontSize * 1.4f, lineHeight = MaterialTheme.typography.displayMedium.lineHeight * 1.35f), color = c.ink, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.ticket_kind), style = MaterialTheme.typography.headlineMedium, color = c.ink)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.idle_lede), style = MaterialTheme.typography.bodyLarge, color = c.inkMuted, modifier = Modifier.widthIn(max = 460.dp))
            Spacer(Modifier.height(24.dp))
            SettingsSummary(state) { editing = true }
        }
        Column(Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            state.scenarios.forEach { IssueTicket(it, issue) }
        }
    }
}

/** 발권 화면의 설정 요약: 차종과 주요 값 한 줄씩, 그리고 [설정] 버튼. */
@Composable
private fun SettingsSummary(state: TripUiState, onEdit: () -> Unit) {
    val c = Ticket.colors
    val st = state.settings
    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.semantics { heading() })
    if (!st.onboarded) {
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.settings_onboarding), style = MaterialTheme.typography.bodyMedium, color = c.stamp)
    }
    Spacer(Modifier.height(8.dp))
    HairlineH()
    val profile = state.profiles.firstOrNull { it.id == st.profileId } ?: state.profiles.firstOrNull()
    profile?.let { SummaryLine(stringResource(R.string.settings_vehicle), it.name) }
    SummaryLine(
        stringResource(R.string.settings_summary_charge),
        stringResource(R.string.settings_summary_charge_value, st.maxTargetSocPercent.roundToInt(), st.safetyMarginPercent, st.destinationSocPercent)
    )
    SummaryLine(
        stringResource(R.string.settings_summary_options),
        listOf(
            stringResource(if (st.includeNonRoadside) R.string.settings_stations_all else R.string.settings_stations_rest_only),
            stringResource(if (st.manualSoc) R.string.settings_soc_manual else R.string.settings_soc_vehicle),
            stringResource(if (st.voiceCommands) R.string.settings_summary_voice_on else R.string.settings_summary_voice_off)
        ).joinToString(" · ")
    )
    Spacer(Modifier.height(16.dp))
    TicketButton(stringResource(R.string.settings_edit), onEdit, Modifier.width(240.dp), primary = false, minHeight = 72.dp)
}

// 설정 요약 한 줄: 칸 이름 위에 값. 값은 일반 글꼴로 적어 길어도 자연스럽게 줄바꿈된다.
@Composable
private fun SummaryLine(label: String, value: String) {
    val c = Ticket.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        Text(value, style = MaterialTheme.typography.titleMedium, color = c.ink)
    }
}

/**
 * F5 내 차 설정 화면: 왼쪽에 차종·충전 상한·안전 여유·목적지 도착 잔량, 오른쪽에 충전소 범위·배터리 값·음성 명령.
 * 칸마다 고를 수 있는 값을 인쇄된 선택 칸으로 늘어놓고, 고른 칸은 도장 잉크로 채운다. 바꾸면 바로 저장되고 다음 발권부터 적용된다.
 */
@Composable
private fun SettingsScreen(
    state: TripUiState,
    onSettings: ((UserSettings) -> UserSettings) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier
) {
    val c = Ticket.colors
    Column(modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.displaySmall, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
            TicketButton(stringResource(R.string.settings_done), onDone, Modifier.width(240.dp), minHeight = 72.dp)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { TripSettings(state, onSettings) }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { AppSettings(state, onSettings) }
        }
    }
}

/** 충전 계산에 쓰는 설정: 차종, 충전 상한, 안전 여유, 목적지 도착 잔량. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TripSettings(state: TripUiState, onSettings: ((UserSettings) -> UserSettings) -> Unit) {
    val st = state.settings
    HairlineH()
    if (state.profiles.isNotEmpty()) {
        SettingRow(stringResource(R.string.settings_vehicle), null) {
            state.profiles.forEachIndexed { i, p ->
                val selected = st.profileId == p.id || (st.profileId == null && i == 0)
                ChoiceCell(p.name, selected) { onSettings { s -> s.copy(profileId = p.id, onboarded = true) } }
            }
        }
    }
    SettingRow(stringResource(R.string.settings_target), stringResource(R.string.settings_target_hint)) {
        UserSettings.TARGET_CHOICES.forEach { t ->
            ChoiceCell("${t.roundToInt()}%", st.maxTargetSocPercent == t) { onSettings { s -> s.copy(maxTargetSocPercent = t) } }
        }
    }
    SettingRow(stringResource(R.string.settings_margin), stringResource(R.string.settings_margin_hint)) {
        UserSettings.MARGIN_CHOICES.forEach { m ->
            ChoiceCell("$m%", st.safetyMarginPercent == m) { onSettings { s -> s.copy(safetyMarginPercent = m) } }
        }
    }
    SettingRow(stringResource(R.string.settings_destination), stringResource(R.string.settings_destination_hint)) {
        UserSettings.DESTINATION_CHOICES.forEach { d ->
            ChoiceCell("$d%", st.destinationSocPercent == d) { onSettings { s -> s.copy(destinationSocPercent = d) } }
        }
    }
}

/** 앱 동작 설정: 충전소 범위, 배터리 값 출처, 음성 명령. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppSettings(state: TripUiState, onSettings: ((UserSettings) -> UserSettings) -> Unit) {
    val st = state.settings
    HairlineH()
    SettingRow(stringResource(R.string.settings_voice), stringResource(R.string.settings_voice_hint)) {
        ChoiceCell(stringResource(R.string.settings_on), st.voiceCommands) { onSettings { s -> s.copy(voiceCommands = true) } }
        ChoiceCell(stringResource(R.string.settings_off), !st.voiceCommands) { onSettings { s -> s.copy(voiceCommands = false) } }
    }
    SettingRow(stringResource(R.string.settings_stations), stringResource(R.string.settings_stations_hint)) {
        ChoiceCell(stringResource(R.string.settings_stations_all), st.includeNonRoadside) { onSettings { s -> s.copy(includeNonRoadside = true) } }
        ChoiceCell(stringResource(R.string.settings_stations_rest_only), !st.includeNonRoadside) { onSettings { s -> s.copy(includeNonRoadside = false) } }
    }
    SettingRow(stringResource(R.string.settings_soc_source), stringResource(R.string.settings_soc_source_hint)) {
        ChoiceCell(stringResource(R.string.settings_soc_vehicle), !st.manualSoc) { onSettings { s -> s.copy(manualSoc = false) } }
        ChoiceCell(stringResource(R.string.settings_soc_manual), st.manualSoc) { onSettings { s -> s.copy(manualSoc = true) } }
    }
}

// 설정 한 줄: 칸 이름(+ 짧은 설명)과 선택 칸들. 아래에 괘선.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingRow(label: String, hint: String?, choices: @Composable () -> Unit) {
    val c = Ticket.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelMedium, color = c.inkMuted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { choices() }
    }
    HairlineH()
}

// 선택 칸 하나. 고른 칸은 도장 잉크로 채우고, 나머지는 괘선 테두리. 손가락으로 누르기 쉽게 48dp 이상.
@Composable
private fun ChoiceCell(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Ticket.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .background(if (selected) c.stamp else androidx.compose.ui.graphics.Color.Transparent, shape)
            .border(1.5.dp, if (selected) c.stamp else c.inkMuted, shape)   // 괘선색(1.5:1)은 조작 칸 테두리로 너무 흐려 보조 잉크(5.8:1 이상)를 쓴다
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = if (selected) c.onStamp else c.ink, maxLines = 1)
    }
}

// 시나리오 하나를 표 한 장으로 그린다: 이름, 거리, 출발 배터리, 출발 시각, [발권] 버튼
@Composable
private fun IssueTicket(option: ScenarioOption, onStart: (String) -> Unit) {
    val c = Ticket.colors
    TicketStock(Modifier.fillMaxWidth(), perforationX = 180.dp, perforationFromEnd = true) {
        Row(Modifier.fillMaxWidth().padding(start = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 22.dp)) {
                Text(option.title, style = MaterialTheme.typography.headlineMedium, color = c.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    MeasureField(stringResource(R.string.label_distance), "${option.distanceKm.roundToInt()}", stringResource(R.string.unit_km), FigureStyle.Medium)
                    MeasureField(stringResource(R.string.label_start_soc), "${option.startSoc.roundToInt()}", stringResource(R.string.unit_percent), FigureStyle.Medium)
                    MeasureField(stringResource(R.string.label_depart), option.departAt, null, FigureStyle.Medium)
                }
            }
            TicketButton(
                stringResource(R.string.idle_issue),
                { onStart(option.id) },
                Modifier.padding(20.dp).width(140.dp),
                minHeight = 88.dp
            )
        }
    }
}

// ---- 충전소 정차 ----

/**
 * 충전소 정차 화면의 공통 틀. 왼쪽 큰 표에는 단계별 본문([body])을,
 * 오른쪽 작은 표에는 경로 띠, 후보 비교 표, 지금까지의 여행 기록을 보여 준다.
 */
@Composable
private fun StopScreen(state: TripUiState, stop: StopInfo, modifier: Modifier, body: @Composable ColumnScope.() -> Unit) {
    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        TicketStock(Modifier.weight(1.45f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 32.dp, bottom = 20.dp), content = body)
        }
        TicketStock(Modifier.weight(1f).fillMaxHeight(), perforationX = 0.dp) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp)) {
                RouteStrip(state.routeMarks, state.progress)
                Spacer(Modifier.height(24.dp))
                if (state.candidates.isNotEmpty()) CandidateTable(state.candidates)
                Spacer(Modifier.height(28.dp))
                TripLines(state)
            }
        }
    }
}

/** 지금까지 이 표에 인쇄된 내용: 표 번호, 발권, 거쳐 온 충전. */
@Composable
private fun TripLines(state: TripUiState) {
    val c = Ticket.colors
    Text(stringResource(R.string.label_trip), style = MaterialTheme.typography.titleMedium, color = c.ink)
    Spacer(Modifier.height(4.dp))
    ReceiptLine(stringResource(R.string.label_ticket_no), state.ticketNo)
    state.receipt?.let { r ->
        ReceiptLine(stringResource(R.string.label_issued), stringResource(R.string.receipt_soc_value, r.departAt, r.startSoc.roundToInt()))
        r.charges.forEach {
            ReceiptLine("${stringResource(R.string.receipt_charge)} · ${it.stationName}", chargeValue(it))
        }
        r.declinedNames.forEach { ReceiptLine("${stringResource(R.string.receipt_declined)} · $it", "—", muted = true) }
    }
}

/**
 * 충전소 도착: 지금 %, 목표 %, 예상 충전 시간, 시간 제한, 예상 요금. [충전 시작] 또는 [충전 없이 출발].
 * 배터리 %를 직접 넣는 중이면 지금 % 아래에 계기판 값에 맞춰 고치는 버튼을 둔다.
 */
@Composable
private fun ColumnScope.ArrivedBody(stop: StopInfo, onStartCharging: () -> Unit, onDepart: () -> Unit, onAdjust: (Int) -> Unit, onCorrect: (Int) -> Unit) {
    val c = Ticket.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(stop.stationName, style = MaterialTheme.typography.displayMedium, color = c.ink)
        Stamp(stringResource(R.string.stamp_arrived))
    }
    // 배터리를 직접 넣는 중이면 고치는 버튼 줄이 더 들어가므로 여백을 줄여 아래 버튼이 눌리지 않게 한다.
    val gap = if (stop.manualSoc) 12.dp else 20.dp
    Spacer(Modifier.height(if (stop.manualSoc) 12.dp else 28.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            MeasureField(stringResource(R.string.label_now), "${stop.currentSoc.roundToInt()}", stringResource(R.string.unit_percent), FigureStyle.Large)
        }
        Column(Modifier.weight(1f)) {
            MeasureField(stringResource(R.string.label_target), "${stop.targetSoc.roundToInt()}", stringResource(R.string.unit_percent), FigureStyle.Large, color = c.stamp)
            Spacer(Modifier.height(8.dp))
            TargetStepper(stop, onAdjust)
        }
    }
    // 배터리를 직접 넣는 중이면 고치는 버튼을 표 너비 전체에 한 줄로 둔다(반 칸에 넣으면 글자가 쪼개진다).
    if (stop.manualSoc) {
        Spacer(Modifier.height(12.dp))
        SocCorrector(onCorrect)
    }
    Spacer(Modifier.height(gap))
    HairlineH()
    Spacer(Modifier.height(gap))
    Row(Modifier.fillMaxWidth()) {
        MeasureField(stringResource(R.string.label_estimate), "${stop.minutesToTarget}", stringResource(R.string.unit_min), FigureStyle.Medium, Modifier.weight(1f))
        LimitField(stop.timeLimitMin, stop.timeLimitKnown, Modifier.weight(1f))
        CostField(stringResource(R.string.label_cost), stop.costKrw, Modifier.weight(1f))
    }
    if (stop.coldBattery) {
        Spacer(Modifier.height(12.dp))
        LimitWarnLine(stringResource(R.string.cold_note))
    }
    if (stop.targetInTaper) {
        Spacer(Modifier.height(12.dp))
        LimitWarnLine(stringResource(R.string.taper_note))
    }
    stop.nextStopName?.let { name ->
        Spacer(Modifier.height(12.dp))
        NextStopLine(name, stop.nextStopTarget)
    }
    Spacer(Modifier.weight(1f))
    TearLine(Modifier.padding(vertical = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TicketButton(stringResource(R.string.parked_start_charging), onStartCharging, Modifier.weight(2f), minHeight = 96.dp)
        TicketButton(stringResource(R.string.parked_skip_charging), onDepart, Modifier.weight(1f), primary = false, minHeight = 96.dp)
    }
}

// 요금 칸. "약 3,800원"처럼 100원 단위로 보여 주고, 요금을 모르면 "정보 없음".
@Composable
private fun CostField(label: String, krw: Int?, modifier: Modifier) {
    val c = Ticket.colors
    if (krw != null) {
        MeasureField(label, wonText(krw), stringResource(R.string.unit_won), FigureStyle.Medium, modifier)
    } else {
        Field(label, modifier) {
            Text(stringResource(R.string.unknown), style = MaterialTheme.typography.headlineMedium, color = c.inkMuted)
        }
    }
}

/**
 * 배터리 % 직접 고치기: [맞음] [−5] [−1] [+1] [+5]. 누를 때마다 바로 반영된다(정차 중에만 보인다).
 * [맞음]은 값을 바꾸지 않고 계기판과 같다고 확인한다. 확인한 값도 전비 보정에 쓰인다.
 * 차량에서 배터리 %를 받을 수 없을 때, 계기판 값을 보고 앱의 추정값을 맞춘다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SocCorrector(onCorrect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Ticket.colors
    Column(modifier) {
        Text(stringResource(R.string.manual_soc_title), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        Spacer(Modifier.height(6.dp))
        // 좁은 칸에서는 두 줄로 접힌다(버튼이 칸 밖으로 밀려 눌리지 않는 일이 없게).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val okDesc = stringResource(R.string.manual_soc_ok_desc)
            TicketButton(
                stringResource(R.string.manual_soc_ok), { onCorrect(0) },
                Modifier.width(120.dp).semantics { contentDescription = okDesc },
                primary = false, stampInk = true, minHeight = 56.dp
            )
            listOf(-5, -1, 1, 5).forEach { d ->
                val desc = if (d < 0) stringResource(R.string.manual_soc_desc_minus, -d) else stringResource(R.string.manual_soc_desc_plus, d)
                TicketButton(
                    if (d < 0) "−${-d}" else "+$d", { onCorrect(d) },
                    Modifier.width(104.dp).semantics { contentDescription = desc },
                    primary = false, minHeight = 56.dp
                )
            }
        }
    }
}

// 시간 제한 칸. 제한이 없다고 확인했으면 "없음", 확인하지 못했으면 "정보 없음"으로 표시한다.
@Composable
private fun LimitField(limit: Int?, known: Boolean, modifier: Modifier) {
    val c = Ticket.colors
    if (limit != null) {
        MeasureField(stringResource(R.string.label_limit), "$limit", stringResource(R.string.unit_min), FigureStyle.Medium, modifier)
    } else {
        Field(stringResource(R.string.label_limit), modifier) {
            Text(stringResource(if (known) R.string.value_none else R.string.unknown), style = MaterialTheme.typography.headlineMedium, color = c.inkMuted)
        }
    }
}

/**
 * 충전 중: 지금 SoC 하나가 화면의 주인. 5% 단위 칸 막대, 동승자를 위한 출발 예정 시각.
 * F2: 목표 옆 [−5%][+5%]로 목표를 바꾸고, 아래에 이 충전기·차종의 출력 곡선과 지금 들어오는 kW를 보여 준다.
 */
@Composable
private fun ColumnScope.ChargingBody(stop: StopInfo, onStop: () -> Unit, onAdjust: (Int) -> Unit, onCorrect: (Int) -> Unit) {
    val c = Ticket.colors
    Text(stringResource(R.string.charging_title, stop.stationName), style = MaterialTheme.typography.headlineMedium, color = c.ink)
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Figure("${stop.currentSoc.roundToInt()}", stringResource(R.string.unit_percent), FigureStyle.Monumental)
        Figure("→ ${stop.targetSoc.roundToInt()}", stringResource(R.string.unit_percent), FigureStyle.Medium, color = c.stamp, modifier = Modifier.padding(bottom = 14.dp))
        Spacer(Modifier.weight(1f))
        TargetStepper(stop, onAdjust, Modifier.padding(bottom = 14.dp))
    }
    Spacer(Modifier.height(12.dp))
    CellGauge(stop.currentSoc, stop.targetSoc)
    if (stop.manualSoc) {
        Spacer(Modifier.height(12.dp))
        SocCorrector(onCorrect)
    }
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth()) {
        MeasureField(stringResource(R.string.label_to_target), "${stop.minutesToTarget}", stringResource(R.string.unit_min), FigureStyle.Medium, Modifier.weight(1f))
        HairlineV(Modifier.height(84.dp).padding(end = 24.dp))
        if (stop.minutesToLimit != null) {
            // 제한 안에 목표를 못 채우고 10분 이내로 남았으면 경고색으로 바꾼다.
            val limitNear = stop.willHitLimit && stop.minutesToLimit <= 10
            MeasureField(
                stringResource(R.string.label_to_limit),
                "${stop.minutesToLimit}",
                stringResource(R.string.unit_min),
                FigureStyle.Medium,
                Modifier.weight(1f),
                color = if (limitNear) c.warn else c.ink
            )
        } else {
            LimitField(null, stop.timeLimitKnown, Modifier.weight(1f))
        }
        HairlineV(Modifier.height(84.dp).padding(end = 24.dp))
        MeasureField(stringResource(R.string.label_depart_at), stop.departAt, null, FigureStyle.Large.copy(fontSize = FigureStyle.Medium.fontSize * 1.3f), Modifier.weight(1.3f))
    }
    // 경고(제한 미달 예상, 80% 넘는 목표)는 곡선 머리 줄에 함께 둬서 곡선이 납작해지지 않게 한다.
    val warning = when {
        stop.willHitLimit && stop.predictedSocAtLimit != null ->
            stringResource(R.string.charging_limit_forecast, stop.predictedSocAtLimit.roundToInt())
        stop.targetInTaper -> stringResource(R.string.taper_note)
        stop.coldBattery -> stringResource(R.string.cold_note)
        else -> null
    }
    Spacer(Modifier.height(16.dp))
    PowerCurve(stop, warning, Modifier.weight(1f).fillMaxWidth())
    TearLine(Modifier.padding(vertical = 8.dp))
    TicketButton(stringResource(R.string.parked_stop_charging), onStop, Modifier.fillMaxWidth(), primary = false, minHeight = 88.dp)
}

/** F2 목표 조정: [−5%] [+5%]. 지금 %보다 낮게, 100%보다 높게는 못 바꾼다. */
@Composable
private fun TargetStepper(stop: StopInfo, onAdjust: (Int) -> Unit, modifier: Modifier = Modifier) {
    val minusDesc = stringResource(R.string.target_minus_desc)
    val plusDesc = stringResource(R.string.target_plus_desc)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TicketButton(
            stringResource(R.string.target_minus), { onAdjust(-STEP) },
            Modifier.width(136.dp).semantics { contentDescription = minusDesc },
            primary = false, minHeight = 60.dp, enabled = stop.canLowerTarget
        )
        TicketButton(
            stringResource(R.string.target_plus), { onAdjust(STEP) },
            Modifier.width(136.dp).semantics { contentDescription = plusDesc },
            primary = false, minHeight = 60.dp, enabled = stop.canRaiseTarget
        )
    }
}

/**
 * F2 출력 곡선: 가로는 SoC 0~100%, 세로는 출력(kW). 이번에 채울 구간(지금~목표)을 도장 잉크로 옅게 칠하고,
 * 지금 위치에 세로 잉크 선, 목표에 점선을 긋는다. 80%를 넘으면 출력이 뚝 떨어지는 모습이 그대로 보인다.
 */
@Composable
private fun PowerCurve(stop: StopInfo, warning: String?, modifier: Modifier) {
    val c = Ticket.colors
    val curve = stop.powerCurve
    if (curve.isEmpty()) return
    val nowKw = stop.chargePowerKw ?: curve.minBy { kotlin.math.abs(it.socPercent - stop.currentSoc.toFloat()) }.kw.toDouble()
    val desc = stringResource(R.string.curve_desc, stop.currentSoc.roundToInt(), nowKw.roundToInt(), stop.targetSoc.roundToInt())
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (warning != null) {
                Box(Modifier.weight(1f)) { LimitWarnLine(warning) }
            } else {
                Text(stringResource(R.string.curve_title), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
                Spacer(Modifier.weight(1f))
            }
            Text(stringResource(R.string.label_power), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            Figure("${nowKw.roundToInt()}", stringResource(R.string.unit_kw), FigureStyle.Small, unitStyle = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(6.dp))
        val maxKw = curve.maxOf { it.kw }.coerceAtLeast(1f)
        Canvas(Modifier.fillMaxWidth().weight(1f).heightIn(min = 48.dp).semantics { contentDescription = desc }) {
            val w = size.width
            val h = size.height
            fun x(soc: Float) = soc / 100f * w
            fun y(kw: Float) = h - kw / maxKw * h * 0.92f
            // 이번에 채울 구간 면
            val from = stop.currentSoc.toFloat()
            val to = stop.targetSoc.toFloat()
            val band = androidx.compose.ui.graphics.Path().apply {
                moveTo(x(from), h)
                curve.filter { it.socPercent in from..to }.forEach { lineTo(x(it.socPercent), y(it.kw)) }
                lineTo(x(to), h)
                close()
            }
            drawPath(band, c.stamp.copy(alpha = 0.22f))
            // 곡선
            val line = androidx.compose.ui.graphics.Path().apply {
                curve.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.socPercent), y(p.kw)) else lineTo(x(p.socPercent), y(p.kw)) }
            }
            drawPath(line, c.inkMuted, style = Stroke(2.dp.toPx()))
            drawLine(c.rule, Offset(0f, h), Offset(w, h), strokeWidth = 1.dp.toPx())
            // 목표(점선)와 지금(실선)
            drawLine(c.stamp, Offset(x(to), 0f), Offset(x(to), h), strokeWidth = 2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
            drawLine(c.ink, Offset(x(from), 0f), Offset(x(from), h), strokeWidth = 2.dp.toPx())
        }
    }
}

// 두 번째 충전 계획 안내 한 줄(B2): 도장 잉크 점 + 글자
@Composable
private fun NextStopLine(name: String, target: Double?) {
    val c = Ticket.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).background(c.stamp, androidx.compose.foundation.shape.CircleShape))
        Text(stringResource(R.string.next_stop_note, name, (target ?: 0.0).roundToInt()), style = MaterialTheme.typography.titleMedium, color = c.ink)
    }
}

/** 20칸 막대: 현재까지 잉크로 채우고, 목표까지는 도장 잉크 테두리로 남긴다. */
@Composable
private fun CellGauge(current: Double, target: Double) {
    val c = Ticket.colors
    val desc = "${current.roundToInt()}% / ${target.roundToInt()}%"
    Canvas(Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = desc }) {
        // 한 칸 = 5%. 칸마다 네 가지 중 하나로 그린다:
        // 다 찬 칸(잉크), 일부 찬 칸(부분 채움), 목표까지 채울 칸(강조색 테두리), 목표 밖 칸(흐린 테두리)
        val cells = 20
        val gap = 6.dp.toPx()
        val w = (size.width - gap * (cells - 1)) / cells
        for (i in 0 until cells) {
            val from = i * 5.0
            val x = i * (w + gap)
            val topLeft = Offset(x, 0f)
            val cellSize = Size(w, size.height)
            val radius = CornerRadius(3.dp.toPx())
            when {
                current >= from + 5 -> drawRoundRect(c.ink, topLeft, cellSize, radius)
                current > from -> {
                    drawRoundRect(c.rule, topLeft, cellSize, radius)
                    drawRoundRect(c.ink, topLeft, Size(w * ((current - from) / 5.0).toFloat(), size.height), radius)
                }
                target > from -> drawRoundRect(c.stamp, topLeft, cellSize, radius, style = Stroke(2.dp.toPx()))
                else -> drawRoundRect(c.rule, topLeft, cellSize, radius, style = Stroke(1.dp.toPx()))
            }
        }
    }
}

/**
 * 충전 완료: 최종 %와 도장. 목표에 도달했으면 "충전 완료" 도장,
 * 시간 제한으로 끊겼으면 호박색 "제한 종료" 도장과 안내 문구를 보여 준다. 이번 충전 요금도 함께 찍는다.
 */
@Composable
private fun ColumnScope.DoneBody(stop: StopInfo, soc: Double, last: ChargeRecord?, onDepart: () -> Unit, onCorrect: (Int) -> Unit) {
    val c = Ticket.colors
    Text(stop.stationName, style = MaterialTheme.typography.headlineMedium, color = c.ink)
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Figure("${soc.roundToInt()}", stringResource(R.string.unit_percent), FigureStyle.Monumental)
        if (stop.endedByLimit) {
            Stamp(stringResource(R.string.stamp_limit), color = c.warn)
        } else {
            Stamp(stringResource(R.string.stamp_charged))
        }
        Spacer(Modifier.weight(1f))
        if (last != null) CostField(stringResource(R.string.label_charge_cost), last.costKrw, Modifier)
    }
    Spacer(Modifier.height(12.dp))
    CellGauge(soc, stop.targetSoc)
    if (stop.manualSoc) {
        Spacer(Modifier.height(12.dp))
        SocCorrector(onCorrect)
    }
    if (stop.endedByLimit) {
        Spacer(Modifier.height(16.dp))
        LimitWarnLine(stringResource(R.string.done_limit_note, stop.targetSoc.roundToInt(), soc.roundToInt()))
    } else {
        stop.nextStopName?.let { name ->
            Spacer(Modifier.height(16.dp))
            NextStopLine(name, stop.nextStopTarget)
        }
    }
    Spacer(Modifier.weight(1f))
    TearLine(Modifier.padding(vertical = 8.dp))
    TicketButton(stringResource(R.string.parked_depart), onDepart, Modifier.fillMaxWidth(), minHeight = 104.dp)
}

// 시간 제한 관련 경고 한 줄(경고 기호 + 호박색 글자)
@Composable
private fun LimitWarnLine(text: String) {
    val c = Ticket.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WarnGlyph(size = 26.dp)
        Text(text, style = MaterialTheme.typography.titleMedium, color = c.warn)
    }
}

/** 충전 기록 한 줄의 값. 충전기 시간 제한으로 끝났으면 "· 제한"을, 요금을 알면 "· 3,800원"을 붙인다. */
@Composable
private fun chargeValue(r: ChargeRecord): String {
    val base = stringResource(R.string.receipt_charge_value, r.fromSoc.roundToInt(), r.toSoc.roundToInt(), r.minutes)
    val limited = if (r.endedByLimit) stringResource(R.string.receipt_limit_suffix, base) else base
    return r.costKrw?.let { stringResource(R.string.receipt_charge_cost_value, limited, wonText(it)) } ?: limited
}

// ---- 정산 영수증 ----

/** 목적지 도착 후 화면. 왼쪽에 요약(충전 횟수, 최종 배터리)과 [다시 발권], 오른쪽에 영수증. */
@Composable
private fun ReceiptScreen(state: TripUiState, onRestart: () -> Unit, modifier: Modifier) {
    val c = Ticket.colors
    val receipt = state.receipt ?: return
    Row(modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.finished_title), style = MaterialTheme.typography.displayMedium, color = c.ink)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                MeasureField(stringResource(R.string.finished_charges), "${receipt.charges.size}", stringResource(R.string.unit_times), FigureStyle.Medium)
                MeasureField(stringResource(R.string.finished_charge_total), "${receipt.chargeMinutesTotal}", stringResource(R.string.unit_min), FigureStyle.Medium)
                MeasureField(stringResource(R.string.finished_battery), "${(receipt.endSoc ?: state.socPercent).roundToInt()}", stringResource(R.string.unit_percent), FigureStyle.Medium)
                receipt.costKrwTotal?.let { MeasureField(stringResource(R.string.finished_cost), wonText(it), stringResource(R.string.unit_won), FigureStyle.Medium) }
            }
            // F4: 필요한 만큼만 충전해서 아낀 시간. 이 앱이 해 준 일을 숫자 하나로 보여 준다.
            if (receipt.charges.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                HairlineH()
                Spacer(Modifier.height(20.dp))
                if (receipt.savedMinutes > 0) {
                    MeasureField(stringResource(R.string.finished_saved), "${receipt.savedMinutes}", stringResource(R.string.unit_min), FigureStyle.Large, color = c.stamp)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.finished_saved_note, receipt.savedMinutes), style = MaterialTheme.typography.titleMedium, color = c.inkMuted)
                } else {
                    Text(stringResource(R.string.finished_saved_none), style = MaterialTheme.typography.titleMedium, color = c.inkMuted)
                }
            }
            Spacer(Modifier.height(36.dp))
            TicketButton(stringResource(R.string.receipt_again), onRestart, Modifier.width(320.dp), minHeight = 96.dp)
        }
        TicketStock(Modifier.width(520.dp).fillMaxHeight()) {
            Box(Modifier.fillMaxSize()) {
                ReceiptBody(receipt, state.scenarioTitle)
                Stamp(
                    stringResource(R.string.stamp_settled),
                    Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 40.dp)
                )
            }
        }
    }
}

// 영수증 본문: 출발 → 충전 내역 → 거절한 충전소 → 도착 → 총 거리 순서로 한 줄씩 찍는다.
@Composable
private fun ReceiptBody(receipt: TripReceipt, title: String) {
    val c = Ticket.colors
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 36.dp, vertical = 28.dp)) {
        Text(stringResource(R.string.receipt_title), style = MaterialTheme.typography.titleLarge, color = c.ink)
        Text(title, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        Spacer(Modifier.height(16.dp))
        TearLine()
        ReceiptLine(stringResource(R.string.receipt_depart), stringResource(R.string.receipt_soc_value, receipt.departAt, receipt.startSoc.roundToInt()))
        receipt.charges.forEach {
            ReceiptLine(
                "${stringResource(R.string.receipt_charge)} · ${it.stationName}",
                chargeValue(it)
            )
        }
        receipt.declinedNames.forEach {
            ReceiptLine("${stringResource(R.string.receipt_declined)} · $it", "—", muted = true)
        }
        ReceiptLine(
            stringResource(R.string.receipt_arrive),
            stringResource(R.string.receipt_soc_value, receipt.arriveAt ?: "—", (receipt.endSoc ?: 0.0).roundToInt())
        )
        Spacer(Modifier.height(12.dp))
        HairlineH()
        Spacer(Modifier.height(3.dp))
        HairlineH()
        ReceiptLine(stringResource(R.string.receipt_total), "${receipt.distanceKm.roundToInt()} km", strong = true)
        receipt.costKrwTotal?.let { total ->
            val text = stringResource(R.string.cost_value, wonText(total))
            ReceiptLine(
                stringResource(R.string.receipt_cost_total),
                if (receipt.costPartlyUnknown) stringResource(R.string.receipt_cost_partial, text) else text,
                strong = true
            )
        }
        if (receipt.savedMinutes > 0) {
            ReceiptLine(stringResource(R.string.receipt_saved), stringResource(R.string.receipt_saved_value, receipt.savedMinutes), strong = true)
        }
    }
}

/** 영수증 한 줄: 왼쪽 항목, 점선 리더, 오른쪽 값. */
@Composable
private fun ReceiptLine(label: String, value: String, muted: Boolean = false, strong: Boolean = false) {
    val c = Ticket.colors
    val color = if (muted) c.inkMuted else c.ink
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(label, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, color = color)
        Canvas(Modifier.weight(1f).height(2.dp).padding(horizontal = 10.dp)) {
            drawLine(c.rule, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3f, 7f)))
        }
        Text(value, style = FigureStyle.Small.copy(fontWeight = if (strong) androidx.compose.ui.text.font.FontWeight.Bold else FigureStyle.Small.fontWeight), color = color)
    }
}

// F2 목표 조정 단위(%)
private const val STEP = 5
