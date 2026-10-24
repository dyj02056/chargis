package com.chargis.app.ui.parked

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import com.chargis.app.domain.session.TripPhase
import com.chargis.app.ui.CandidateTable
import com.chargis.app.ui.MeasureField
import com.chargis.app.ui.ScenarioOption
import com.chargis.app.ui.StopInfo
import com.chargis.app.ui.TripReceipt
import com.chargis.app.ui.TripUiState
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
import kotlin.math.roundToInt

/** 정차·충전 중 화면(plan.md §9.3). 상세와 스크롤을 허용한다. */
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
    val stop = state.stop
    when {
        state.phase == TripPhase.Idle -> IssueScreen(state.scenarios, onStartScenario, modifier)
        state.phase == TripPhase.Finished && state.receipt != null -> ReceiptScreen(state, onRestart, modifier)
        stop != null && state.phase == TripPhase.Arrived -> StopScreen(state, stop, modifier) { ArrivedBody(stop, onStartCharging, onDepart) }
        stop != null && state.phase == TripPhase.Charging -> StopScreen(state, stop, modifier) { ChargingBody(stop, onStopCharging) }
        stop != null && state.phase == TripPhase.Done -> StopScreen(state, stop, modifier) { DoneBody(stop, state.socPercent, onDepart) }
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

@Composable
private fun IssueScreen(options: List<ScenarioOption>, onStart: (String) -> Unit, modifier: Modifier) {
    val c = Ticket.colors
    Row(modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        Column(Modifier.weight(0.75f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium.copy(fontSize = MaterialTheme.typography.displayMedium.fontSize * 1.6f, lineHeight = MaterialTheme.typography.displayMedium.lineHeight * 1.5f), color = c.ink)
            Text(stringResource(R.string.ticket_kind), style = MaterialTheme.typography.headlineMedium, color = c.ink)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.idle_lede), style = MaterialTheme.typography.bodyLarge, color = c.inkMuted, modifier = Modifier.widthIn(max = 460.dp))
        }
        Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
            options.forEach { IssueTicket(it, onStart) }
        }
    }
}

@Composable
private fun IssueTicket(option: ScenarioOption, onStart: (String) -> Unit) {
    val c = Ticket.colors
    TicketStock(Modifier.fillMaxWidth(), perforationX = 180.dp, perforationFromEnd = true) {
        Row(Modifier.fillMaxWidth().padding(start = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 32.dp)) {
                Text(option.title, style = MaterialTheme.typography.headlineMedium, color = c.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(18.dp))
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

// ---- 휴게소 정차 ----

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
            ReceiptLine("${stringResource(R.string.receipt_charge)} · ${it.stationName}", stringResource(R.string.receipt_charge_value, it.fromSoc.roundToInt(), it.toSoc.roundToInt(), it.minutes))
        }
        r.declinedNames.forEach { ReceiptLine("${stringResource(R.string.receipt_declined)} · $it", "—", muted = true) }
    }
}

@Composable
private fun ColumnScope.ArrivedBody(stop: StopInfo, onStartCharging: () -> Unit, onDepart: () -> Unit) {
    val c = Ticket.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(stop.stationName, style = MaterialTheme.typography.displayMedium, color = c.ink)
        Stamp(stringResource(R.string.stamp_arrived))
    }
    Spacer(Modifier.height(28.dp))
    Row(Modifier.fillMaxWidth()) {
        MeasureField(stringResource(R.string.label_now), "${stop.currentSoc.roundToInt()}", stringResource(R.string.unit_percent), FigureStyle.Large, Modifier.weight(1f))
        MeasureField(stringResource(R.string.label_target), "${stop.targetSoc.roundToInt()}", stringResource(R.string.unit_percent), FigureStyle.Large, Modifier.weight(1f), color = c.stamp)
    }
    Spacer(Modifier.height(20.dp))
    HairlineH()
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth()) {
        MeasureField(stringResource(R.string.label_estimate), "${stop.minutesToTarget}", stringResource(R.string.unit_min), FigureStyle.Medium, Modifier.weight(1f))
        LimitField(stop.timeLimitMin, Modifier.weight(1f))
    }
    Spacer(Modifier.weight(1f))
    TearLine(Modifier.padding(vertical = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TicketButton(stringResource(R.string.parked_start_charging), onStartCharging, Modifier.weight(2f), minHeight = 96.dp)
        TicketButton(stringResource(R.string.parked_skip_charging), onDepart, Modifier.weight(1f), primary = false, minHeight = 96.dp)
    }
}

