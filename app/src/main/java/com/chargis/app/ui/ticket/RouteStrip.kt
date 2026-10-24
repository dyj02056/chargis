package com.chargis.app.ui.ticket

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.chargis.app.ui.MarkState
import com.chargis.app.ui.RouteMark
import com.chargis.app.ui.theme.Ticket

/**
 * 표에 인쇄된 구간 띠: 출발 ─ 휴게소들 ─ 도착. 지나온 구간은 실선, 남은 구간은 점선.
 * 목표 휴게소만 도장 잉크로 채우고, 거절한 곳은 X, 충전한 곳은 채운 원으로 남긴다.
 */
@Composable
fun RouteStrip(marks: List<RouteMark>, progress: Float, modifier: Modifier = Modifier) {
    val c = Ticket.colors
    val labelStyle = MaterialTheme.typography.labelMedium
    BoxWithConstraints(modifier.fillMaxWidth().height(64.dp)) {
        val w = maxWidth
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val y = size.height / 2
            val px = size.width * progress
            val r = 7.dp.toPx()
            drawLine(c.ink, Offset(0f, y), Offset(px, y), strokeWidth = 4f)
            drawLine(c.rule, Offset(px, y), Offset(size.width, y), strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            // 출발·도착 막대
            drawLine(c.ink, Offset(1f, y - r), Offset(1f, y + r), strokeWidth = 3f)
            drawLine(c.ink, Offset(size.width - 1f, y - r), Offset(size.width - 1f, y + r), strokeWidth = 3f)
            marks.forEach { m ->
                val x = size.width * m.fraction
                val center = Offset(x, y)
                when (m.state) {
                    MarkState.TARGET -> drawCircle(c.stamp, r * 1.25f, center)
                    MarkState.CHARGED -> drawCircle(c.ink, r, center)
                    MarkState.DECLINED -> {
                        drawCircle(c.stock, r, center)
                        drawLine(c.inkMuted, Offset(x - r, y - r), Offset(x + r, y + r), strokeWidth = 3f)
                        drawLine(c.inkMuted, Offset(x - r, y + r), Offset(x + r, y - r), strokeWidth = 3f)
                    }
                    MarkState.PASSED -> {
                        drawCircle(c.stock, r, center)
                        drawCircle(c.inkMuted, r, center, style = Stroke(3f))
                    }
                    MarkState.AHEAD -> {
                        drawCircle(c.stock, r, center)
                        drawCircle(c.ink, r, center, style = Stroke(3f))
                    }
                }
            }
            // 현재 위치: 짧은 세로 막대
            drawLine(c.ink, Offset(px, y - r * 1.8f), Offset(px, y + r * 1.8f), strokeWidth = 5f)
        }
        marks.forEach { m ->
            val color = when (m.state) {
                MarkState.TARGET -> c.stamp
                MarkState.PASSED, MarkState.DECLINED -> c.inkMuted
                else -> c.ink
            }
            Box(Modifier.offset(x = w * m.fraction - 60.dp, y = 32.dp).width(120.dp)) {
                Text(
                    m.name,
                    style = labelStyle,
                    color = color,
                    textAlign = TextAlign.Center,
                    textDecoration = if (m.state == MarkState.DECLINED) TextDecoration.LineThrough else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
