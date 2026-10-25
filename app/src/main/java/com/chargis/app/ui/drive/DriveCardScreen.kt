package com.chargis.app.ui.drive

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chargis.app.R
import com.chargis.app.domain.model.ReasonCode
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.model.WaitSource
import com.chargis.app.ui.DriveCard
import com.chargis.app.ui.RouteMark
import com.chargis.app.ui.EnergyInfo
import com.chargis.app.ui.reasonLabels
import com.chargis.app.ui.stationMeta
import com.chargis.app.ui.waitLabel
import com.chargis.app.ui.theme.FigureStyle
import com.chargis.app.ui.theme.Ticket
import com.chargis.app.ui.ticket.Field
import com.chargis.app.ui.ticket.Figure
import com.chargis.app.ui.ticket.HairlineH
import com.chargis.app.ui.ticket.HairlineV
import com.chargis.app.ui.ticket.RouteStrip
import com.chargis.app.ui.ticket.Stamp
import com.chargis.app.ui.ticket.StampTag
import com.chargis.app.ui.ticket.TearLine
import com.chargis.app.ui.ticket.TicketButton
import com.chargis.app.ui.ticket.TicketStock
import com.chargis.app.ui.ticket.WarnGlyph
import com.chargis.app.ui.ticket.InfoLine
import com.chargis.app.ui.ticket.WarnLine
import com.chargis.app.ui.ticket.rememberReduceMotion
import com.chargis.app.ui.vertical
import kotlin.math.roundToInt

// 주행 중 화면을 그리는 파일이다. 스크린샷: docs/screenshots/02~04, 08, 10, 12번
// 화면 구성: [왼쪽 꼬리 칸(표 번호)] ┊ 절취선 ┊ [본문 카드 1장]

private val StubWidth = 76.dp   // 왼쪽 꼬리 칸의 폭

/** 표 머리에 인쇄되는 정보: 표 번호와 발권 시각. */
data class TicketMeta(val ticketNo: String, val issuedAt: String)

/**
 * 주행 중 화면(plan.md §9.2): 표 한 칸이 화면 전체를 차지한다. 스크롤·입력·목록 없음.
 * 왼쪽 칸은 거리 하나가 주인이고, 오른쪽 칸은 괘선으로 나뉜 인쇄 칸이다.
 * 카드가 바뀔 때만 Material fade-through로 넘어가고, 그 밖에는 움직이지 않는다.
 */
@Composable
fun DriveCardScreen(
    card: DriveCard,
    eta: String,
    remainingKm: Double,
    meta: TicketMeta,
    routeMarks: List<RouteMark>,
    progress: Float,
    onAccept: (String) -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    energy: EnergyInfo? = null,
    onPrecondition: () -> Unit = {}
) {
    val reduce = rememberReduceMotion()
    TicketStock(modifier.fillMaxSize(), perforationX = StubWidth) {
        Row(Modifier.fillMaxSize()) {
            StubTail(meta)
            // 카드 종류가 바뀔 때만 부드럽게 바꿔 끼운다(옛 카드는 빨리 사라지고, 새 카드는 살짝 커지며 나타남).
            AnimatedContent(
                targetState = card,
                contentKey = { it::class },
                transitionSpec = {
                    if (reduce) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        (fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.96f)) togetherWith
                            fadeOut(tween(90))
                    }
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "drive-card"
            ) { target ->
                Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 28.dp, bottom = 20.dp)) {
                    // 카드 종류에 따라 알맞은 그리기 함수를 고른다.
                    when (target) {
                        is DriveCard.Recommend -> RecommendStub(target, onAccept, onDecline)
                        is DriveCard.Congested -> CongestedStub(target, onAccept)
                        is DriveCard.Navigating -> NavigatingStub(target, routeMarks, progress, onPrecondition)
                        is DriveCard.Status -> StatusStub(target, eta, remainingKm, routeMarks, progress, energy)
                        is DriveCard.NoSafeStop -> NoSafeStub(target, routeMarks, progress)
                    }
                }
            }
        }
    }
}

