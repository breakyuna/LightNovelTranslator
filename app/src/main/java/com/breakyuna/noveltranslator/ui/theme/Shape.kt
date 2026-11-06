package com.breakyuna.noveltranslator.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/* ============================================================================
 * Shape tokens — an expressive Material 3 scale.
 * Cards use generous radii; controls stay tighter; sheets and dialogs round
 * from the top so they read as layered paper.
 * ========================================================================== */

/** Chips, small badges, tiny controls. */
val SmallControlShape = RoundedCornerShape(10.dp)

/** Standard buttons. */
val ButtonShape = RoundedCornerShape(14.dp)

/** Grouped list cards / settings surfaces. */
val GroupedCardShape = RoundedCornerShape(20.dp)

/** Hero surfaces (bookshelf header, empty states, summaries). */
val HeroCardShape = RoundedCornerShape(28.dp)

/** Bottom sheets. */
val ModalSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** Dialogs. */
val DialogShape = RoundedCornerShape(28.dp)

/** Book covers and posters. */
val CoverShape = RoundedCornerShape(12.dp)

/** Fully rounded pills (badges, indicators). */
val PillShape = RoundedCornerShape(percent = 50)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = SmallControlShape,
    medium = ButtonShape,
    large = GroupedCardShape,
    extraLarge = DialogShape
)