@Composable
private fun LimitField(limit: Int?, modifier: Modifier) {
    val c = Ticket.colors
    if (limit != null) {
        MeasureField(stringResource(R.string.label_limit), "$limit", stringResource(R.string.unit_min), FigureStyle.Medium, modifier)
    } else {
        Field(stringResource(R.string.label_limit), modifier) {
            Text(stringResource(R.string.unknown), style = MaterialTheme.typography.headlineMedium, color = c.inkMuted)
        }
    }
}

/** 충전 중: 지금 SoC 하나가 화면의 주인. 5% 단위 칸 막대, 동승자를 위한 출발 예정 시각. */
@Composable
private fun ColumnScope.ChargingBody(stop: StopInfo, onStop: () -> Unit) {
    val c = Ticket.colors
    Text(stringResource(R.string.charging_title, stop.stationName), style = MaterialTheme.typography.headlineMedium, color = c.ink)
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Figure("${stop.currentSoc.roundToInt()}", stringResource(R.string.unit_percent), FigureStyle.Monumental)
        Figure("→ ${stop.targetSoc.roundToInt()}", stringResource(R.string.unit_percent), FigureStyle.Medium, color = c.stamp, modifier = Modifier.padding(bottom = 14.dp))
    }
    Spacer(Modifier.height(12.dp))
    CellGauge(stop.currentSoc, stop.targetSoc)
    Spacer(Modifier.height(24.dp))
    Row(Modifier.fillMaxWidth()) {
        MeasureField(stringResource(R.string.label_to_target), "${stop.minutesToTarget}", stringResource(R.string.unit_min), FigureStyle.Medium, Modifier.weight(1f))
        HairlineV(Modifier.height(84.dp).padding(end = 24.dp))
        if (stop.minutesToLimit != null) {
            MeasureField(stringResource(R.string.label_to_limit), "${stop.minutesToLimit}", stringResource(R.string.unit_min), FigureStyle.Medium, Modifier.weight(1f))
        } else {
            LimitField(null, Modifier.weight(1f))
        }
        HairlineV(Modifier.height(84.dp).padding(end = 24.dp))
        MeasureField(stringResource(R.string.label_depart_at), stop.departAt, null, FigureStyle.Large.copy(fontSize = FigureStyle.Medium.fontSize * 1.3f), Modifier.weight(1.3f))
    }
    Spacer(Modifier.weight(1f))
    TearLine(Modifier.padding(vertical = 8.dp))
    TicketButton(stringResource(R.string.parked_stop_charging), onStop, Modifier.fillMaxWidth(), primary = false, minHeight = 88.dp)
}

/** 20칸 막대: 현재까지 잉크로 채우고, 목표까지는 도장 잉크 테두리로 남긴다. */
@Composable
private fun CellGauge(current: Double, target: Double) {
    val c = Ticket.colors
    val desc = "${current.roundToInt()}% / ${target.roundToInt()}%"
    Canvas(Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = desc }) {
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

@Composable
private fun ColumnScope.DoneBody(stop: StopInfo, soc: Double, onDepart: () -> Unit) {
    val c = Ticket.colors
    Text(stop.stationName, style = MaterialTheme.typography.headlineMedium, color = c.ink)
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Figure("${soc.roundToInt()}", stringResource(R.string.unit_percent), FigureStyle.Monumental)
        Stamp(stringResource(R.string.stamp_charged))
    }
    Spacer(Modifier.height(12.dp))
    CellGauge(soc, stop.targetSoc)
    Spacer(Modifier.weight(1f))
    TearLine(Modifier.padding(vertical = 8.dp))
    TicketButton(stringResource(R.string.parked_depart), onDepart, Modifier.fillMaxWidth(), minHeight = 104.dp)
}

// ---- 정산 영수증 ----

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
                MeasureField(stringResource(R.string.finished_battery), "${(receipt.endSoc ?: state.socPercent).roundToInt()}", stringResource(R.string.unit_percent), FigureStyle.Medium)
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
                stringResource(R.string.receipt_charge_value, it.fromSoc.roundToInt(), it.toSoc.roundToInt(), it.minutes)
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
