package com.chargis.app.ui.parked

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chargis.app.R
import com.chargis.app.ui.ChargeRecord
import com.chargis.app.ui.MeasureField

import com.chargis.app.ui.TripReceipt
import com.chargis.app.ui.TripUiState
import com.chargis.app.ui.wonText
import com.chargis.app.ui.theme.FigureStyle
import com.chargis.app.ui.theme.Ticket
import com.chargis.app.ui.ticket.HairlineH
import com.chargis.app.ui.ticket.Stamp
import com.chargis.app.ui.ticket.TearLine
import com.chargis.app.ui.ticket.TicketButton
import com.chargis.app.ui.ticket.TicketStock
import kotlin.math.roundToInt

// 목적지 도착 후 정산 영수증 화면(ParkedScreen.kt에서 분리). 스크린샷: 09-finished.png, 16-limit-receipt.png

/** 목적지 도착 후 화면. 왼쪽에 요약(충전 횟수, 최종 배터리)과 [다시 발권], 오른쪽에 영수증. */
@Composable
internal fun ReceiptScreen(state: TripUiState, onRestart: () -> Unit, modifier: Modifier) {
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
internal fun ReceiptLine(label: String, value: String, muted: Boolean = false, strong: Boolean = false) {
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

/** 충전 기록 한 줄의 값. 충전기 시간 제한으로 끝났으면 "· 제한"을, 요금을 알면 "· 3,800원"을 붙인다. */
@Composable
internal fun chargeValue(r: ChargeRecord): String {
    val base = stringResource(R.string.receipt_charge_value, r.fromSoc.roundToInt(), r.toSoc.roundToInt(), r.minutes)
    val limited = if (r.endedByLimit) stringResource(R.string.receipt_limit_suffix, base) else base
    return r.costKrw?.let { stringResource(R.string.receipt_charge_cost_value, limited, wonText(it)) } ?: limited
}
