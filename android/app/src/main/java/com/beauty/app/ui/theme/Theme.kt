package com.beauty.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkObsidian = Color(0xFF0F0E13)
val CardSurface = Color(0xFF181622)
val RoseGoldPrimary = Color(0xFFE5B899)
val ChampagneAccent = Color(0xFFD4A373)
val TextLight = Color(0xFFF8F6F0)
val TextMuted = Color(0xFF9E9AA8)
val EmeraldStatus = Color(0xFF2DD4BF)

// Every role a component reads is set here. Anything left out falls back to
// Material's baseline purple, which is what gave cards a grey-violet fill and
// selected filter chips a blue-grey one next to the web app's palette.
private val DarkColorScheme = darkColorScheme(
    primary = RoseGoldPrimary,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3B2D25),
    onPrimaryContainer = RoseGoldPrimary,
    secondary = ChampagneAccent,
    onSecondary = Color.Black,
    // Selected FilterChip fill, as the active tag pill on the web.
    secondaryContainer = RoseGoldPrimary,
    onSecondaryContainer = Color.Black,
    tertiary = EmeraldStatus,
    onTertiary = Color.Black,
    background = DarkObsidian,
    onBackground = TextLight,
    surface = CardSurface,
    onSurface = TextLight,
    surfaceVariant = Color(0xFF221F2C),
    onSurfaceVariant = TextMuted,
    surfaceTint = RoseGoldPrimary,
    surfaceContainerLowest = Color(0xFF0B0A0E),
    surfaceContainerLow = Color(0xFF141219),
    surfaceContainer = CardSurface,
    surfaceContainerHigh = Color(0xFF1D1A27),
    surfaceContainerHighest = Color(0xFF221F2C),
    outline = Color(0xFF4A4554),
    outlineVariant = Color(0xFF2C2936)
)

@Composable
fun BeautyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
