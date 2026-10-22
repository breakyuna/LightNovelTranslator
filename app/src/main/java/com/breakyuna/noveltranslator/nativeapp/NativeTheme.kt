package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun NativeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme(
        primary = Color(0xfff49a51), onPrimary = Color(0xff25160a),
        background = Color(0xff111318), surface = Color(0xff171a20),
        onBackground = Color(0xffeef1f5), onSurface = Color(0xffeef1f5), outline = Color(0xff343a44),
    ) else lightColorScheme(
        primary = Color(0xffad5a17), onPrimary = Color.White,
        background = Color(0xfff3f4f6), surface = Color(0xfffbfcfd),
        onBackground = Color(0xff25272c), onSurface = Color(0xff25272c), outline = Color(0xffd6dae0),
    ), content = content)
}