/** 절취선 왼쪽 꼬리 칸: 세로로 인쇄된 표 이름과 표 번호. */
@Composable
private fun StubTail(meta: TicketMeta) {
    val c = Ticket.colors
    Column(
        Modifier.width(StubWidth).fillMaxHeight().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(meta.ticketNo, style = FigureStyle.Small.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize), color = c.ink, maxLines = 1, modifier = Modifier.vertical())
        Text(stringResource(R.string.ticket_kind), style = MaterialTheme.typography.labelMedium, color = c.inkMuted, maxLines = 1, modifier = Modifier.vertical())
        Text(meta.issuedAt, style = FigureStyle.Small.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize), color = c.inkMuted, maxLines = 1, modifier = Modifier.vertical())
    }
}

/** 왼쪽 칸: 기념비 크기 거리, 그 아래 충전소 이름, 그 아래 이유([sub] 글자 또는 [extra] 조각). */
@Composable
private fun LeadColumn(
    km: Int,
    unit: String,
    name: String,
    sub: String?,
    modifier: Modifier,
    sentence: Boolean = false,
    extra: (@Composable () -> Unit)? = null
) {
    val c = Ticket.colors
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
        Figure("$km", unit, FigureStyle.Monumental)
        Spacer(Modifier.height(8.dp))
        // 충전소 이름은 한 줄, 문장(상태 안내)은 작은 크기로 두 줄까지
        Text(
            name,
            style = if (sentence) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium,
            color = c.ink,
            maxLines = if (sentence) 2 else 1,
            overflow = TextOverflow.Ellipsis
        )
        if (sub != null) {
            Spacer(Modifier.height(4.dp))
            Text(sub, style = MaterialTheme.typography.titleMedium, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (extra != null) {
            Spacer(Modifier.height(12.dp))
            extra()
        }
    }
}

/** 괘선 칸 두 개를 나란히: 위·아래 가로 괘선, 가운데 세로 괘선. */
@Composable
private fun CellPair(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Column {
        HairlineH()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.weight(1f).padding(vertical = 16.dp)) { left() }
            HairlineV(Modifier.padding(end = 20.dp))
            Box(Modifier.weight(1f).padding(vertical = 16.dp)) { right() }
        }
    }
}

// 칸 이름 + 중간 크기 숫자. 예) "충전 시간 / 12분"
@Composable
private fun Measure(label: String, value: String, unit: String?, color: androidx.compose.ui.graphics.Color = Ticket.colors.ink) {
    Field(label) { Figure(value, unit, FigureStyle.Medium, color = color) }
}

// 숫자 대신 글자가 들어가는 칸. 예) "시간 제한 / 없음", "대기 / 정보 없음"
@Composable
private fun Words(label: String, text: String) {
    Field(label) { Text(text, style = MaterialTheme.typography.headlineMedium, color = Ticket.colors.inkMuted, maxLines = 1) }
}

