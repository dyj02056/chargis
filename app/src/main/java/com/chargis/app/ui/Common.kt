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
import com.chargis.app.domain.model.ChargingStation
import com.chargis.app.domain.model.DataSource
import com.chargis.app.domain.model.OpenHours
import com.chargis.app.domain.model.ReasonCode
import com.chargis.app.domain.model.Recommendation
import com.chargis.app.domain.model.RecommendationConfig
import com.chargis.app.domain.model.StationKind
import com.chargis.app.domain.model.WaitSource
import com.chargis.app.ui.theme.FigureStyle
import com.chargis.app.ui.theme.Ticket
import com.chargis.app.ui.ticket.Figure
import com.chargis.app.ui.ticket.HairlineH
import com.chargis.app.ui.ticket.MicGlyph
import com.chargis.app.ui.ticket.SpeakerGlyph
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt

// 주행 화면과 정차 화면이 함께 쓰는 화면 조각들을 모은 파일이다.

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
        // 여행을 시작하기 전(Idle)에는 시각·배터리를 숨긴다.
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

/** 데이터 출처를 화면 글자로 바꾼다. 예) SIMULATED → "시뮬레이션 데이터". 실제 문구는 res/values/strings.xml에 있다. */
@Composable
fun sourceLabel(source: DataSource) = stringResource(
    when (source) {
        DataSource.SDK -> R.string.source_sdk
        DataSource.MANUAL -> R.string.source_manual
        DataSource.SIMULATED -> R.string.source_simulated
    }
)

/**
 * 음성 안내 자막 줄. 소리를 못 들은 동승자도 읽을 수 있게 화면 맨 아래에 띄운다.
 * 오른쪽에는 음성 명령을 듣는 중이면 마이크와 "말하기: 수락 / 다음"을 붙인다(F5).
 * 자막이 없을 때 [note](예: 이전 여행 복원 안내)가 있으면 그것을 흐리게 보여 준다. 보여 줄 것이 없으면 그리지 않는다.
 */
@Composable
fun CaptionBar(caption: String?, modifier: Modifier = Modifier, voice: VoiceUi = VoiceUi(), note: String? = null) {
    val text = caption?.takeIf { it.isNotBlank() } ?: note
    if (text == null && !voice.listening) return
    val c = Ticket.colors
    Column(modifier.fillMaxWidth().background(c.stock)) {
        HairlineH()
        Row(
            Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (text != null) {
                SpeakerGlyph()
                Text(
                    text, style = MaterialTheme.typography.bodyLarge, color = if (caption.isNullOrBlank()) c.inkMuted else c.ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (voice.listening) {
                val words = voice.hints.map { voiceWord(it) }.joinToString(" / ")
                Row(
                    Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MicGlyph()
                    Text(stringResource(R.string.voice_listening), style = MaterialTheme.typography.labelMedium, color = c.stamp)
                    Text(stringResource(R.string.voice_say, words), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
                }
            }
        }
    }
}

// 음성 명령 이름을 운전자가 말할 낱말로 바꾼다.
@Composable
private fun voiceWord(command: String): String = stringResource(
    when (command) {
        "ACCEPT" -> R.string.voice_cmd_accept
        "NEXT_STOP" -> R.string.voice_cmd_next
        "START_CHARGING" -> R.string.voice_cmd_charge
        "PRECONDITION" -> R.string.voice_cmd_precondition
        else -> R.string.voice_cmd_depart
    }
)

/**
 * 세로로 인쇄한 글자(표 꼬리 칸용).
 * 글자를 왼쪽으로 90도 눕혀서 그린다. 가로·세로 크기를 서로 바꿔 재고, 돌린 뒤 제자리에 맞춰 놓는다.
 */
fun Modifier.vertical() = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth))
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer(
            x = -(placeable.width - placeable.height) / 2,
            y = (placeable.width - placeable.height) / 2
        ) { rotationZ = -90f }
    }
}

/**
 * 추천 이유 배지 글자 목록. 예) ["가장 빨리 도착", "가장 빠른 충전기"]
 * 운전 중에 너무 많이 읽지 않도록 최대 [max]개만 보여 준다.
 * (경로 이탈 없음, 시간 제한 위험은 다른 곳에서 따로 표시하므로 여기서는 뺀다.)
 */
