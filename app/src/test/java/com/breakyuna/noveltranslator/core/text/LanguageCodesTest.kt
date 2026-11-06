package com.breakyuna.noveltranslator.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageCodesTest {

    @Test
    fun normalizes_case_and_whitespace_and_rejects_unknown_codes() {
        assertEquals("ZH", LanguageCodes.normalizeLanguageCode(" zh "))
        assertEquals("ZH-HANT", LanguageCodes.normalizeLanguageCode("ZH-HANT"))
        assertEquals(null, LanguageCodes.normalizeLanguageCode("XX"))
        assertEquals(null, LanguageCodes.normalizeLanguageCode(""))
    }

    @Test
    fun source_codes_accept_all_but_reject_traditional_chinese() {
        // 源语言接受 ALL（关闭语言过滤）。
        assertEquals("ALL", LanguageCodes.normalizeSourceLanguageCode("all"))
        assertEquals("JA", LanguageCodes.normalizeSourceLanguageCode("ja"))
        assertEquals(null, LanguageCodes.normalizeSourceLanguageCode("ZH-HANT"))
    }

    @Test
    fun target_codes_reject_all() {
        assertEquals("ZH-HANT", LanguageCodes.normalizeTargetLanguageCode("zh-hant"))
        assertEquals(null, LanguageCodes.normalizeTargetLanguageCode("ALL"))
        assertEquals(null, LanguageCodes.normalizeTargetLanguageCode("JP"))
    }

    @Test
    fun cjk_detection_covers_chinese_japanese_and_korean_only() {
        for (code in listOf("ZH", "ZH-HANT", "JA", "KO", "zh", " ja ")) {
            assertTrue(code, LanguageCodes.isCjkLanguageCode(code))
        }
        for (code in listOf("EN", "RU", "TH", "AR", "XX", "")) {
            assertFalse(code, LanguageCodes.isCjkLanguageCode(code))
        }
    }

    @Test
    fun cjk_character_detection_finds_body_characters_only() {
        assertTrue(LanguageCodes.hasCjkLanguageCharacter("\u6F22\u5B57\u3068\u30AB\u30BF\u30AB\u30CA"))
        assertTrue(LanguageCodes.hasCjkLanguageCharacter("\uD55C\uAE00"))
        assertFalse(LanguageCodes.hasCjkLanguageCharacter("Latin only 123"))
        assertFalse(LanguageCodes.hasCjkLanguageCharacter(""))
    }

    @Test
    fun grapheme_classification_separates_allowed_latin_and_other_residue() {
        assertEquals(
            LanguageCodes.GraphemeClassification.ALLOWED,
            LanguageCodes.classifyGrapheme("\u6F22", "ZH")
        )
        assertEquals(
            LanguageCodes.GraphemeClassification.LATIN_RESIDUE,
            LanguageCodes.classifyGrapheme("A", "ZH")
        )
        assertEquals(
            LanguageCodes.GraphemeClassification.OTHER_RESIDUE,
            LanguageCodes.classifyGrapheme("\u3042", "ZH")
        )
        assertEquals(
            LanguageCodes.GraphemeClassification.NEUTRAL,
            LanguageCodes.classifyGrapheme("\uFF01", "ZH")
        )
        assertEquals(
            LanguageCodes.GraphemeClassification.ALLOWED,
            LanguageCodes.classifyGrapheme("\u3042", "JA")
        )
    }

    @Test
    fun kana_auxiliary_characters_belong_to_kana_even_though_their_script_is_common() {
        // 长音符 ー 与半角浊点 ﾞ 都是 Script=Common，必须靠附属白名单归入假名。
        for (grapheme in listOf("\u30FC", "\uFF9E", "\u309B", "\u3099")) {
            assertEquals(
                grapheme,
                LanguageCodes.GraphemeClassification.OTHER_RESIDUE,
                LanguageCodes.classifyGrapheme(grapheme, "ZH")
            )
        }
    }

    @Test
    fun unknown_or_all_language_codes_disable_classification() {
        for (code in listOf("XX", "", LanguageCodes.ALL_LANGUAGE_CODE)) {
            assertEquals(
                code,
                LanguageCodes.GraphemeClassification.NEUTRAL,
                LanguageCodes.classifyGrapheme("A\u3042", code)
            )
        }
    }
}
