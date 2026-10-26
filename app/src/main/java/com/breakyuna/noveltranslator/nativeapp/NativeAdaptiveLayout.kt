package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.ui.unit.dp

/**
 * Shared window-width thresholds. Pages only change composition, not business state.
 * The shell passes its window-level decision down because the navigation rail reduces the
 * width available to page content at the same window size.
 */
internal val NativeRailBreakpoint = 600.dp
internal val NativeTwoPaneBreakpoint = 840.dp

/** Fixed reading width for the wide Agent summary rail. */
internal val NativeAgentSidePanelWidth = 300.dp