@Composable
fun reasonLabels(rec: Recommendation, max: Int = 2): List<String> =
    rec.reasonCodes.mapNotNull {
        when (it) {
            ReasonCode.FASTEST_ARRIVAL -> R.string.reason_fastest
            ReasonCode.LATEST_SAFE_STOP -> R.string.reason_latest_safe
            ReasonCode.MAX_POWER -> R.string.reason_max_power
            ReasonCode.LOW_WAIT -> R.string.reason_low_wait
            ReasonCode.ENOUGH_CHARGERS -> R.string.reason_enough
            // 고장 의심은 추천 이유가 아니라 주의 사항이라 충전소 설명 줄(stationMeta)에 적는다.
            ReasonCode.NO_DETOUR, ReasonCode.TIME_LIMIT_RISK, ReasonCode.MAYBE_BROKEN -> null
        }
    }.take(max).map { stringResource(it) }

/**
 * 충전소 이름 아래에 붙이는 한 줄. 예) "상업시설 · 왕복 5km 우회 · 10:00~22:00 운영 · 주차 유료 · 고장·점검 1대"
 * 본선의 고속도로 휴게소는 고장·점검 소식이 없으면 설명할 것이 없어 null(지금까지의 화면 그대로).
 */
@Composable
fun stationMeta(station: ChargingStation): String? {
    val parts = buildList {
        when (station.kind) {
            StationKind.HIGHWAY_REST_STOP -> null
            StationKind.LOCAL_REST_STOP -> R.string.kind_local_rest_stop
            StationKind.SHELTER -> R.string.kind_shelter
            StationKind.COMMERCIAL -> R.string.kind_commercial
            StationKind.PUBLIC -> R.string.kind_public
            StationKind.OTHER -> R.string.kind_other
        }?.let { add(stringResource(it)) }
        if (station.detourKm > OFF_ROUTE_KM) add(stringResource(R.string.meta_round_trip, (2 * station.detourKm).roundToInt()))
        station.openHours?.takeIf { it != OpenHours.ALL_DAY }?.let {
            add(stringResource(R.string.meta_hours, clock(it.openMin), clock(it.closeMin)))
        }
        if (station.parkingFree == false) add(stringResource(R.string.meta_parking_paid))
        station.brokenChargers?.takeIf { it > 0 }?.let { add(stringResource(R.string.meta_broken, it)) }
        station.hoursSinceLastCharge?.takeIf { it >= staleHours(station.kind) }?.let { add(stringResource(R.string.meta_stale, (it / 24).toInt())) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

// 엔진이 "경로를 벗어난다"고 보는 편도 우회 거리, "고장 의심"으로 보는 무충전 시간과 같은 기준
private val OFF_ROUTE_KM = RecommendationConfig().offRouteKm
private val STALE_CONFIG = RecommendationConfig()
private fun staleHours(kind: StationKind) =
    if (kind == StationKind.HIGHWAY_REST_STOP || kind == StationKind.COMMERCIAL) STALE_CONFIG.staleChargeHours else STALE_CONFIG.staleChargeHoursQuiet

/** 요금 표시: 100원 단위로 반올림하고 천 단위 쉼표. 예) 3,780 → "3,800" */
fun wonText(krw: Int): String = "%,d".format(((krw + 50) / 100) * 100)

// 0시부터 분 → "09:30"
private fun clock(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60 % 24, minuteOfDay % 60)

/** 대기 칸 이름에 출처 꼬리표를 붙인다. 예) "대기 · 예측". 실시간이면 꼬리표 없이 "대기". */
@Composable
fun waitLabel(source: WaitSource): String {
    val base = stringResource(R.string.label_wait)
    return when (source) {
        WaitSource.FORECAST, WaitSource.BLENDED -> "$base · ${stringResource(R.string.wait_forecast)}"
        WaitSource.LIVE, WaitSource.UNKNOWN -> base
    }
}

/**
 * 괘선으로만 나눈 비교 표. 미상 값은 "정보 없음".
 * 정차 화면에서 후보 충전소들을 [충전소 | 도착 % | 출력 | 빈 충전기 | 대기] 칸으로 나란히 보여 준다.
 */
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
                Column(Modifier.weight(1.5f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 1)
                    // 휴게소가 아니거나 경로를 벗어나는 곳이면 종류·우회를 작게 덧붙인다.
                    stationMeta(s)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                NumberCell("${r.arrivalSocPercent.roundToInt()}", "%", Modifier.weight(1f))
                NumberCell("${s.maxKw.roundToInt()}", "kW", Modifier.weight(1f))
                NumberCell(s.availableChargers?.let { "$it/${s.totalChargers}" }, null, Modifier.weight(1f), unknown)
                NumberCell(r.waitMinutes.takeIf { r.waitSource != WaitSource.UNKNOWN }?.toString(), stringResource(R.string.unit_min), Modifier.weight(1f), unknown)
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

// 표의 머리 줄(칸 이름들). 첫 칸(충전소 이름)은 다른 칸보다 1.5배 넓다.
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
