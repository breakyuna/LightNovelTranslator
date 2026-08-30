package com.breakyuna.noveltranslator.ui.screens.reader

import androidx.compose.ui.text.font.FontFamily
import com.breakyuna.noveltranslator.data.model.DisplayMode
import com.breakyuna.noveltranslator.data.model.PageAnimation
import com.breakyuna.noveltranslator.data.model.PagingMode
import com.breakyuna.noveltranslator.data.model.ReaderBackground
import com.breakyuna.noveltranslator.data.model.ReaderFontFamily
import com.breakyuna.noveltranslator.data.model.ReaderLayoutMode
import com.breakyuna.noveltranslator.data.model.ReaderProgressEntity

data class ReaderSettingsState(
    val displayMode: DisplayMode = DisplayMode.TRANSLATION,
    val pagingMode: PagingMode = PagingMode.CONTINUOUS,
    val layoutMode: ReaderLayoutMode = ReaderLayoutMode.CLEAN,
    val animation: PageAnimation = PageAnimation.SLIDE,
    val fontSizeSp: Float = 18f,
    val fontFamily: ReaderFontFamily = ReaderFontFamily.SYSTEM,
    val letterSpacingSp: Float = 0f,
    val lineSpacingMultiplier: Float = 1.35f,
    val paragraphSpacingDp: Float = 10f,
    val pageMarginDp: Float = 18f,
    val useTraditionalChinese: Boolean = false,
    val background: ReaderBackground = ReaderBackground.SYSTEM
)

fun ReaderFontFamily.toComposeFontFamily(): FontFamily = when (this) {
    ReaderFontFamily.SYSTEM -> FontFamily.Default
    ReaderFontFamily.SERIF -> FontFamily.Serif
    ReaderFontFamily.SANS_SERIF -> FontFamily.SansSerif
    ReaderFontFamily.MONOSPACE -> FontFamily.Monospace
}

fun ReaderProgressEntity.toReaderSettings(): ReaderSettingsState = ReaderSettingsState(
    displayMode = enumOrDefault(displayMode, DisplayMode.TRANSLATION),
    pagingMode = enumOrDefault(pagingMode, PagingMode.CONTINUOUS),
    layoutMode = enumOrDefault(readerLayoutMode, ReaderLayoutMode.CLEAN),
    animation = enumOrDefault(pageAnimation, PageAnimation.SLIDE),
    fontSizeSp = fontSizeSp.takeIf { it.isFinite() }?.coerceIn(14f, 32f) ?: 18f,
    fontFamily = enumOrDefault(fontFamily, ReaderFontFamily.SYSTEM),
    letterSpacingSp = letterSpacingSp.takeIf { it.isFinite() }?.coerceIn(0f, 3f) ?: 0f,
    lineSpacingMultiplier = lineSpacingMultiplier.takeIf { it.isFinite() }?.coerceIn(1.1f, 2.4f) ?: 1.35f,
    paragraphSpacingDp = paragraphSpacingDp.takeIf { it.isFinite() }?.coerceIn(0f, 32f) ?: 10f,
    pageMarginDp = pageMarginDp.takeIf { it.isFinite() }?.coerceIn(8f, 64f) ?: 18f,
    useTraditionalChinese = useTraditionalChinese,
    background = enumOrDefault(readerBackground, ReaderBackground.SYSTEM)
)

private inline fun <reified T : Enum<T>> enumOrDefault(value: String, fallback: T): T =
    runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
