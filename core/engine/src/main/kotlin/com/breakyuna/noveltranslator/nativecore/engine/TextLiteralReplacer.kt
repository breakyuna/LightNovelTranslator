package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*

/** The same explicit replacement semantics are used by draft preview and transactional query replacement. */
object TextLiteralReplacer {
    fun preview(text: String, options: TextReplacementOptions, checkActive: () -> Unit = {}): TextReplacementPreview =
        LiteralTextReplacement.preview(text, options, checkActive)
}
