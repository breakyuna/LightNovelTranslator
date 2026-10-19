package com.breakyuna.noveltranslator.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationQualityRulesTest {

    // ---- 外文残留 -------------------------------------------------------

    @Test
    fun collects_continuous_foreign_residue_fragments_and_deduplicates_stably() {
        assertEquals(
            listOf(
                "\u304B\u306A",
                "\u30AB\u30CA\u304B\u306A",
                "OpenAI",
                "\u0442\u0435\u043A\u0441\u0442",
                "\uD55C\uAE00"
            ),
            TranslationQualityRules.collectForeignResidueFragments(
                text = "\u7532\u304B\u306A\u4E59\u30AB\u30CA\u304B\u306A\uFF0COpenAI\uFF0C" +
                    "\u0442\u0435\u043A\u0441\u0442\uFF0C\uD55C\uAE00\uFF0C\u304B\u306A",
                targetLanguage = "ZH"
            )
        )
    }

    @Test
    fun keeps_combining_marks_inside_residue_clusters_and_ignores_neutral_content() {
        assertEquals(
            listOf("\u304B\u3099", "\uFF76\uFF9E", "\u309B"),
            TranslationQualityRules.collectForeignResidueFragments(
                text = "\u304B\u3099 \uFF76\uFF9E \u309B 123 \uD83D\uDC69\u200D\uD83D\uDCBB",
                targetLanguage = "ZH"
            )
        )
    }

    @Test
    fun ignores_isolated_latin_graphemes_and_reports_other_writing_systems() {
        assertEquals(
            listOf("A\u3042", "\uD55C", "\u03B2"),
            TranslationQualityRules.collectForeignResidueFragments(
                text = "\u6309 A/X \u952E\uFF0C\u00E9\uFF0Ce\u0301\uFF0C\uFF21\uFF1BA\u3042\uFF1B" +
                    "\uD55C\uFF1B\u03B2",
                targetLanguage = "ZH"
            )
        )
    }

    @Test
    fun exempts_two_to_four_ascii_uppercase_fragments_and_reports_longer_or_mixed_ones() {
        assertEquals(
            listOf("ABCDE", "Ab", "OpenAI", "\uFF21\uFF22", "\u00C9A", "E\u0301A"),
            TranslationQualityRules.collectForeignResidueFragments(
                text = "AB ABC ABCD ABCDE Ab OpenAI \uFF21\uFF22 \u00C9A E\u0301A",
                targetLanguage = "ZH"
            )
        )
    }

    @Test
    fun each_target_language_allows_its_own_writing_system() {
        val cases = listOf(
            "ZH" to "\u4E2D\u6587",
            "ZH-HANT" to "\u7E41\u9AD4\u4E2D\u6587",
            "JA" to "\u6771\u4EAC\u3078\u884C\u304F",
            "KO" to "\u6771\u4EAC\uC5D0 \uAC04\uB2E4",
            "EN" to "cafe\u0301",
            "RU" to "\u0420\u0443\u0441\u0441\u043A\u0438\u0439",
            "AR" to "\u0627\u0644\u0652\u0639\u064E\u0631\u064E\u0628\u0650\u064A\u064E\u0651\u0629\u064F\u0640",
            "TH" to "\u0E20\u0E32\u0E29\u0E32\u0E44\u0E17\u0E48\u0E30"
        )
        for ((language, text) in cases) {
            assertEquals(
                text,
                emptyList<String>(),
                TranslationQualityRules.collectForeignResidueFragments(text, language)
            )
        }
    }

    @Test
    fun each_target_language_reports_other_writing_systems() {
        val cases = listOf(
            "ZH" to "Latin",
            "JA" to "\uD55C\uAE00",
            "KO" to "\u304B\u306A",
            "EN" to "\u041A\u0438\u0440\u0438\u043B\u043B\u0438\u0446\u0430",
            "RU" to "Latin",
            "AR" to "Latin",
            "TH" to "Latin"
        )
        for ((language, text) in cases) {
            assertEquals(
                text,
                listOf(text),
                TranslationQualityRules.collectForeignResidueFragments(text, language)
            )
        }
    }

    @Test
    fun unknown_target_language_disables_the_residue_check() {
        assertEquals(
            emptyList<String>(),
            TranslationQualityRules.collectForeignResidueFragments("Latin \u304B\u306A", "XX-UNKNOWN")
        )
    }

    // ---- 相似度 ---------------------------------------------------------

    @Test
    fun non_empty_text_is_similar_when_contained_or_highly_overlapping() {
        assertTrue(TranslationQualityRules.isTranslationTextSimilar("alpha", "alpha!"))
        assertTrue(TranslationQualityRules.isTranslationTextSimilar("abcdefghij", "abcdefghik"))
        assertFalse(TranslationQualityRules.isTranslationTextSimilar("abc", "abd"))
        assertFalse(TranslationQualityRules.isTranslationTextSimilar("", "alpha"))
    }

    @Test
    fun similarity_issue_requires_non_chinese_evidence_when_translating_ja_or_ko_to_chinese() {
        val cases = listOf(
            Triple("\u6771\u4EAC", "\u6771\u4EAC", false),
            Triple("\u6771\u4EAC", "\u6771\u4EAC\u3042", true),
            Triple("\u6771\u4EACA", "\u6771\u4EACA", false),
            Triple("\u56FD\u969B", "\u56FD\u969B", false),
            Triple("\u56FD\u969BHTTP", "\u56FD\u969BHTTP", false),
            Triple("\u56FD\u969B", "\u56FD\u969B\uD55C", true)
        )
        for ((src, dst, expected) in cases) {
            val hasResidue = TranslationQualityRules.collectForeignResidueFragments(dst, "ZH").isNotEmpty()
            assertEquals(
                "$src/$dst",
                expected,
                TranslationQualityRules.hasTranslationSimilarityIssue(
                    src = src,
                    dst = dst,
                    sourceLanguage = "JA",
                    targetLanguage = "ZH",
                    hasForeignResidue = hasResidue
                )
            )
        }
    }

    @Test
    fun similarity_is_reported_outside_ja_ko_to_chinese_translation() {
        assertTrue(
            TranslationQualityRules.hasTranslationSimilarityIssue(
                src = "same text",
                dst = "same text",
                sourceLanguage = "EN",
                targetLanguage = "ZH",
                hasForeignResidue = true
            )
        )
        assertTrue(
            TranslationQualityRules.hasTranslationSimilarityIssue(
                src = "\u6771\u4EAC",
                dst = "\u6771\u4EAC",
                sourceLanguage = "JA",
                targetLanguage = "EN",
                hasForeignResidue = false
            )
        )
    }

    // ---- 标点结构 -------------------------------------------------------

    @Test
    fun punctuation_structure_mismatch_detects_added_removed_and_half_pairs() {
        assertTrue(
            TranslationQualityRules.hasPunctuationStructureMismatch(
                src = "\u4ED6\u8BF4\uFF1A\u201C\u597D\u201D",
                dst = "\u4ED6\u8BF4\u597D"
            )
        )
        // 同组错配会通过：引号允许不同语言的排版形式。
        assertFalse(
            TranslationQualityRules.hasPunctuationStructureMismatch(
                src = "\u4ED6\u8BF4\uFF1A\u201C\u597D\u201D",
                dst = "\u4ED6\u8BF4\uFF1A\u300C\u597D\u300D"
            )
        )
        assertFalse(
            TranslationQualityRules.hasPunctuationStructureMismatch(
                src = "\u7EAF\u6587\u672C",
                dst = "\u7EAF\u7EAF\u6587\u672C\u6587\u672C"
            )
        )
    }

    @Test
    fun punctuation_structure_ignores_body_text_and_line_breaks() {
        assertEquals(
            listOf("ROUND_OPEN", "ROUND_CLOSE", "TITLE_OPEN", "TITLE_CLOSE"),
            TranslationQualityRules.collectPunctuationStructure("\u524D(\u4E2D)\u540E\u300A\u6807\u300B\n")
        )
    }

    // ---- 行 / 段数 ------------------------------------------------------

    @Test
    fun paragraph_count_counts_blank_line_separated_blocks() {
        assertEquals(3, TranslationQualityRules.countParagraphs("\u4E00\n\n\u4E8C\n\u4E09\n\n\n\u56DB"))
        assertEquals(0, TranslationQualityRules.countParagraphs("\n\n  \n"))
        assertEquals(1, TranslationQualityRules.countParagraphs("\u53EA\u6709\u4E00\u6BB5"))
    }

    @Test
    fun check_translation_quality_reports_every_issue_with_code_and_detail() {
        val src = "\u7B2C\u4E00\u6BB5\u3002\n\n\u7B2C\u4E8C\u6BB5\uFF08A\uFF09\u3002"
        val dst = "\u7B2C\u4E00\u6BB5\u3002\n\u7B2C\u4E8C\u6BB5\u3002 Latin"

        val issues = TranslationQualityRules.checkTranslationQuality(
            src = src,
            dst = dst,
            sourceLanguage = "JA",
            targetLanguage = "ZH"
        )
        val codes = issues.map { it.code }.toSet()

        assertTrue(codes.contains(QualityIssueCode.FOREIGN_RESIDUE))
        assertTrue(codes.contains(QualityIssueCode.PUNCTUATION_STRUCTURE))
        assertTrue(codes.contains(QualityIssueCode.LINE_COUNT_MISMATCH))
        assertTrue(codes.contains(QualityIssueCode.PARAGRAPH_COUNT_MISMATCH))
        // 每项都带可读详情，供调用方记录具体检查项。
        assertTrue(issues.all { it.detail.isNotBlank() })
    }

    @Test
    fun check_translation_quality_reports_nothing_for_a_clean_translation() {
        val issues = TranslationQualityRules.checkTranslationQuality(
            src = "\u4ED6\u8BF4\uFF1A\u201C\u4F60\u597D\u201D\u3002",
            dst = "\u4ED6\u8BF4\uFF1A\u201C\u4F60\u597D\u201D\u3002",
            sourceLanguage = "JA",
            targetLanguage = "ZH"
        )

        assertEquals(emptyList<QualityIssue>(), issues)
    }

    @Test
    fun check_translation_quality_ignores_protected_segments_when_a_rule_is_given() {
        val rule = TextProtectionPresets.baseRule(
            extraEntries = listOf(TextPreserveEntry(pattern = "<[^>]+>"))
        )
        val src = "\u539F\u6587 <span class=\"a\"> \u7ED3\u5C3E"
        val dst = "\u8BD1\u6587 <span class=\"a\"> \u7ED3\u5C3E"

        // 不带保护规则时，HTML 标签里的拉丁内容会被当成外文残留。
        assertTrue(TranslationQualityRules.collectForeignResidueFragments(dst, "ZH").isNotEmpty())

        val issues = TranslationQualityRules.checkTranslationQuality(
            src = src,
            dst = dst,
            sourceLanguage = "JA",
            targetLanguage = "ZH",
            preserveRule = rule
        )
        assertFalse(issues.any { it.code == QualityIssueCode.FOREIGN_RESIDUE })
    }

    @Test
    fun jaccard_similarity_is_based_on_the_utf16_character_set() {
        assertEquals(1.0, TranslationQualityRules.jaccardSimilarity("abc", "cab"), 1e-9)
        assertEquals(0.5, TranslationQualityRules.jaccardSimilarity("abc", "abd"), 1e-9)
        assertEquals(0.0, TranslationQualityRules.jaccardSimilarity("", ""), 1e-9)
    }
}
