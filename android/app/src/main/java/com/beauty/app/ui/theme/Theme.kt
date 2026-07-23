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

private val DarkColorScheme = darkColorScheme(
    primary = RoseGoldPrimary,
    secondary = ChampagneAccent,
    background = DarkObsidian,
    surface = CardSurface,
    onPrimary = Color.Black,
    onBackground = TextLight,
    onSurface = TextLight
)

@Composable
fun BeautyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
