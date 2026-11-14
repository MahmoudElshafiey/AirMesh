package com.airmesh.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * App theme (TASK U1 part 1). Uses the fixed teal/amber brand palettes by default.
 * On Android 12+ the system's dynamic (wallpaper-derived) palette is available as an
 * option via [dynamicColor] but is OFF by default so the brand stays consistent; when
 * it is on, only the standard M3 roles are recolored — the few extra semantic colors
 * keep their brand values.
 */
@Composable
fun AirMeshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Brand palettes are the default; dynamic color is opt-in (off by default).
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        // Dynamic color needs the wallpaper palette API (Android 12+).
        useDynamic -> if (darkTheme) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val extraColors = if (darkTheme) DarkExtraColors else LightExtraColors
    CompositionLocalProvider(LocalAirMeshColors provides extraColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AirMeshTypography,
            shapes = AirMeshShapes,
            content = content,
        )
    }
}
