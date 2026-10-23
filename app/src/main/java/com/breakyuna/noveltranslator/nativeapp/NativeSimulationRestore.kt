package com.breakyuna.noveltranslator.nativeapp

/** Saved navigation belongs to one execution token. Invalid history falls back to its fixed page. */
internal fun simulationCursors(savedToken: String?, token: String, remembered: List<Long>?, afterId: Long): List<Long> {
    require(afterId >= 0)
    return remembered?.takeIf { savedToken == token && it.firstOrNull() == 0L &&
        it.lastOrNull() == afterId && it.zipWithNext().all { (a, b) -> a < b } }?.toList()
        ?: if (afterId == 0L) listOf(0L) else listOf(0L, afterId)
}
