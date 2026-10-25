package com.breakyuna.noveltranslator.nativecore.workspace

import android.icu.text.BreakIterator
import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Pure bounded text operations. Safe inside the disposable isolated process, minSdk 24. */
internal class WorkspaceUnicodeHost : QuickJsWorkspaceScriptEngine.LgUnicodeHost {
    override fun normalize(text: String, form: String): String {
        validate(text)
        val mode = when (form) {
            "NFC" -> Normalizer.Form.NFC
            "NFD" -> Normalizer.Form.NFD
            "NFKC" -> Normalizer.Form.NFKC
            "NFKD" -> Normalizer.Form.NFKD
            else -> throw IllegalArgumentException("Invalid normalization form")
        }
        return Normalizer.normalize(text, mode).also { require(it.length <= MAX_RESULT_CHARS) }
    }

    override fun segments(text: String): String {
        validate(text)
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        var start = iterator.first()
        var outputChars = 0L
        return buildJsonArray {
            while (true) {
                val end = iterator.next()
                if (end == BreakIterator.DONE) break
                outputChars += (end - start).toLong() + 64
                require(outputChars <= MAX_RESULT_CHARS) { "Unicode output exceeds budget" }
                add(buildJsonObject {
                    put("segment", text.substring(start, end))
                    put("index", start)
                })
                start = end
            }
        }.toString().also { require(it.length <= MAX_RESULT_CHARS) }
    }

    private fun validate(text: String) {
        require(text.length <= MAX_INPUT_CHARS) { "Unicode input exceeds budget" }
        requireWellFormedUnicode(text)
    }

    companion object {
        const val MAX_INPUT_CHARS = 65_536
        const val MAX_RESULT_CHARS = 4 * 1024 * 1024
    }
}
