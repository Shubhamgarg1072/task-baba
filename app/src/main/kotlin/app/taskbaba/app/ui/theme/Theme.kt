package app.taskbaba.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class BabaColors(
    val background: Color,
    val sidebar: Color,
    val card: Color,
    val cardBorder: Color,
    val text: Color,
    val textMuted: Color,
    val saffron: Color,
    val saffronSoft: Color,
    val onSaffron: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val info: Color,
    val critical: Color,
    val high: Color,
    val medium: Color,
    val low: Color,
)

private val Light = BabaColors(
    background = Color(0xFFFBF6EE),
    sidebar = Color(0xFFF3EADC),
    card = Color(0xFFFFFDF9),
    cardBorder = Color(0xFFE8DCCB),
    text = Color(0xFF2A2724),
    textMuted = Color(0xFF6F675E),
    saffron = Color(0xFFE07A1F),
    saffronSoft = Color(0xFFFBE3CC),
    onSaffron = Color(0xFFFFFFFF),
    success = Color(0xFF3F8F5A),
    warning = Color(0xFFB7791F),
    danger = Color(0xFFC0392B),
    info = Color(0xFF3D6FA8),
    critical = Color(0xFFC0392B),
    high = Color(0xFFE07A1F),
    medium = Color(0xFF3D6FA8),
    low = Color(0xFF7D8A80),
)

private val Dark = BabaColors(
    background = Color(0xFF1C1A18),
    sidebar = Color(0xFF242120),
    card = Color(0xFF2A2725),
    cardBorder = Color(0xFF3A3532),
    text = Color(0xFFF1EBE3),
    textMuted = Color(0xFFA9A097),
    saffron = Color(0xFFF0913A),
    saffronSoft = Color(0xFF4A3322),
    onSaffron = Color(0xFF1C1A18),
    success = Color(0xFF6BBF86),
    warning = Color(0xFFE0AA4F),
    danger = Color(0xFFE5735F),
    info = Color(0xFF7FA8DA),
    critical = Color(0xFFE5735F),
    high = Color(0xFFF0913A),
    medium = Color(0xFF7FA8DA),
    low = Color(0xFF9AA79D),
)

val LocalBabaColors = staticCompositionLocalOf { Light }
val LocalReduceMotion = staticCompositionLocalOf { false }

object Baba {
    val colors: BabaColors @Composable get() = LocalBabaColors.current
    val reduceMotion: Boolean @Composable get() = LocalReduceMotion.current
}

private val BabaTypography = Typography(
    displaySmall = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun BabaTheme(dark: Boolean = isSystemInDarkTheme(), reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val scheme = if (dark) darkColorScheme(
        primary = c.saffron, onPrimary = c.onSaffron, primaryContainer = c.saffronSoft,
        background = c.background, surface = c.card, onSurface = c.text, onBackground = c.text,
        surfaceVariant = c.sidebar, onSurfaceVariant = c.textMuted, outline = c.cardBorder, error = c.danger,
        secondaryContainer = c.saffronSoft, onSecondaryContainer = c.text,
    ) else lightColorScheme(
        primary = c.saffron, onPrimary = c.onSaffron, primaryContainer = c.saffronSoft,
        background = c.background, surface = c.card, onSurface = c.text, onBackground = c.text,
        surfaceVariant = c.sidebar, onSurfaceVariant = c.textMuted, outline = c.cardBorder, error = c.danger,
        secondaryContainer = c.saffronSoft, onSecondaryContainer = c.text,
    )
    CompositionLocalProvider(LocalBabaColors provides c, LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = scheme, typography = BabaTypography, content = content)
    }
}
