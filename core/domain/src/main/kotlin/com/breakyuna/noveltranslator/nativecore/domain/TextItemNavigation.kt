package com.breakyuna.noveltranslator.nativecore.domain

enum class TextItemDirection { PREVIOUS, NEXT }

/** Ordered query navigation at the exact editor revision; no write or implicit draft save. */
interface TextItemNavigationPort {
    suspend fun adjacentTextItem(ref: LocalProjectRef, revision: Long, query: TextItemQuery,
        anchor: ItemId, direction: TextItemDirection): TextItemDetails?
}
