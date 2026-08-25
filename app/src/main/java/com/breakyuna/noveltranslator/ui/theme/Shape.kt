package com.breakyuna.noveltranslator.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val SmallControlShape = RoundedCornerShape(10.dp)
val ButtonShape = RoundedCornerShape(12.dp)
val GroupedCardShape = RoundedCornerShape(14.dp)
val ModalSheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
val DialogShape = RoundedCornerShape(20.dp)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = SmallControlShape,
    medium = ButtonShape,
    large = GroupedCardShape,
    extraLarge = DialogShape
)
