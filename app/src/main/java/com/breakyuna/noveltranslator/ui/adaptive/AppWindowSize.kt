package com.breakyuna.noveltranslator.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WindowWidthSizeClass {
    COMPACT,
    MEDIUM,
    EXPANDED
}

data class AppWindowSize(
    val widthSizeClass: WindowWidthSizeClass,
    val screenWidthDp: Dp,
    val screenHeightDp: Dp
) {
    val isCompact: Boolean get() = widthSizeClass == WindowWidthSizeClass.COMPACT
    val isMedium: Boolean get() = widthSizeClass == WindowWidthSizeClass.MEDIUM
    val isExpanded: Boolean get() = widthSizeClass == WindowWidthSizeClass.EXPANDED
    val useBottomBar: Boolean get() = isCompact
    val useNavRail: Boolean get() = !isCompact
}

@Composable
fun rememberWindowSize(): AppWindowSize {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp

    val widthSizeClass = when {
        screenWidth < 600.dp -> WindowWidthSizeClass.COMPACT
        screenWidth < 840.dp -> WindowWidthSizeClass.MEDIUM
        else -> WindowWidthSizeClass.EXPANDED
    }

    return remember(screenWidth, screenHeight, widthSizeClass) {
        AppWindowSize(widthSizeClass, screenWidth, screenHeight)
    }
}
