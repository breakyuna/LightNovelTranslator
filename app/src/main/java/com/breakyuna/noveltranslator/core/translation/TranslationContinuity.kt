package com.breakyuna.noveltranslator.core.translation

/**
 * How the preceding-context block of a chapter request is produced.
 *
 * - [SOURCE_TAIL] (default): the preceding context comes from source text only, so chapter N can be
 *   translated before chapter N-1 exists. This is what unlocks in-book parallelism.
 * - [TRANSLATION_TAIL]: legacy behaviour, the tail of the previous chapter's translation is injected
 *   and chapter N structurally depends on chapter N-1's output.
 */
object TranslationContinuity {
    const val SOURCE_TAIL = "SOURCE_TAIL"
    const val TRANSLATION_TAIL = "TRANSLATION_TAIL"

    val ALL = setOf(SOURCE_TAIL, TRANSLATION_TAIL)
}
