package com.breakyuna.noveltranslator.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Extended semantic colors that the Material 3 scheme does not model.
 *
 * Provided through [LocalAppColors] so screens never hard-code literals; light
 * and dark variants are built once in [NovelTranslatorTheme].
 */
data class AppColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    /** Faded gradient used behind covers that have no artwork yet. */
    val coverPlaceholderStart: Color,
    val coverPlaceholderEnd: Color,
    val onCoverPlaceholder: Color,
    /** Soft brand wash for hero/empty surfaces. */
    val heroStart: Color,
    val heroEnd: Color,
    val onHero: Color,
    /** Scrim behind reader side panels. */
    val scrim: Color
)

val LightAppColors = AppColors(
    success = StatusSuccess,
    onSuccess = Color.White,
    successContainer = StatusSuccessContainer,
    onSuccessContainer = Green20,
    warning = StatusWarning,
    onWarning = Color.White,
    warningContainer = StatusWarningContainer,
    onWarningContainer = Orange30,
    info = AccentBlue,
    infoContainer = InkBlue90,
    onInfoContainer = InkBlue20,
    coverPlaceholderStart = InkBlue90,
    coverPlaceholderEnd = PaperSurfaceLow,
    onCoverPlaceholder = InkBlue20,
    heroStart = InkBlue95,
    heroEnd = PaperSurface,
    onHero = PaperOnSurface,
    scrim = Color(0x66000000)
)

val DarkAppColors = AppColors(
    success = Green60,
    onSuccess = Green10,
    successContainer = Green20,
    onSuccessContainer = Green80,
    warning = Orange60,
    onWarning = Orange10,
    warningContainer = Orange20,
    onWarningContainer = Orange80,
    info = InkBlue70,
    infoContainer = InkBlue30,
    onInfoContainer = InkBlue80,
    coverPlaceholderStart = InkBlue30,
    coverPlaceholderEnd = NightSurfaceHigh,
    onCoverPlaceholder = InkBlue80,
    heroStart = NightSurfaceLow,
    heroEnd = NightSurface,
    onHero = NightOnSurface,
    scrim = Color(0x99000000)
)

/** Extended colors for the current theme. Defaults to the light palette. */
val LocalAppColors = staticCompositionLocalOf { LightAppColors }

/**
 * Motion tokens. Durations stay in the 90–220ms band recommended for Android:
 * long enough to read as intentional, short enough to feel instant.
 */
object Motion {
    const val DurationFast: Int = 90
    const val DurationMedium: Int = 140
    const val DurationSlow: Int = 220

    /** Standard easing for enter/exit transitions. */
    val EaseEmphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Decelerate for content entering the screen. */
    val EaseDecelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)
    /** Accelerate for content leaving the screen. */
    val EaseAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
}

/** Elevation tokens. The app prefers tonal surfaces; shadows stay subtle. */
object Elevation {
    val flat: Dp = 0.dp
    val card: Dp = 1.dp
    val raised: Dp = 2.dp
    val overlay: Dp = 4.dp
    val scrim: Dp = 8.dp
}
