package com.chargis.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 차량 화면용 고대비 다크 테마(plan.md §9.4).
object ChargisColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF15181C)
    val SurfaceHigh = Color(0xFF22272E)
    val OnSurface = Color(0xFFF2F4F7)
    val Muted = Color(0xFFA9B1BC)
    val Accent = Color(0xFF00E0A0)
    val OnAccent = Color(0xFF00241A)
    val Warning = Color(0xFFFFC145)
    val Danger = Color(0xFFFF6B5E)
}

private val scheme = darkColorScheme(
    primary = ChargisColors.Accent,
    onPrimary = ChargisColors.OnAccent,
    secondary = ChargisColors.SurfaceHigh,
    onSecondary = ChargisColors.OnSurface,
    background = ChargisColors.Background,
    onBackground = ChargisColors.OnSurface,
    surface = ChargisColors.Surface,
    onSurface = ChargisColors.OnSurface,
    error = ChargisColors.Danger
)

private val typography = Typography(
    displayLarge = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Bold, lineHeight = 60.sp),
    displayMedium = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp),
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 38.sp),
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp),
    bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    labelLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun ChargisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
