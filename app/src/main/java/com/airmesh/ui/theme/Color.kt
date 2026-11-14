package com.airmesh.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * AirMesh design tokens (TASK U1 part 1). Brand: a fixed deep-teal primary with an
 * amber warning accent; red is reserved for emergencies only. The neutral roles
 * follow the Material 3 baseline so text contrast stays sane in both themes.
 *
 * Every literal color in the app lives in this file. Components read from
 * [androidx.compose.material3.MaterialTheme.colorScheme] (standard M3 roles) or
 * [LocalAirMeshColors] (the few semantic colors with no M3 role) — they never
 * hardcode their own, so light/dark and any future re-theme stay consistent.
 */

// Light scheme: deep-teal primary (#0E7490), amber tertiary for warnings, red error
// reserved for emergencies, near-white surfaces. `internal` so Theme.kt can build the
// MaterialTheme from them (they are design-system internals, not public API).
internal val LightColors = lightColorScheme(
    primary = Color(0xFF0E7490),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC5EEFF),
    onPrimaryContainer = Color(0xFF062A33),
    inversePrimary = Color(0xFF8CD8EC),
    // Secondary mirrors the teal family so selected nav items match the brand.
    secondary = Color(0xFF4D616C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE4F0),
    onSecondaryContainer = Color(0xFF081E27),
    tertiary = Color(0xFFB45309), // amber: warnings / "searching"
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB2),
    onTertiaryContainer = Color(0xFF281600),
    background = Color(0xFFF9FAFD),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFF9FAFD),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFDCE3E9),
    onSurfaceVariant = Color(0xFF40484C),
    outline = Color(0xFF70787D),
    outlineVariant = Color(0xFFC0C8CD),
    inverseSurface = Color(0xFF2E3133),
    inverseOnSurface = Color(0xFFF1F0F5),
    error = Color(0xFFBA1A1A), // red: emergencies only
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

// Dark scheme: lightened teal primary, amber warning, dark surfaces.
internal val DarkColors = darkColorScheme(
    primary = Color(0xFF5AC8DC),
    onPrimary = Color(0xFF003642),
    primaryContainer = Color(0xFF004A5B),
    onPrimaryContainer = Color(0xFFC5EEFF),
    inversePrimary = Color(0xFF0E7490),
    secondary = Color(0xFFB4C9D6),
    onSecondary = Color(0xFF1F323C),
    secondaryContainer = Color(0xFF354B56),
    onSecondaryContainer = Color(0xFFCFE4F0),
    tertiary = Color(0xFFFFB86B), // amber: warnings / "searching"
    onTertiary = Color(0xFF3A2100),
    tertiaryContainer = Color(0xFF893D00),
    onTertiaryContainer = Color(0xFFFFDDB2),
    background = Color(0xFF191C1E),
    onBackground = Color(0xFFE2E2E5),
    surface = Color(0xFF191C1E),
    onSurface = Color(0xFFE2E2E5),
    surfaceVariant = Color(0xFF40484C),
    onSurfaceVariant = Color(0xFFC0C8CD),
    outline = Color(0xFF8A9297),
    outlineVariant = Color(0xFF40484C),
    inverseSurface = Color(0xFFE2E2E5),
    inverseOnSurface = Color(0xFF313436),
    error = Color(0xFFFFB4AB), // red: emergencies only
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * Semantic colors that have no Material 3 role. Set by [AirMeshTheme] through
 * [LocalAirMeshColors]; components read them instead of hardcoding values.
 */
data class AirMeshExtraColors(
    /** Green dot for ONLINE nodes (presence). */
    val presenceOnline: Color,
    /** Amber accent for warnings and "searching" states. */
    val warning: Color,
    /** Incoming message bubble fill (conversation screen). */
    val bubbleIn: Color,
    /** Outgoing message bubble fill (conversation screen). */
    val bubbleOut: Color,
)

internal val LightExtraColors = AirMeshExtraColors(
    presenceOnline = Color(0xFF16A34A),
    warning = Color(0xFFB45309),
    bubbleIn = Color(0xFFFFFFFF),
    bubbleOut = Color(0xFFD6F0E4),
)

internal val DarkExtraColors = AirMeshExtraColors(
    presenceOnline = Color(0xFF4ADE80),
    warning = Color(0xFFFFB86B),
    bubbleIn = Color(0xFF2A2C2E),
    bubbleOut = Color(0xFF133B33),
)

/** CompositionLocal carrying [AirMeshExtraColors]; provided by [AirMeshTheme]. */
val LocalAirMeshColors = staticCompositionLocalOf { LightExtraColors }
