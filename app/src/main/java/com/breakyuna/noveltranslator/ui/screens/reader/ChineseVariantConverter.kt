package com.breakyuna.noveltranslator.ui.screens.reader

import android.icu.text.Transliterator

private val simplifiedToTraditional by lazy {
    Transliterator.getInstance("Simplified-Traditional")
}

private val traditionalToSimplified by lazy {
    Transliterator.getInstance("Traditional-Simplified")
}

/** Converts only rendered text; image markers are split before this function is called. */
fun convertChineseVariant(text: String, useTraditionalChinese: Boolean): String =
    if (useTraditionalChinese) {
        simplifiedToTraditional.transliterate(text)
    } else {
        traditionalToSimplified.transliterate(text)
    }