/**
 * 추천 카드 (03-recommend.png).
 * 왼쪽: "46 km 앞 / B 휴게소 / (휴게소가 아니면) 종류·왕복 우회 / 이유 도장", 오른쪽: 목표 %, 충전 시간, 대기, 도착 배터리, 시간 제한
 * 맨 아래: [경유지로 수락] [다음 충전소] 버튼
 *
 * F3 정보 위계: 목표 %가 주인이고, 시간 제한은 위험이 없으면 흐리게 두고 위험할 때만 경고색과 기호로 올린다.
 * 이유는 회색 글자 대신 도장 꼬리표로 찍고, 다음 후보보다 빨리 도착하면 그 분을 첫 꼬리표로 둔다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.RecommendStub(card: DriveCard.Recommend, onAccept: (String) -> Unit, onDecline: () -> Unit) {
    val c = Ticket.colors
    val rec = card.rec
    val min = stringResource(R.string.unit_min)
    val limitRisk = ReasonCode.TIME_LIMIT_RISK in rec.reasonCodes && rec.timeLimitMin != null
    val tags = buildList {
        card.fasterThanNextMin?.let { add(stringResource(R.string.drive_faster_than_next, it)) }
        addAll(reasonLabels(rec, max = if (card.fasterThanNextMin != null) 1 else 2).filter {
            // "다음 후보보다 N분 빠름"이 있으면 "가장 빨리 도착"은 같은 말이라 뺀다.
            card.fasterThanNextMin == null || it != stringResource(R.string.reason_fastest)
        })
    }
    Row(Modifier.weight(1f).fillMaxWidth()) {
        LeadColumn(
            rec.station.distanceAlongRouteKm.roundToInt(), stringResource(R.string.unit_km_ahead), rec.station.name, stationMeta(rec.station), Modifier.weight(1.3f),
            extra = if (tags.isEmpty()) null else {
                { FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { tags.forEach { StampTag(it) } } }
            }
        )
        HairlineV(Modifier.padding(horizontal = 32.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Field(stringResource(R.string.label_target), Modifier.weight(1f)) {
                    Figure("${rec.targetSocPercent.roundToInt()}", stringResource(R.string.unit_percent), FigureStyle.Large, color = c.stamp)
                }
                if (card.etaClock.isNotEmpty()) {
                    Field(stringResource(R.string.label_dest_eta)) { Figure(card.etaClock, null, FigureStyle.Small, color = c.inkMuted) }
                }
            }
            Spacer(Modifier.height(16.dp))
            CellPair(
                { Measure(stringResource(R.string.label_charge_time), "${rec.chargeMinutes}", min) },
                { WaitCell(rec) }
            )
            CellPair(
                { Measure(stringResource(R.string.label_arrival_soc), "${rec.arrivalSocPercent.roundToInt()}", stringResource(R.string.unit_percent)) },
                { LimitCell(rec.timeLimitMin, rec.station.rule.timeLimitKnown, limitRisk) }
            )
            HairlineH()
            Spacer(Modifier.weight(1f))
            // 경고 줄: 시간 제한 안에 못 채울 위험 / 한 번 더 충전해야 함
            if (limitRisk && rec.timeLimitMin != null) {
                WarnLine(stringResource(R.string.drive_limit_risk, rec.timeLimitMin, (rec.socAtTimeLimitPercent ?: 0.0).roundToInt()), Modifier.padding(top = 10.dp), singleLine = true)
            }
            val next = rec.nextStop
            when {
                next != null -> InfoLine(stringResource(R.string.drive_two_stop, next.station.name, next.targetSocPercent.roundToInt()), Modifier.padding(top = 10.dp), singleLine = true)
                rec.needsAnotherStop -> WarnLine(stringResource(R.string.drive_need_another), Modifier.padding(top = 10.dp), singleLine = true)
            }
        }
    }
    TearLine(Modifier.padding(vertical = 8.dp))
    // 수락 버튼을 2배 넓게 해서 주로 누를 버튼이 눈에 띄게 한다.
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TicketButton(stringResource(R.string.drive_accept), { onAccept(rec.station.id) }, Modifier.weight(2f), stampInk = true)
        TicketButton(stringResource(R.string.drive_next), onDecline, Modifier.weight(1f), primary = false)
    }
}

/** 대기 칸: 도착 시각 기준 예측이면 "대기 · 예측"으로, 모르면 "정보 없음". */
@Composable
private fun WaitCell(rec: Recommendation) {
    if (rec.waitSource != WaitSource.UNKNOWN) Measure(waitLabel(rec.waitSource), "${rec.waitMinutes}", stringResource(R.string.unit_min))
    else Words(stringResource(R.string.label_wait), stringResource(R.string.unknown))
}

/**
 * 시간 제한 칸: 위험하지 않으면 흐리게, 제한 안에 목표를 못 채울 위험이 있으면 경고색 + 기호.
 * 제한이 없다고 확인했으면 "없음", 확인하지 못했으면([known] false) "정보 없음".
 */
