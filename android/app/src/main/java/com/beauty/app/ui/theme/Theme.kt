package com.beauty.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.R

val Paper = Color(0xFFF8F7F2)
val Forest = Color(0xFF283A2C)
val Muted = Color(0xFF626B5D)
val Sage = Color(0xFFE8ECDF)
val Peach = Color(0xFFF4BC99)
val WhiteCard = Color(0xFFFFFEF9)
val Border = Color(0xFFD8DCD0)
val Success = Color(0xFF3D653D)
val Danger = Color(0xFFA33E46)
val Clay = Color(0xFF845438)

// Compatibility for screens whose call sites predate the shared design tokens.
val DarkObsidian = Paper
val CardSurface = WhiteCard
val RoseGoldPrimary = Forest
val ChampagneAccent = Color(0xFF74523C)
val TextLight = Forest
val TextMuted = Muted
val EmeraldStatus = Success

val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold)
)

private fun type(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = Manrope, fontWeight = weight, fontSize = size.sp,
    lineHeight = lineHeight.sp, letterSpacing = 0.sp
)

private val AuraTypography = Typography(
    displayLarge = type(36, 44, FontWeight.SemiBold),
    displayMedium = type(32, 40, FontWeight.SemiBold),
    displaySmall = type(30, 38, FontWeight.SemiBold),
    headlineLarge = type(30, 38, FontWeight.SemiBold),
    headlineMedium = type(24, 32, FontWeight.SemiBold),
    headlineSmall = type(20, 28, FontWeight.SemiBold),
    titleLarge = type(20, 28, FontWeight.SemiBold),
    titleMedium = type(16, 24, FontWeight.SemiBold),
    titleSmall = type(13, 20, FontWeight.Bold),
    bodyLarge = type(16, 25), bodyMedium = type(14, 22), bodySmall = type(13, 20),
    labelLarge = type(14, 21, FontWeight.Bold),
    labelMedium = type(13, 20, FontWeight.SemiBold), labelSmall = type(12, 18, FontWeight.SemiBold)
)

private val AuraColors = lightColorScheme(
    primary = Forest, onPrimary = WhiteCard,
    primaryContainer = Sage, onPrimaryContainer = Forest,
    secondary = Forest, onSecondary = WhiteCard,
    secondaryContainer = Sage, onSecondaryContainer = Forest,
    tertiary = Clay, onTertiary = WhiteCard,
    tertiaryContainer = Peach, onTertiaryContainer = Forest,
    background = Paper, onBackground = Forest,
    surface = WhiteCard, onSurface = Forest,
    surfaceVariant = Sage, onSurfaceVariant = Muted, surfaceTint = Forest,
    surfaceContainerLowest = WhiteCard, surfaceContainerLow = Paper,
    surfaceContainer = WhiteCard, surfaceContainerHigh = Sage,
    surfaceContainerHighest = Color(0xFFE1E6D8),
    outline = Color(0xFF8B9583), outlineVariant = Border,
    error = Danger, onError = WhiteCard,
    errorContainer = Color(0xFFF8E5E5), onErrorContainer = Color(0xFF782B32),
    inverseSurface = Forest, inverseOnSurface = WhiteCard, inversePrimary = Peach, scrim = Forest
)

@Composable
fun BeautyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AuraColors, typography = AuraTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(24.dp)
        ), content = content
    )
}
