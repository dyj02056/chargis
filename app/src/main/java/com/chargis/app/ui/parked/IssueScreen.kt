package com.chargis.app.ui.parked

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chargis.app.R
import com.chargis.app.domain.model.UserSettings
import com.chargis.app.ui.MeasureField
import com.chargis.app.ui.ScenarioOption

import com.chargis.app.ui.TripUiState
import com.chargis.app.ui.theme.FigureStyle
import com.chargis.app.ui.theme.Ticket
import com.chargis.app.ui.ticket.HairlineH
import com.chargis.app.ui.ticket.TicketButton
import com.chargis.app.ui.ticket.TicketStock
import kotlin.math.roundToInt

// 발권 화면과 내 차 설정 화면(ParkedScreen.kt에서 분리). 스크린샷: 01-home.png

/**
 * 첫 화면. 왼쪽에 앱 이름과 소개, 그 아래 지금 설정 요약과 [설정] 버튼, 오른쪽에 고를 수 있는 시나리오 표들.
 * 설정 칸이 많아 첫 화면에 다 늘어놓지 않고 [설정]을 누르면 따로 연다([SettingsScreen]).
 * 처음 실행이면 설정 안내 문구를 보여 주고, 발권하면 설정을 마친 것으로 기록한다.
 */
@Composable
internal fun IssueScreen(
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
