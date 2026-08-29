package com.breakyuna.noveltranslator.data.model

/** Lightweight preview row kept in Compose state; chapter bodies remain in the ViewModel cache. */
data class ChapterSplitPreview(
    val index: Int,
    val title: String,
    val wordCount: Int
)
