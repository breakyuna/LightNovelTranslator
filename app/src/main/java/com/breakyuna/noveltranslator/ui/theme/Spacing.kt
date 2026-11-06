package com.breakyuna.noveltranslator.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale (4dp base). Every screen lays out on these steps so vertical
 * rhythm stays consistent between phone and tablet.
 */
object Spacing {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val xxxl: Dp = 48.dp

    /** Page horizontal padding on compact widths. */
    val compactHorizontalPadding: Dp = 20.dp
    /** Page horizontal padding on medium/expanded widths. */
    val expandedHorizontalPadding: Dp = 28.dp

    /** Minimum touch target. */
    val minTouchTarget: Dp = 48.dp

    /** Vertical gap between major sections. */
    val sectionGap: Dp = 24.dp
}
