package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import com.google.re2j.Pattern

object TextLiteralReplacer {
    fun preview(text: String, options: TextReplacementOptions, checkActive: () -> Unit = {}): TextReplacementPreview = try {
        prepare(text, options, checkActive)
    } catch (failure: InvalidTextRule) {
        if (failure.reason == InvalidTextRule.Reason.LIMIT) throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
        throw failure
    }
    private fun prepare(text: String, options: TextReplacementOptions, checkActive: () -> Unit): TextReplacementPreview {
        require(text.length <= 65_536 && '\u0000' !in text)
        TextProjectFingerprint.utf8(text); checkActive()
        val original = ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(text)))
        val budget = TextPolicyProcessor.RuleBudget(checkActive)
        val pattern = Pattern.compile(Pattern.quote(options.find), if (options.caseSensitive) 0 else Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(TextPolicyProcessor.BoundedSequence(text, budget))
        val output = StringBuilder(); var cursor = 0; var count = 0
        fun append(value: String) {
            if (output.length.toLong() + value.length > 65_536)
                throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
            output.append(value)
        }
        while (matcher.find()) {
            checkActive()
            append(text.substring(cursor, matcher.start())); append(options.replacement)
            cursor = matcher.end(); count++
        }
        append(text.substring(cursor)); checkActive()
        val result = output.toString()
        TextProjectFingerprint.utf8(result)
        return TextReplacementPreview(options, original, result, count, result != text)
    }
}
