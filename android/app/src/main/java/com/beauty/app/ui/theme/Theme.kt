package com.beauty.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beauty.app.R

private val LightPaper = Color(0xFFF8F7F2)
private val LightForest = Color(0xFF283A2C)
private val LightMuted = Color(0xFF626B5D)
private val LightSage = Color(0xFFE8ECDF)
private val LightPeach = Color(0xFFF4BC99)
private val LightCard = Color(0xFFFFFEF9)
private val LightBorder = Color(0xFFD8DCD0)
private val LightSuccess = Color(0xFF3D653D)
private val LightDanger = Color(0xFFA33E46)
private val LightClay = Color(0xFF845438)

/** Extra semantic colours that Material's scheme does not model (success and badges). */
data class AuraSemanticColors(
    val success: Color, val onSuccess: Color, val muted: Color, val sage: Color,
    val peach: Color, val clay: Color, val border: Color
)

private val LocalAuraSemantic = staticCompositionLocalOf {
    AuraSemanticColors(LightSuccess, LightCard, LightMuted, LightSage, LightPeach, LightClay, LightBorder)
}

// Legacy names deliberately resolve from the active scheme, so older screens do not become light-only.
val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
val Forest: Color @Composable get() = MaterialTheme.colorScheme.onBackground
val Muted: Color @Composable get() = LocalAuraSemantic.current.muted
val Sage: Color @Composable get() = LocalAuraSemantic.current.sage
val Peach: Color @Composable get() = LocalAuraSemantic.current.peach
val WhiteCard: Color @Composable get() = MaterialTheme.colorScheme.surface
val Border: Color @Composable get() = LocalAuraSemantic.current.border
val Success: Color @Composable get() = LocalAuraSemantic.current.success
val OnSuccess: Color @Composable get() = LocalAuraSemantic.current.onSuccess
val Danger: Color @Composable get() = MaterialTheme.colorScheme.error
val Clay: Color @Composable get() = LocalAuraSemantic.current.clay
val DarkObsidian: Color @Composable get() = MaterialTheme.colorScheme.background
val CardSurface: Color @Composable get() = MaterialTheme.colorScheme.surface
val RoseGoldPrimary: Color @Composable get() = MaterialTheme.colorScheme.primary
val ChampagneAccent: Color @Composable get() = MaterialTheme.colorScheme.tertiary
val TextLight: Color @Composable get() = MaterialTheme.colorScheme.onSurface
val TextMuted: Color @Composable get() = LocalAuraSemantic.current.muted
val EmeraldStatus: Color @Composable get() = LocalAuraSemantic.current.success

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

internal val AuraColors = lightColorScheme(
    primary = LightForest, onPrimary = LightCard,
    primaryContainer = LightSage, onPrimaryContainer = LightForest,
    secondary = LightForest, onSecondary = LightCard,
    secondaryContainer = LightSage, onSecondaryContainer = LightForest,
    tertiary = LightClay, onTertiary = LightCard,
    tertiaryContainer = LightPeach, onTertiaryContainer = LightForest,
    background = LightPaper, onBackground = LightForest,
    surface = LightCard, onSurface = LightForest,
    surfaceVariant = LightSage, onSurfaceVariant = LightMuted, surfaceTint = LightForest,
    surfaceContainerLowest = LightCard, surfaceContainerLow = LightPaper,
    surfaceContainer = LightCard, surfaceContainerHigh = LightSage,
    surfaceContainerHighest = Color(0xFFE1E6D8),
    outline = Color(0xFF8B9583), outlineVariant = LightBorder,
    error = LightDanger, onError = LightCard,
    errorContainer = Color(0xFFF8E5E5), onErrorContainer = Color(0xFF782B32),
    inverseSurface = LightForest, inverseOnSurface = LightCard, inversePrimary = LightPeach, scrim = LightForest
)

internal val AuraDarkColors = darkColorScheme(
    primary = Color(0xFFC7D6B9), onPrimary = Color(0xFF17311F),
    primaryContainer = Color(0xFF314936), onPrimaryContainer = Color(0xFFE3F0D6),
    secondary = Color(0xFFD4DFCC), onSecondary = Color(0xFF263828),
    secondaryContainer = Color(0xFF3A4D3A), onSecondaryContainer = Color(0xFFE7F0DF),
    tertiary = Color(0xFFFFC8A5), onTertiary = Color(0xFF4A2716),
    tertiaryContainer = Color(0xFF633F28), onTertiaryContainer = Color(0xFFFFDCCA),
    background = Color(0xFF121A15), onBackground = Color(0xFFE7EDE3),
    surface = Color(0xFF1B251E), onSurface = Color(0xFFE7EDE3),
    surfaceVariant = Color(0xFF344238), onSurfaceVariant = Color(0xFFC1CBC0),
    surfaceContainerLowest = Color(0xFF101712), surfaceContainerLow = Color(0xFF172019),
    surfaceContainer = Color(0xFF1B251E), surfaceContainerHigh = Color(0xFF263128),
    surfaceContainerHighest = Color(0xFF303C32), surfaceTint = Color(0xFFC7D6B9),
    outline = Color(0xFF8F9B90), outlineVariant = Color(0xFF465349),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE7EDE3), inverseOnSurface = Color(0xFF28332B), inversePrimary = LightForest,
    scrim = Color.Black
)

internal val LightSemantic = AuraSemanticColors(LightSuccess, LightCard, LightMuted, LightSage, LightPeach, LightClay, LightBorder)
internal val DarkSemantic = AuraSemanticColors(
    success = Color(0xFFA7D9A6), onSuccess = Color(0xFF123819), muted = Color(0xFFC1CBC0),
    sage = Color(0xFF344238), peach = Color(0xFF633F28), clay = Color(0xFFFFC8A5), border = Color(0xFF465349)
)

@Composable
fun BeautyTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAuraSemantic provides if (darkTheme) DarkSemantic else LightSemantic) {
      MaterialTheme(
        colorScheme = if (darkTheme) AuraDarkColors else AuraColors, typography = AuraTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(24.dp)
        ), content = content
      )
    }
}