@Composable
private fun LimitCell(limitMin: Int?, known: Boolean, risk: Boolean) {
    val c = Ticket.colors
    when {
        limitMin == null -> Words(stringResource(R.string.label_limit), stringResource(if (known) R.string.value_none else R.string.unknown))
        risk -> Field(stringResource(R.string.label_limit)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WarnGlyph(size = 28.dp)
                Figure("$limitMin", stringResource(R.string.unit_min), FigureStyle.Medium, color = c.warn)
            }
        }
        else -> Measure(stringResource(R.string.label_limit), "$limitMin", stringResource(R.string.unit_min), color = c.inkMuted)
    }
}

/**
 * 혼잡 변형(§7.3, F1): 붐비지 않았다면 골랐을 1순위(왼쪽)와 가장 빨리 도착하는 대안(오른쪽)을 나란히 인쇄한다. (10-congested.png)
 * 운전자가 실제로 궁금한 "목적지에 언제 닿나"를 가장 크게 두고, 그 대가(도착 배터리 차이)를 바로 아래에 적는다.
 * 앱이 마음대로 바꾸지 않고 운전자가 고르게 한다. 더 빨리 도착하는 쪽만 도장 잉크로 칠한다.
 */
@Composable
private fun ColumnScope.CongestedStub(card: DriveCard.Congested, onAccept: (String) -> Unit) {
    val c = Ticket.colors
    val primary = card.primary
    val alternative = card.alternative
    val faster = if (alternative.etaMinutes <= primary.etaMinutes) alternative else primary
    val slower = if (faster === alternative) primary else alternative
    val gainMin = (slower.etaMinutes - faster.etaMinutes).roundToInt()
    val socCost = (slower.arrivalSocPercent - faster.arrivalSocPercent).roundToInt()
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        WarnGlyph(size = 34.dp, modifier = Modifier.padding(bottom = 8.dp))
        Text(stringResource(R.string.drive_congested_title, primary.station.name), style = MaterialTheme.typography.displaySmall, color = c.ink)
        Figure("${primary.waitMinutes}", stringResource(R.string.unit_min), FigureStyle.Medium, color = c.warn)
    }
    // 판정 한 줄: "C 휴게소 쪽이 23분 빨리 도착 · 대신 도착 배터리 11%p 낮음"
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (gainMin >= 1) {
            Text(stringResource(R.string.drive_congested_verdict, faster.station.name, gainMin), style = MaterialTheme.typography.titleLarge, color = c.stamp)
            if (socCost >= 1) Text(stringResource(R.string.drive_congested_cost, socCost), style = MaterialTheme.typography.titleMedium, color = c.inkMuted)
        } else {
            Text(stringResource(R.string.drive_congested_even), style = MaterialTheme.typography.titleLarge, color = c.inkMuted)
        }
    }
    Spacer(Modifier.height(16.dp))
    HairlineH()
    Row(Modifier.weight(1f).fillMaxWidth()) {
        ChoiceColumn(primary, card.primaryEta, emphasize = primary === faster && gainMin >= 1, onAccept = onAccept, modifier = Modifier.weight(1f))
        Box(Modifier.width(56.dp).fillMaxHeight(), contentAlignment = Alignment.Center) { HairlineV() }
        ChoiceColumn(alternative, card.alternativeEta, emphasize = alternative === faster && gainMin >= 1, onAccept = onAccept, modifier = Modifier.weight(1f))
    }
}

