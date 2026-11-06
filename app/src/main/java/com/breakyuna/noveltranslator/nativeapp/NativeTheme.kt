package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val NativeLightColors = lightColorScheme(
    primary = Color(0xffad5a17),
    onPrimary = Color.White,
    primaryContainer = Color(0xfffde6d2),
    onPrimaryContainer = Color(0xff381900),
    secondary = Color(0xff745944),
    onSecondary = Color.White,
    secondaryContainer = Color(0xfff5e4d4),
    onSecondaryContainer = Color(0xff2a1707),
    tertiary = Color(0xff386641),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xffd7ead9),
    onTertiaryContainer = Color(0xff0b2010),
    error = Color(0xffba1a1a),
    onError = Color.White,
    errorContainer = Color(0xffffdad6),
    onErrorContainer = Color(0xff410002),
    background = Color(0xfff3f4f6),
    onBackground = Color(0xff1e2024),
    surface = Color(0xfffbfcfd),
    onSurface = Color(0xff1e2024),
    surfaceVariant = Color(0xffe9ecef),
    onSurfaceVariant = Color(0xff4e5562),
    surfaceContainerLowest = Color(0xffffffff),
    surfaceContainerLow = Color(0xfff7f8fa),
    surfaceContainer = Color(0xffeff1f5),
    surfaceContainerHigh = Color(0xffe7eaef),
    surfaceContainerHighest = Color(0xffdfe3e9),
    outline = Color(0xffc5cad3),
    outlineVariant = Color(0xffdde1e7),
)

private val NativeDarkColors = darkColorScheme(
    primary = Color(0xfff49a51),
    onPrimary = Color(0xff25160a),
    primaryContainer = Color(0xff5b2e07),
    onPrimaryContainer = Color(0xfffde6d2),
    secondary = Color(0xffdec2ab),
    onSecondary = Color(0xff402b19),
    secondaryContainer = Color(0xff35281e),
    onSecondaryContainer = Color(0xfff5e4d4),
    tertiary = Color(0xff9cc5a1),
    onTertiary = Color(0xff123319),
    tertiaryContainer = Color(0xff223a26),
    onTertiaryContainer = Color(0xffd7ead9),
    error = Color(0xffffb4ab),
    onError = Color(0xff690005),
    errorContainer = Color(0xff5c1916),
    onErrorContainer = Color(0xffffdad6),
    background = Color(0xff111318),
    onBackground = Color(0xffeef1f5),
    surface = Color(0xff171a20),
    onSurface = Color(0xffeef1f5),
    surfaceVariant = Color(0xff222730),
    onSurfaceVariant = Color(0xffa6aebc),
    surfaceContainerLowest = Color(0xff0d0f13),
    surfaceContainerLow = Color(0xff15181e),
    surfaceContainer = Color(0xff1c2028),
    surfaceContainerHigh = Color(0xff242933),
    surfaceContainerHighest = Color(0xff2d333f),
    outline = Color(0xff454d5b),
    outlineVariant = Color(0xff2b313c),
)

internal data class NativeStatusColors(
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val NativeLightStatusColors = NativeStatusColors(
    warning = Color(0xff8a5100),
    warningContainer = Color(0xffffddb0),
    onWarningContainer = Color(0xff2c1600),
)

private val NativeDarkStatusColors = NativeStatusColors(
    warning = Color(0xffffb95c),
    warningContainer = Color(0xff663c00),
    onWarningContainer = Color(0xffffddb0),
)

internal val LocalNativeStatusColors = staticCompositionLocalOf { NativeLightStatusColors }

private val NativeShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

private val BaseTypography = Typography()

private val NativeTypography = Typography(
    headlineSmall = BaseTypography.headlineSmall.copy(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = BaseTypography.titleLarge.copy(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = BaseTypography.titleMedium.copy(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = BaseTypography.titleSmall.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = BaseTypography.bodyLarge.copy(
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = BaseTypography.bodyMedium.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = BaseTypography.bodySmall.copy(
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = BaseTypography.labelLarge.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = BaseTypography.labelMedium.copy(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = BaseTypography.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
    ),
)

@Composable
fun NativeTheme(mode: NativeThemeMode = NativeThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        NativeThemeMode.SYSTEM -> isSystemInDarkTheme()
        NativeThemeMode.LIGHT -> false
        NativeThemeMode.DARK -> true
    }
    CompositionLocalProvider(
        LocalNativeStatusColors provides if (dark) NativeDarkStatusColors else NativeLightStatusColors,
    ) {
        MaterialTheme(
            colorScheme = if (dark) NativeDarkColors else NativeLightColors,
            shapes = NativeShapes,
            typography = NativeTypography,
            content = content,
        )
    }
}
