package de.pokemoncarddetector.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Red = Color(0xFFD7261E)
private val RedDark = Color(0xFFFFB4A9)
private val Blue = Color(0xFF2A62B0)
private val BlueDark = Color(0xFFAEC6FF)
private val Yellow = Color(0xFFF5B800)

private val LightColors = lightColorScheme(
    primary = Red,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410001),
    secondary = Blue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E2FF),
    onSecondaryContainer = Color(0xFF001A42),
    tertiary = Color(0xFF7A5900),
    tertiaryContainer = Color(0xFFFFDEA3),
    onTertiaryContainer = Color(0xFF261900),
)

private val DarkColors = darkColorScheme(
    primary = RedDark,
    onPrimary = Color(0xFF690002),
    primaryContainer = Color(0xFF930005),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = BlueDark,
    onSecondary = Color(0xFF002E6A),
    secondaryContainer = Color(0xFF004494),
    onSecondaryContainer = Color(0xFFD8E2FF),
    tertiary = Yellow,
    tertiaryContainer = Color(0xFF5C4200),
    onTertiaryContainer = Color(0xFFFFDEA3),
)

/** Farbe fuer "Karte im Besitz"-Markierungen. */
val OwnedGreen = Color(0xFF2E7D32)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
