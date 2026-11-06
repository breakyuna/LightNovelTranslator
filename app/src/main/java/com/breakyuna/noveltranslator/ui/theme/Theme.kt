package com.breakyuna.noveltranslator.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

private val DarkColorScheme = darkColorScheme(
    primary = InkBlue70,
    onPrimary = InkBlue10,
    primaryContainer = InkBlue30,
    onPrimaryContainer = InkBlue80,
    inversePrimary = InkBlue40,
    secondary = Teal60,
    onSecondary = Teal10,
    secondaryContainer = Teal30,
    onSecondaryContainer = Teal80,
    tertiary = Amber60,
    onTertiary = Amber10,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber80,
    error = Red60,
    onError = Red10,
    errorContainer = Red30,
    onErrorContainer = Red80,
    background = NightBackground,
    onBackground = NightOnSurface,
    surface = NightSurface,
    onSurface = NightOnSurface,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = NightOnSurfaceVariant,
    surfaceTint = InkBlue70,
    inverseSurface = NightOnSurface,
    inverseOnSurface = NightSurface,
    outline = NightOutline,
    outlineVariant = NightOutlineVariant,
    scrim = Color(0xFF000000),
    surfaceBright = NightSurfaceHighest,
    surfaceDim = NightBackground,
    surfaceContainer = NightSurfaceContainer,
    surfaceContainerHigh = NightSurfaceHigh,
    surfaceContainerHighest = NightSurfaceHighest,
    surfaceContainerLow = NightSurfaceLow,
    surfaceContainerLowest = NightSurfaceLowest
)

private val LightColorScheme = lightColorScheme(
    primary = InkBlue40,
    onPrimary = OnPrimary,
    primaryContainer = InkBlue90,
    onPrimaryContainer = InkBlue20,
    inversePrimary = InkBlue70,
    secondary = Teal40,
    onSecondary = OnSecondary,
    secondaryContainer = Teal90,
    onSecondaryContainer = Teal20,
    tertiary = Amber50,
    onTertiary = OnTertiary,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber20,
    error = Red50,
    onError = Color(0xFFFFFFFF),
    errorContainer = Red90,
    onErrorContainer = Red20,
    background = PaperBackground,
    onBackground = PaperOnSurface,
    surface = PaperSurface,
    onSurface = PaperOnSurface,
    surfaceVariant = PaperSurfaceHigh,
    onSurfaceVariant = PaperOnSurfaceVariant,
    surfaceTint = InkBlue40,
    inverseSurface = PaperOnSurface,
    inverseOnSurface = PaperSurface,
    outline = PaperOutline,
    outlineVariant = PaperOutlineVariant,
    scrim = Color(0xFF000000),
    surfaceBright = PaperSurface,
    surfaceDim = PaperSurfaceLow,
    surfaceContainer = PaperSurfaceContainer,
    surfaceContainerHigh = PaperSurfaceHigh,
    surfaceContainerHighest = PaperSurfaceHighest,
    surfaceContainerLow = PaperSurfaceLow,
    surfaceContainerLowest = PaperSurfaceLowest
)

@Composable
fun NovelTranslatorTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(
        LocalAppColors provides if (isDark) DarkAppColors else LightAppColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content
        )
    }
}
