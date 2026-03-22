package org.freewheel.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// High-contrast dark palette — true black base, visible card layering, vibrant accents
private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DA3FF),           // Vibrant blue — saturated like iOS systemBlue
    onPrimary = Color(0xFF00315B),
    primaryContainer = Color(0xFF004880),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF263141),
    secondaryContainer = Color(0xFF3C4858),
    onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFD6BEE4),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF524060),
    onTertiaryContainer = Color(0xFFF2DAFF),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surface = Color(0xFF000000),            // True black — AMOLED, maximum contrast
    onSurface = Color(0xFFE6E6E6),
    onSurfaceVariant = Color(0xFF9E9E9E),
    surfaceContainer = Color(0xFF2C2C2E),   // Cards / interactive tiles (iOS-matched)
    surfaceContainerLow = Color(0xFF1C1C1E), // Grouped sections (iOS-matched)
    surfaceContainerHigh = Color(0xFF3A3A3C),
    surfaceContainerHighest = Color(0xFF48484A),
    outline = Color(0xFF636366),
    outlineVariant = Color(0xFF38383A),
)

// Clean light palette — neutral, clear hierarchy
private val LightColors = lightColorScheme(
    primary = Color(0xFF0066CC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF535F70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2DAFF),
    onTertiaryContainer = Color(0xFF251432),
    error = Color(0xFFD32F2F),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surface = Color(0xFFF2F2F7),            // iOS-style light grouped background
    onSurface = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF636366),
    surfaceContainer = Color(0xFFE5E5EA),
    surfaceContainerLow = Color(0xFFFFFFFF), // White cards on gray background (iOS-style)
    surfaceContainerHigh = Color(0xFFD1D1D6),
    surfaceContainerHighest = Color(0xFFC7C7CC),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFC7C7CC),
)

@Composable
fun AppTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val dynamic = if (useDarkTheme) dynamicDarkColorScheme(context)
                          else dynamicLightColorScheme(context)
            // Pin surface hierarchy to our custom values so card layering
            // stays sharp regardless of wallpaper. Let dynamic theming
            // control accent colors (primary, secondary, tertiary).
            val base = if (useDarkTheme) DarkColors else LightColors
            dynamic.copy(
                surface = base.surface,
                surfaceContainer = base.surfaceContainer,
                surfaceContainerLow = base.surfaceContainerLow,
                surfaceContainerHigh = base.surfaceContainerHigh,
                surfaceContainerHighest = base.surfaceContainerHighest,
                onSurface = base.onSurface,
                onSurfaceVariant = base.onSurfaceVariant,
                outline = base.outline,
                outlineVariant = base.outlineVariant,
            )
        }
        useDarkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        content = content
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
