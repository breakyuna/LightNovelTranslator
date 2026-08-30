package com.breakyuna.noveltranslator.ui.screens.reader

import android.annotation.SuppressLint
import android.icu.text.Transliterator
import android.os.Build

@SuppressLint("NewApi")
private fun createTransliterator(id: String): Transliterator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Transliterator.getInstance(id)
    } else {
        null
    }

private val simplifiedToTraditional by lazy {
    createTransliterator("Simplified-Traditional")
}

private val traditionalToSimplified by lazy {
    createTransliterator("Traditional-Simplified")
}

/** Converts only rendered text; image markers are split before this function is called. */
fun convertChineseVariant(text: String, useTraditionalChinese: Boolean): String =
    if (useTraditionalChinese) {
        simplifiedToTraditional?.transliterate(text) ?: text
    } else {
        traditionalToSimplified?.transliterate(text) ?: text
    }
