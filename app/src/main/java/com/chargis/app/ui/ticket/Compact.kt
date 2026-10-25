package com.chargis.app.ui.ticket

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp

// 좁은 창 대응. Pleos Connect는 앱 창에 앱을 1개 또는 2개 띄우고 가로·세로를 모두 쓰므로,
// 본문 폭이 좁으면(세로 화면, 앱 2개 분할) 2단 배치를 1단으로 쌓고 큰 숫자를 줄인다.

/** 좁은 창이면 true. 기본값 false라 이 값을 주지 않는 화면 테스트는 넓은 창으로 그려진다. */
val LocalCompact = staticCompositionLocalOf { false }

/** 좁고 낮은 창(분할)이면 true. 큰 숫자는 이때만 줄인다. 세로 화면은 높이가 넉넉해 원래 크기로 둔다. */
val LocalShrinkFigures = staticCompositionLocalOf { false }

/** 좁은 창 가운데 본문 높이가 이보다 낮으면 큰 숫자를 줄인다. 세로 화면 본문은 약 1100dp, 분할은 약 680dp다. */
val ShrinkFiguresBelow: Dp = 900.dp

/** 본문 폭이 이보다 좁으면 좁은 창. 가로 태블릿 본문은 약 1230dp, 세로는 약 750dp, 분할은 약 590dp다. */
val CompactBelow: Dp = 1000.dp

/** 좁은 창에서 큰 숫자(36sp 이상)에 곱하는 배율. 72sp가 약 50sp, 40sp가 28sp가 된다. */
const val CompactFigureScale = 0.7f

/** 좁은 창에서 기념비 숫자(112sp)에 곱하는 배율(약 67sp). 분할 화면 높이에 주행 카드가 다 들어가게 더 줄인다. */
const val CompactMonumentalScale = 0.6f

/** 글자 크기·줄 높이·자간을 함께 줄인다. 값이 정해지지 않은 항목은 그대로 둔다. */
fun TextStyle.scaled(factor: Float): TextStyle = copy(
    fontSize = if (fontSize.isSpecified) fontSize * factor else fontSize,
    lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight,
    letterSpacing = if (letterSpacing.isSpecified) letterSpacing * factor else letterSpacing
)

/** 주어진 공간의 폭을 재서 [LocalCompact]를 정해 준다. */
@Composable
fun ProvideCompact(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxWidth < CompactBelow
        CompositionLocalProvider(
            LocalCompact provides compact,
            LocalShrinkFigures provides (compact && maxHeight < ShrinkFiguresBelow)
        ) { content() }
    }
}
