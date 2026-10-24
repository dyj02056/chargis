package com.chargis.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp
import com.chargis.app.R

// 앱의 색과 글꼴을 한곳에서 정하는 파일이다. 디자인을 바꿀 때는 주로 여기만 고친다.
// 색은 0xFF______ 형태의 16진수 색상 코드로 적는다(앞 FF는 불투명, 뒤 6자리는 웹 색상 코드와 같다).
//
// "통행권" 세계: 표 용지(stock)와 잉크. 야간은 따뜻한 차콜 용지에 흰 잉크, 주간은 차가운 흰 감열지에 검은 잉크.
// 강조색은 도장 잉크 하나뿐이며 지금 향하는 충전소와 도장에만 쓴다. 경고는 호박색 + 그린 기호 + 글자.

/** 앱에서 쓰는 색 묶음. 밤(어두운 화면)과 낮(밝은 화면) 두 벌이 있다. */
@Immutable
data class TicketPalette(
    val ground: Color,      // 화면 바탕(표 바깥)
    val stock: Color,       // 표 용지
    val ink: Color,         // 본문 인쇄
    val inkMuted: Color,    // 감열 흐림(보조 인쇄)
    val rule: Color,        // 괘선·절취선
    val stamp: Color,       // 도장 잉크(유일한 강조색)
    val onStamp: Color,     // 도장 잉크 면 위 글자
    val warn: Color,        // 경고(호박)
    val isNight: Boolean
)

val NightPalette = TicketPalette(
    ground = Color(0xFF0F0E0D),
    stock = Color(0xFF1A1917),
    ink = Color(0xFFEDE9E2),
    inkMuted = Color(0xFFA19C93),
    rule = Color(0xFF3B3833),
    stamp = Color(0xFF8FB2EE),
    onStamp = Color(0xFF0F1A2E),
    warn = Color(0xFFE8AE4A),
    isNight = true
)

val DayPalette = TicketPalette(
    ground = Color(0xFFE1E5E8),
    stock = Color(0xFFF3F5F6),
    ink = Color(0xFF181B1E),
    inkMuted = Color(0xFF57606A),
    rule = Color(0xFFC4CACF),
    stamp = Color(0xFF2448A6),
    onStamp = Color(0xFFF3F5F6),
    warn = Color(0xFF8A5300),
    isNight = false
)

// 화면 어디서든 지금 색 묶음을 꺼내 쓸 수 있게 하는 통로. 기본은 밤 색.
val LocalTicket = staticCompositionLocalOf { NightPalette }

/** 한글: Wanted Sans. 측정값(거리·%·분·시각): Geist Mono. */
val SansFamily = FontFamily(
    Font(R.font.wanted_sans_medium, FontWeight.Medium),
    Font(R.font.wanted_sans_medium, FontWeight.Normal),
    Font(R.font.wanted_sans_semibold, FontWeight.SemiBold),
    Font(R.font.wanted_sans_bold, FontWeight.Bold),
    Font(R.font.wanted_sans_extrabold, FontWeight.ExtraBold)
)

val MonoFamily = FontFamily(
    Font(R.font.geist_mono_medium, FontWeight.Medium),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
    Font(R.font.geist_mono_bold, FontWeight.Bold)
)

/**
 * 표에 인쇄된 숫자 크기. Material 글자 단계 밖의 측정값 전용.
 * 운전 중에도 한눈에 읽히도록 "50%" 같은 핵심 숫자는 아주 크게(Monumental = 112sp) 쓴다.
 */
object FigureStyle {
    val Monumental = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 112.sp, lineHeight = 112.sp, letterSpacing = (-4).sp)
    val Large = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 72.sp, lineHeight = 76.sp, letterSpacing = (-2).sp)
    val Medium = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1).sp)
    val Small = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp)
    val Unit = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 30.sp)
}

// 한글은 어절 단위로 줄을 바꾼다(API 33+). 그 아래 버전에서는 무시된다.
private val KoreanLineBreak = LineBreak(LineBreak.Strategy.HighQuality, LineBreak.Strictness.Normal, LineBreak.WordBreak.Phrase)
// 어절 단위 줄바꿈은 글자의 로캘이 한국어일 때만 동작한다. 기기 언어와 상관없이 한국어로 지정한다.
private val Korean = LocaleList("ko-KR")

// 일반 글자의 크기 단계(제목, 본문, 라벨 등). 차량 화면은 멀리서 보므로 휴대폰 앱보다 크게 잡았다.
private val baseTypography = Typography(
    displayMedium = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.ExtraBold, fontSize = 48.sp, lineHeight = 56.sp, letterSpacing = (-1).sp),
    displaySmall = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp),
    titleLarge = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp),
    labelMedium = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp)
)

// 문장이 들어가는 글자 단계에만 한국어 어절 단위 줄바꿈을 적용한다.
private val typography = baseTypography.copy(
    displayMedium = baseTypography.displayMedium.copy(lineBreak = KoreanLineBreak, localeList = Korean),
    displaySmall = baseTypography.displaySmall.copy(lineBreak = KoreanLineBreak, localeList = Korean),
    headlineMedium = baseTypography.headlineMedium.copy(lineBreak = KoreanLineBreak, localeList = Korean),
    titleLarge = baseTypography.titleLarge.copy(lineBreak = KoreanLineBreak, localeList = Korean),
    titleMedium = baseTypography.titleMedium.copy(lineBreak = KoreanLineBreak, localeList = Korean),
    bodyLarge = baseTypography.bodyLarge.copy(lineBreak = KoreanLineBreak, localeList = Korean),
    bodyMedium = baseTypography.bodyMedium.copy(lineBreak = KoreanLineBreak, localeList = Korean)
)

// 우리 색 묶음을 안드로이드 기본 디자인 체계(Material)의 색 이름에 연결한다.
// 그래야 기본 부품(버튼 등)도 같은 색을 쓴다.
private fun TicketPalette.toScheme(): ColorScheme {
    val base = if (isNight) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = stamp, onPrimary = onStamp,
        background = ground, onBackground = ink,
        surface = stock, onSurface = ink,
        surfaceVariant = stock, onSurfaceVariant = inkMuted,
        outline = rule, outlineVariant = rule,
        error = warn
    )
}

/** 앱 전체를 감싸는 테마. 기기가 다크 모드면 밤 색, 아니면 낮 색을 쓴다. */
@Composable
fun ChargisTheme(night: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (night) NightPalette else DayPalette
    CompositionLocalProvider(LocalTicket provides palette) {
        MaterialTheme(colorScheme = palette.toScheme(), typography = typography, content = content)
    }
}

/** 화면 코드에서 `Ticket.colors.ink`처럼 지금 색을 짧게 꺼내 쓰기 위한 바로가기. */
object Ticket {
    val colors: TicketPalette
        @Composable get() = LocalTicket.current
}
