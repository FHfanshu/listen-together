package app.listentogether.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 一起听固定用深色：听歌场景多在夜里，封面在深色背景上也更好看。 */
object Palette {
    val Background = Color(0xFF0E0E12)
    val Surface = Color(0xFF17171D)
    val SurfaceHigh = Color(0xFF212129)
    val Outline = Color(0xFF2C2C36)
    val Text = Color(0xFFF3F3F6)
    val Muted = Color(0xFF9696A3)
    val Accent = Color(0xFFFF4D5E)
    val AccentSoft = Color(0x33FF4D5E)
    val Online = Color(0xFF34D399)

    val AccentGradient = Brush.linearGradient(listOf(Color(0xFFFF5F6D), Color(0xFFFF8A4C)))
}

private val Scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    primaryContainer = Palette.AccentSoft,
    onPrimaryContainer = Palette.Text,
    secondary = Palette.Accent,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Background,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.Muted,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
    surfaceContainerLow = Palette.Surface,
    outline = Palette.Outline,
    outlineVariant = Palette.Outline,
)

private val Type = Typography().let {
    it.copy(
        headlineLarge = it.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = it.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = it.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
