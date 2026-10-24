package com.chargis.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.chargis.app.R
import com.chargis.app.domain.model.DataSource
import com.chargis.app.domain.model.ReasonCode
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.ui.theme.FigureStyle
import com.chargis.app.ui.theme.Ticket
import com.chargis.app.ui.ticket.Figure
import com.chargis.app.ui.ticket.HairlineH
import com.chargis.app.ui.ticket.SpeakerGlyph
import kotlin.math.roundToInt

/** 표 머리: 상표, 구간, 시각, 배터리, 데이터 출처. 주행 중에도 늘 보이는 유일한 상태 줄. */
@Composable
fun TicketHeader(state: TripUiState, modifier: Modifier = Modifier) {
    val c = Ticket.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = c.ink)
        Text(
            if (state.phase == com.chargis.app.domain.session.TripPhase.Idle) "" else state.scenarioTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = c.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (state.phase != com.chargis.app.domain.session.TripPhase.Idle) {
            Text(state.clock, style = FigureStyle.Small, color = c.ink)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.label_battery), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
                Spacer(Modifier.width(8.dp))
                Text("${state.socPercent.roundToInt()}%", style = FigureStyle.Small, color = c.ink)
            }
            Text(sourceLabel(state.dataSource), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        }
    }
}

@Composable
fun sourceLabel(source: DataSource) = stringResource(
    when (source) {
        DataSource.SDK -> R.string.source_sdk
        DataSource.MANUAL -> R.string.source_manual
        DataSource.SIMULATED -> R.string.source_simulated
    }
)

/** 음성 안내 자막 줄. */
@Composable
fun CaptionBar(caption: String?, modifier: Modifier = Modifier) {
    if (caption.isNullOrBlank()) return
    val c = Ticket.colors
    Column(modifier.fillMaxWidth().background(c.stock)) {
        HairlineH()
        Row(
            Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SpeakerGlyph()
            Text(caption, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 세로로 인쇄한 글자(표 꼬리 칸용). */
fun Modifier.vertical() = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth))
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer(
            x = -(placeable.width - placeable.height) / 2,
            y = (placeable.width - placeable.height) / 2
        ) { rotationZ = -90f }
    }
}

@Composable
fun reasonText(rec: Recommendation): String? {
    val parts = rec.reasonCodes.mapNotNull {
        when (it) {
            ReasonCode.LATEST_SAFE_STOP -> R.string.reason_latest_safe
            ReasonCode.MAX_POWER -> R.string.reason_max_power
            ReasonCode.LOW_WAIT -> R.string.reason_low_wait
            ReasonCode.ENOUGH_CHARGERS -> R.string.reason_enough
            ReasonCode.NO_DETOUR, ReasonCode.TIME_LIMIT_RISK -> null
        }
    }.take(2).map { stringResource(it) }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** 괘선으로만 나눈 비교 표. 미상 값은 "정보 없음". */
@Composable
fun CandidateTable(candidates: List<Recommendation>, modifier: Modifier = Modifier) {
    val c = Ticket.colors
    val unknown = stringResource(R.string.unknown)
    Column(modifier) {
        Text(stringResource(R.string.parked_compare), style = MaterialTheme.typography.titleMedium, color = c.ink)
        Spacer(Modifier.height(12.dp))
        TableRow(
            listOf(
                stringResource(R.string.col_station), stringResource(R.string.col_arrival),
                stringResource(R.string.col_power), stringResource(R.string.col_available), stringResource(R.string.col_wait)
            ),
            header = true
        )
        candidates.forEach { r ->
            val s = r.station
            HairlineH()
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Bottom) {
                Text(s.name, style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 1, modifier = Modifier.weight(1.5f))
                NumberCell("${r.arrivalSocPercent.roundToInt()}", "%", Modifier.weight(1f))
                NumberCell("${s.maxKw.roundToInt()}", "kW", Modifier.weight(1f))
                NumberCell(s.availableChargers?.let { "$it/${s.totalChargers}" }, null, Modifier.weight(1f), unknown)
                NumberCell(s.expectedWaitMin?.toString(), stringResource(R.string.unit_min), Modifier.weight(1f), unknown)
            }
        }
        HairlineH()
    }
}

/** 표 칸의 측정값: 숫자는 Geist Mono, 단위는 한글 글꼴. 값이 없으면 "정보 없음". */
@Composable
private fun NumberCell(value: String?, unit: String?, modifier: Modifier, unknown: String = "") {
    val c = Ticket.colors
    Box(modifier) {
        if (value == null) {
            Text(unknown, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, maxLines = 1)
        } else {
            Figure(value, unit, FigureStyle.Small.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize), unitStyle = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false) {
    val c = Ticket.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        cells.forEachIndexed { i, text ->
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = c.inkMuted,
                modifier = Modifier.weight(if (i == 0) 1.5f else 1f),
                maxLines = 1
            )
        }
    }
}

/** 측정값 칸 공용: 칸 이름 + 숫자 + 단위. */
@Composable
fun MeasureField(label: String, value: String, unit: String?, style: TextStyle, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Ticket.colors.ink) {
    com.chargis.app.ui.ticket.Field(label, modifier) { Figure(value, unit, style, color = color) }
}
