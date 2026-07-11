package space.linuxct.teleforward.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand palette (Telegram-adjacent blue), used as the static fallback below API 31 or when
// dynamic color is disabled.
private val BrandPrimary = Color(0xFF1E6BE6)
private val BrandOnPrimary = Color(0xFFFFFFFF)
private val BrandPrimaryContainer = Color(0xFFD7E2FF)
private val BrandOnPrimaryContainer = Color(0xFF001A41)
private val BrandSecondary = Color(0xFF565E71)
private val BrandTertiary = Color(0xFF6E5676)

val LightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = BrandOnPrimary,
    primaryContainer = BrandPrimaryContainer,
    onPrimaryContainer = BrandOnPrimaryContainer,
    secondary = BrandSecondary,
    tertiary = BrandTertiary,
    background = Color(0xFFFDFBFF),
    surface = Color(0xFFFDFBFF),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFFACC7FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF004494),
    onPrimaryContainer = BrandPrimaryContainer,
    secondary = Color(0xFFBEC6DC),
    tertiary = Color(0xFFDBBCE2),
    background = Color(0xFF1A1C1E),
    surface = Color(0xFF1A1C1E),
)
