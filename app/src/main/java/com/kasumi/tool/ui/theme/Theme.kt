package com.kasumi.tool.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Kasumi purple palette
val KasumiPurple = Color(0xFFBB86FC)
val KasumiPurpleLight = Color(0xFFD0BCFF)
val KasumiPurpleDark = Color(0xFF6650A4)
val KasumiPurpleContainer = Color(0xFF3A2063)
val KasumiOnPurpleContainer = Color(0xFFE8DEFF)
val KasumiTeal = Color(0xFF80CBC4)
val KasumiSurface = Color(0xFF1A1A2E)
val KasumiSurfaceVariant = Color(0xFF252540)
val KasumiSurfaceContainer = Color(0xFF1E1E35)
val KasumiSurfaceContainerHigh = Color(0xFF2A2A48)
val KasumiBackground = Color(0xFF121225)
val KasumiOnBackground = Color(0xFFE6E1E5)
val KasumiOnSurface = Color(0xFFE6E1E5)
val KasumiOnSurfaceVariant = Color(0xFFCAC4D0)
val KasumiOutline = Color(0xFF3D3756)
val KasumiOutlineVariant = Color(0xFF49454F)
val KasumiError = Color(0xFFCF6679)
val KasumiOnError = Color(0xFF1C1B1F)

private val DarkColorScheme = darkColorScheme(
    primary = KasumiPurple,
    onPrimary = Color(0xFF1C1B1F),
    primaryContainer = KasumiPurpleContainer,
    onPrimaryContainer = KasumiOnPurpleContainer,
    secondary = KasumiTeal,
    onSecondary = Color(0xFF1C1B1F),
    secondaryContainer = Color(0xFF1A3A38),
    onSecondaryContainer = Color(0xFFB2DFDB),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF1C1B1F),
    tertiaryContainer = Color(0xFF4A2532),
    onTertiaryContainer = Color(0xFFFDD8E5),
    background = KasumiBackground,
    onBackground = KasumiOnBackground,
    surface = KasumiSurface,
    onSurface = KasumiOnSurface,
    surfaceVariant = KasumiSurfaceVariant,
    onSurfaceVariant = KasumiOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFF0F0F1F),
    surfaceContainerLow = Color(0xFF161630),
    surfaceContainer = KasumiSurfaceContainer,
    surfaceContainerHigh = KasumiSurfaceContainerHigh,
    surfaceContainerHighest = Color(0xFF323250),
    outline = KasumiOutline,
    outlineVariant = KasumiOutlineVariant,
    error = KasumiError,
    onError = KasumiOnError,
    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = Color(0xFF1C1B1F),
    inversePrimary = KasumiPurpleDark,
    scrim = Color(0xFF000000)
)

private val LightColorScheme = lightColorScheme(
    primary = KasumiPurpleDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DEFF),
    onPrimaryContainer = Color(0xFF22005D),
    secondary = Color(0xFF00897B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF002020),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    inversePrimary = KasumiPurpleLight,
    scrim = Color(0xFF000000)
)

@Composable
fun KasumiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
