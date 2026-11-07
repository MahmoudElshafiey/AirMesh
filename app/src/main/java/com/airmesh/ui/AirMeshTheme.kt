package com.airmesh.ui

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

/**
 * App theme. On Android 12+ the system's dynamic (wallpaper-derived) palette is
 * used when available; below that — or if [dynamicColor] is turned off — the
 * fixed blue/amber fallback palettes below apply, in light and dark variants.
 * The neutral roles follow the Material 3 baseline so text contrast stays sane.
 */

// Fixed fallback palette: blue primary (actions, my bubbles), amber tertiary
// ("group formed, no neighbors" dot), M3-baseline neutrals and error colors.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1E5AC8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E3FF),
    onPrimaryContainer = Color(0xFF062B5C),
    inversePrimary = Color(0xFFA7C4FF),
    // Secondary mirrors the primary family so selected nav items match bubbles.
    secondary = Color(0xFF4A6FA5),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E3FF),
    onSecondaryContainer = Color(0xFF062B5C),
    tertiary = Color(0xFFB45309),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE2B8),
    onTertiaryContainer = Color(0xFF3A2300),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474A),
    outline = Color(0xFF74777C),
    outlineVariant = Color(0xFFC4C6D0),
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F5),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA7C4FF),
    onPrimary = Color(0xFF062E63),
    primaryContainer = Color(0xFF0E3B7D),
    onPrimaryContainer = Color(0xFFD9E3FF),
    inversePrimary = Color(0xFF1E5AC8),
    secondary = Color(0xFFBCC9EE),
    onSecondary = Color(0xFF1E3054),
    secondaryContainer = Color(0xFF2A3C63),
    onSecondaryContainer = Color(0xFFD9E3FF),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF3A2300),
    tertiaryContainer = Color(0xFF825000),
    onTertiaryContainer = Color(0xFFFFF3D6),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF44474A),
    onSurfaceVariant = Color(0xFFC6C6D0),
    outline = Color(0xFF8E9095),
    outlineVariant = Color(0xFF44474A),
    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = Color(0xFF313033),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFF9DEDC),
)

@Composable
fun AirMeshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // Dynamic color needs the wallpaper palette API (Android 12+).
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