// 혼잡 비교 카드의 한쪽 칸: 목적지 도착 시각(주인) → 대기·도착 배터리 → 고르기 버튼. [emphasize]인 쪽만 도장 잉크.
@Composable
private fun ChoiceColumn(rec: Recommendation, eta: String, emphasize: Boolean, onAccept: (String) -> Unit, modifier: Modifier) {
    val c = Ticket.colors
    Column(modifier.fillMaxHeight().padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(rec.station.name, style = MaterialTheme.typography.headlineMedium, color = c.ink, modifier = Modifier.weight(1f))
            Figure("${rec.station.distanceAlongRouteKm.roundToInt()}", stringResource(R.string.unit_km_ahead), FigureStyle.Small, color = c.inkMuted, unitStyle = MaterialTheme.typography.bodyMedium)
        }
        stationMeta(rec.station)?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        Field(stringResource(R.string.label_dest_eta)) {
            Figure(eta, null, FigureStyle.Large, color = if (emphasize) c.stamp else c.ink)
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Box(Modifier.weight(1f)) {
                if (rec.waitSource != WaitSource.UNKNOWN) {
                    Measure(waitLabel(rec.waitSource), "${rec.waitMinutes}", stringResource(R.string.unit_min))
                } else {
                    Words(stringResource(R.string.label_wait), stringResource(R.string.unknown))
                }
            }
            Box(Modifier.weight(1f)) {
                Measure(stringResource(R.string.label_arrival_soc), "${rec.arrivalSocPercent.roundToInt()}", stringResource(R.string.unit_percent))
            }
        }
        Spacer(Modifier.weight(1f))
        TicketButton(
            stringResource(R.string.drive_choose, rec.station.name),
            { onAccept(rec.station.id) },
            Modifier.fillMaxWidth(),
            primary = emphasize,
            stampInk = emphasize
        )
    }
}

/**
 * 수락한 충전소로 가는 중 카드 (04-navigating.png). 남은 거리와 "경유지 등록" 도장, 아래에 경로 띠.
 * 배터리가 차가워 충전이 늦어질 때는 도착 30분 전부터 예열을 권하고 [배터리 예열 켜기] 버튼 하나를 띄운다.
 */
