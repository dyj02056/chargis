package com.chargis.app.ui.ticket

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chargis.app.ui.theme.Ticket

/** 시스템 "애니메이션 제거"가 켜져 있으면 true. 그때는 모든 전환을 즉시 바꾼다. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

private val NotchRadius = 14.dp
private val Dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))

/**
 * 표 한 장. [perforationX]가 있으면 그 위치에 세로 절취선과 위·아래 펀치 홈을 그린다.
 * 홈은 바탕색 원으로 표 가장자리를 파낸다.
 */
@Composable
fun TicketStock(
    modifier: Modifier = Modifier,
    perforationX: Dp? = null,
    perforationFromEnd: Boolean = false,
    content: @Composable () -> Unit
) {
    val c = Ticket.colors
    Box(
        modifier.drawBehind {
            drawRect(c.stock)
            if (perforationX != null) {
                val x = if (perforationFromEnd) size.width - perforationX.toPx() else perforationX.toPx()
                drawLine(c.rule, Offset(x, NotchRadius.toPx() + 6f), Offset(x, size.height - NotchRadius.toPx() - 6f), strokeWidth = 2f, pathEffect = Dash)
                drawCircle(c.ground, NotchRadius.toPx(), Offset(x, 0f))
                drawCircle(c.ground, NotchRadius.toPx(), Offset(x, size.height))
            }
        }
    ) { content() }
}

/** 가로 절취선. 양 끝은 펀치 홈. 뜯어내는 띠(버튼 줄) 위에 쓴다. */
@Composable
fun TearLine(modifier: Modifier = Modifier) {
    val c = Ticket.colors
    Canvas(modifier.fillMaxWidth().height(NotchRadius * 2)) {
        val y = size.height / 2
        val r = NotchRadius.toPx()
        drawLine(c.rule, Offset(r + 6f, y), Offset(size.width - r - 6f, y), strokeWidth = 2f, pathEffect = Dash)
        drawCircle(c.ground, r, Offset(0f, y))
        drawCircle(c.ground, r, Offset(size.width, y))
    }
}

@Composable
fun HairlineH(modifier: Modifier = Modifier) {
    val c = Ticket.colors
    Box(modifier.fillMaxWidth().height(1.dp).background(c.rule))
}

@Composable
fun HairlineV(modifier: Modifier = Modifier) {
    val c = Ticket.colors
    Box(modifier.fillMaxHeight().width(1.dp).background(c.rule))
}

/** 인쇄된 칸 하나: 칸 왼쪽 위에 작은 칸 이름, 아래에 값. */
@Composable
fun Field(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Ticket.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
        content()
    }
}

/** 측정값 + 단위. 숫자는 Geist Mono, 단위는 한글 글꼴. */
@Composable
fun Figure(
    value: String,
    unit: String?,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Ticket.colors.ink,
    unitStyle: TextStyle = com.chargis.app.ui.theme.FigureStyle.Unit
) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(value, style = style, color = color, maxLines = 1)
        if (unit != null) {
            Text(
                unit,
                style = unitStyle,
                color = color,
                modifier = Modifier.padding(start = 6.dp, bottom = (style.fontSize.value * 0.12f).dp)
            )
        }
    }
}

/**
 * 도장. 이중 테두리 사각 도장이 살짝 기울어 찍힌다.
 * 처음 나타날 때 한 번만 크게 찍히는 동작을 하고(이 앱의 유일한 연출), 애니메이션 제거 설정에서는 바로 나타난다.
 */
@Composable
fun Stamp(text: String, modifier: Modifier = Modifier, color: Color = Ticket.colors.stamp) {
    val reduce = rememberReduceMotion()
    val scale = remember { Animatable(if (reduce) 1f else 1.35f) }
    val alpha = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(text) {
        if (!reduce) {
            alpha.animateTo(1f, tween(90))
            scale.animateTo(1f, tween(260, easing = { t -> 1f - (1f - t) * (1f - t) * (1f - t) }))
        }
    }
    Box(
        modifier
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value }
            .rotate(-6f)
            .border(2.dp, color, RoundedCornerShape(4.dp))
            .padding(3.dp)
            .border(1.dp, color, RoundedCornerShape(2.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .semantics { contentDescription = text }
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

/**
 * 뜯는 띠의 버튼. primary는 잉크로 채운 칸, secondary는 잉크 괘선 칸.
 * [stampInk]는 휴게소를 표에 찍는 동작(경유지 수락, 혼잡 시 고른 휴게소)에만 쓴다: 도장 잉크로 채운다.
 * 누르면 0.98로 살짝 눌린다.
 */
@Composable
fun TicketButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    stampInk: Boolean = false,
    minHeight: Dp = 104.dp,
    enabled: Boolean = true
) {
    val c = Ticket.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(6.dp)
    val fill = when {
        !primary -> Color.Transparent
        stampInk -> c.stamp
        else -> c.ink
    }
    val textColor = when {
        !primary -> c.ink
        stampInk -> c.onStamp
        else -> c.stock
    }
    Box(
        modifier
            .heightIn(min = minHeight)
            .scale(if (pressed) 0.98f else 1f)
            .background(fill, shape)
            .then(if (primary) Modifier else Modifier.border(2.dp, c.ink, shape))
            .clickable(
                interactionSource = source,
                indication = ripple(color = textColor),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = textColor, textAlign = TextAlign.Center)
    }
}

/** 경고 기호: 그린 삼각형 + 느낌표. 색만으로 뜻을 전하지 않도록 늘 글자와 함께 쓴다. */
@Composable
fun WarnGlyph(modifier: Modifier = Modifier, color: Color = Ticket.colors.warn, size: Dp = 28.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val tri = Path().apply {
            moveTo(w / 2, h * 0.06f); lineTo(w * 0.97f, h * 0.92f); lineTo(w * 0.03f, h * 0.92f); close()
        }
        drawPath(tri, color, style = Stroke(width = w * 0.09f))
        drawLine(color, Offset(w / 2, h * 0.36f), Offset(w / 2, h * 0.62f), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
        drawCircle(color, w * 0.055f, Offset(w / 2, h * 0.77f))
    }
}

/** 음성 안내 표시: 그린 스피커. */
@Composable
fun SpeakerGlyph(modifier: Modifier = Modifier, color: Color = Ticket.colors.inkMuted, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val body = Path().apply {
            moveTo(w * 0.08f, h * 0.36f); lineTo(w * 0.3f, h * 0.36f); lineTo(w * 0.56f, h * 0.12f)
            lineTo(w * 0.56f, h * 0.88f); lineTo(w * 0.3f, h * 0.64f); lineTo(w * 0.08f, h * 0.64f); close()
        }
        drawPath(body, color)
        drawArc(color, -45f, 90f, false, Offset(w * 0.5f, h * 0.3f), androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.4f), style = Stroke(w * 0.08f, cap = StrokeCap.Round))
        drawArc(color, -50f, 100f, false, Offset(w * 0.46f, h * 0.14f), androidx.compose.ui.geometry.Size(w * 0.52f, h * 0.72f), style = Stroke(w * 0.08f, cap = StrokeCap.Round))
    }
}