@Composable
private fun ColumnScope.NavigatingStub(card: DriveCard.Navigating, marks: List<RouteMark>, progress: Float, onPrecondition: () -> Unit) {
    val c = Ticket.colors
    val min = stringResource(R.string.unit_min)
    Row(Modifier.weight(1f).fillMaxWidth()) {
        LeadColumn(card.distanceKm.roundToInt(), stringResource(R.string.unit_km_left), stringResource(R.string.drive_navigating, card.stationName), null, Modifier.weight(1.3f))
        HairlineV(Modifier.padding(horizontal = 32.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Field(stringResource(R.string.label_target)) {
                Figure("${card.targetSoc.roundToInt()}", stringResource(R.string.unit_percent), FigureStyle.Large, color = c.stamp)
            }
            Spacer(Modifier.height(16.dp))
            CellPair(
                { Measure(stringResource(R.string.label_arrival_soc), "${card.arrivalSoc.roundToInt()}", stringResource(R.string.unit_percent)) },
                { Measure(stringResource(R.string.label_charge_time), "${card.chargeMinutes}", min) }
            )
            CellPair(
                {
                    if (card.timeLimitMin != null) Measure(stringResource(R.string.label_limit), "${card.timeLimitMin}", min)
                    else Words(stringResource(R.string.label_limit), stringResource(if (card.timeLimitKnown) R.string.value_none else R.string.unknown))
                },
                { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Stamp(stringResource(R.string.stamp_waypoint)) } }
            )
            HairlineH()
            when {
                card.preconditioning == true -> InfoLine(stringResource(R.string.drive_precondition_on), Modifier.padding(top = 10.dp), singleLine = true)
                card.suggestPreconditioning -> {
                    InfoLine(stringResource(R.string.drive_precondition_suggest, card.coldExtraMinutes), Modifier.padding(top = 10.dp), singleLine = true)
                    Spacer(Modifier.weight(1f))
                    TicketButton(stringResource(R.string.drive_precondition_button), onPrecondition, Modifier.fillMaxWidth(), primary = false, minHeight = 88.dp)
                }
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    RouteStrip(marks, progress)
}

/**
 * 평소 주행 카드 (02-drive-status.png). 목적지까지 남은 거리와 충전 계획을 보여 준다.
 * 계획이 없거나 값을 모르는 칸은 "—"로 둔다.
 */
@Composable
private fun ColumnScope.StatusStub(card: DriveCard.Status, eta: String, remainingKm: Double, marks: List<RouteMark>, progress: Float, energy: EnergyInfo?) {
    val c = Ticket.colors
    val min = stringResource(R.string.unit_min)
    // 구간 띠를 표 머리에 인쇄해 위쪽 칸을 채운다.
    Field(stringResource(R.string.label_route)) { RouteStrip(marks, progress) }
    Spacer(Modifier.height(16.dp))
    HairlineH()
    Row(Modifier.weight(1f).fillMaxWidth().padding(top = 20.dp)) {
        LeadColumn(
            remainingKm.roundToInt(),
            stringResource(R.string.unit_km_left),
            stringResource(if (card.chargePlanned) R.string.drive_charge_planned else R.string.drive_no_charge),
            energyNote(energy),
            Modifier.weight(1.3f),
            sentence = true
        )
        HairlineV(Modifier.padding(horizontal = 32.dp))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
            Field(stringResource(R.string.label_planned)) {
                if (card.chargePlanned && card.plannedName != null) {
                    Text(card.plannedName, style = MaterialTheme.typography.displaySmall, color = c.ink, maxLines = 1)
                } else {
                    Text(stringResource(R.string.value_not_needed), style = MaterialTheme.typography.displaySmall, color = c.inkMuted, maxLines = 1)
                }
            }
            Spacer(Modifier.height(16.dp))
            CellPair(
                {
                    if (card.plannedKm != null) Measure(stringResource(R.string.label_distance), "${card.plannedKm.roundToInt()}", stringResource(R.string.unit_km_ahead))
                    else Words(stringResource(R.string.label_distance), "—")
                },
                { Field(stringResource(R.string.label_eta)) { Figure(eta, null, FigureStyle.Medium) } }
            )
            CellPair(
                {
                    if (card.plannedTarget != null) Measure(stringResource(R.string.label_target), "${card.plannedTarget.roundToInt()}", stringResource(R.string.unit_percent))
                    else Words(stringResource(R.string.label_target), "—")
                },
                {
                    if (card.plannedMinutes != null) Measure(stringResource(R.string.label_charge_time), "${card.plannedMinutes}", min)
                    else Words(stringResource(R.string.label_charge_time), "—")
                }
            )
            HairlineH()
            if (card.nextStopName != null && card.nextStopTarget != null) {
                InfoLine(stringResource(R.string.drive_two_stop, card.nextStopName, card.nextStopTarget.roundToInt()), Modifier.padding(top = 10.dp), singleLine = true)
            }
        }
    }
}

/**
 * B3 전비 보정 상태를 한 줄로. 실측이 쌓였으면 "실측 25km · 사양보다 30% 더 씀",
 * 아직이면 기온·속도로 어림한 배율이 5% 넘게 다를 때만 알려 준다. 사양과 거의 같으면 null.
 */
@Composable
private fun energyNote(energy: EnergyInfo?): String? {
    if (energy == null) return null
    val pct = ((energy.factor - 1.0) * 100).roundToInt()
    return when {
        energy.observedKm >= 5.0 && pct >= 3 -> stringResource(R.string.energy_measured, energy.observedKm.roundToInt(), pct)
        energy.observedKm >= 5.0 && pct <= -3 -> stringResource(R.string.energy_measured_less, energy.observedKm.roundToInt(), -pct)
        energy.observedKm < 5.0 && pct >= 5 -> stringResource(R.string.energy_prior, pct)
        else -> null
    }
}

/** 안전하게 갈 수 있는 충전소가 없다는 경고 카드. 그나마 가장 가까운 충전소가 있으면 알려 준다. */
@Composable
private fun ColumnScope.NoSafeStub(card: DriveCard.NoSafeStop, marks: List<RouteMark>, progress: Float) {
    val c = Ticket.colors
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WarnGlyph(size = 48.dp)
            Text(stringResource(R.string.drive_no_safe_title), style = MaterialTheme.typography.displayMedium, color = c.warn)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (card.nearestName != null && card.nearestKm != null) {
                stringResource(R.string.drive_no_safe_sub, card.nearestName, card.nearestKm.roundToInt())
            } else {
                stringResource(R.string.drive_no_safe_none)
            },
            style = MaterialTheme.typography.headlineMedium,
            color = c.ink
        )
    }
    RouteStrip(marks, progress)
}
