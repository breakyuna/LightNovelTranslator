package com.breakyuna.noveltranslator.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class PostTranslationRestorerTest {

    // ---- 保护段 -------------------------------------------------------

    @Test
    fun only_removes_extra_preserved_segments_outside_the_ordered_expected_sequence() {
        val rule = createRule(listOf("<[^>]+>"))

        assertEquals(
            "A<1>B<3>C",
            restore(
                rule = rule,
                restorationText = "A<1>B<3>C",
                preparedText = "A<1>B<3>C",
                translation = "A<1>B<x><3><y>C"
            )
        )
        // 期望序列不是实际序列的子序列时不做任何删除。
        assertEquals(
            "A<1><x>B<3>C",
            restore(
                rule = rule,
                restorationText = "A<1>B<2>C",
                preparedText = "A<1>B<2>C",
                translation = "A<1><x>B<3>C"
            )
        )
    }

    @Test
    fun format_placeholders_and_trailing_control_codes_participate_as_whole_segments() {
        // 复刻 RPG Maker 控制符场景：格式占位符与末尾单字母控制符都是完整保护段。
        val rule = TextProtectionPresets.baseRule(
            extraEntries = listOf(
                TextPreserveEntry(pattern = """%[-+ #0]*\d*(?:\.\d+)?[sdxofgec%]"""),
                TextPreserveEntry(pattern = """%\d\$[-+ #0]*\d*(?:\.\d+)?s"""),
                TextPreserveEntry(pattern = """\\[A-Za-z]""")
            )
        )
        val source = "%sHP\u3092%02d\u5FA9\u6975\uFF1A\\G"

        assertEquals(
            "%sHP\u5FA9\u697D\u4E86%02d\u70B9\uFF1A\\G",
            restore(
                rule = rule,
                restorationText = source,
                preparedText = source,
                translation = "%sHP\u5FA9\u697D\u4E86%02d%s\u70B9\uFF1A\\G\\G"
            )
        )
    }

    // ---- 反斜杠段 -------------------------------------------------------

    @Test
    fun restores_backslash_run_lengths_by_the_following_character() {
        val source = "\\\\n[1] \\E"
        assertEquals(
            source,
            restore(
                restorationText = source,
                preparedText = source,
                translation = "\\n[1] \\E"
            )
        )

        // 后继字符不对应时不恢复，避免把不同控制结构误当成同一结构。
        val reordered = "\\b \\a"
        assertEquals(
            reordered,
            restore(
                restorationText = "\\\\a \\\\b",
                preparedText = "\\\\a \\\\b",
                translation = reordered
            )
        )
    }

    // ---- 圆圈数字 -------------------------------------------------------

    @Test
    fun restores_circled_number_forms_when_all_values_line_up() {
        assertEquals(
            "\u2460 2 \u3251",
            restore(
                restorationText = "\u2460 2 \u3251",
                preparedText = "\u2460 2 \u3251",
                translation = "1 2 21"
            )
        )

        val mismatched = "1 3"
        assertEquals(
            mismatched,
            restore(
                restorationText = "\u2460 2",
                preparedText = "\u2460 2",
                translation = mismatched
            )
        )
    }

    // ---- 标点形态 -------------------------------------------------------

    @Test
    fun punctuation_stabilization_cases() {
        val cases = listOf(
            // 源文日式钩括号恢复到译文边界
            Case(
                name = "\u65E5\u5F0F\u94A9\u62EC\u53F7",
                source = "\u300C\u4F60\u597D\u300D",
                translation = "\"\u4F60\u597D\"",
                sourceLanguage = "JA",
                targetLanguage = "JA",
                expected = "\u300C\u4F60\u597D\u300D"
            ),
            // CJK 目标归一弯双引号
            Case(
                name = "CJK \u5F52\u4E00\u5F2F\u53CC\u5F15\u53F7",
                source = "\u201C\u4F60\u597D\u201D",
                translation = "\"\u4F60\u597D\"",
                sourceLanguage = "ZH",
                targetLanguage = "ZH",
                expected = "\u300C\u4F60\u597D\u300D"
            ),
            // 源文行内全角空格保持原形
            Case(
                name = "\u5168\u89D2\u7A7A\u683C",
                source = "\u7532\u3000\u4E59",
                translation = "\u7532 \u4E59",
                sourceLanguage = "JA",
                targetLanguage = "ZH",
                expected = "\u7532\u3000\u4E59"
            ),
            // 非 CJK 译为 CJK 时保留译文全角标点
            Case(
                name = "\u975E CJK \u8BD1 CJK",
                source = "A:B",
                translation = "A\uFF1AB",
                sourceLanguage = "EN",
                targetLanguage = "ZH",
                expected = "A\uFF1AB"
            ),
            // 非 CJK 互译时恢复源文半角标点
            Case(
                name = "\u975E CJK \u4E92\u8BD1",
                source = "A:B",
                translation = "A\uFF1AB",
                sourceLanguage = "EN",
                targetLanguage = "EN",
                expected = "A:B"
            ),
            // CJK 译为非 CJK 时恢复源文全角标点
            Case(
                name = "CJK \u8BD1\u975E CJK",
                source = "A\uFF1AB",
                translation = "A:B",
                sourceLanguage = "JA",
                targetLanguage = "EN",
                expected = "A\uFF1AB"
            )
        )
        for (case in cases) {
            assertEquals(
                case.name,
                case.expected,
                restore(
                    restorationText = case.source,
                    preparedText = case.source,
                    translation = case.translation,
                    sourceLanguage = case.sourceLanguage,
                    targetLanguage = case.targetLanguage
                )
            )
        }
    }

    @Test
    fun punctuation_stabilization_only_touches_unpreserved_text() {
        val rule = createRule(listOf("<[^>]+>"))

        assertEquals(
            "<tag title=\u201Cname\u201D>\u300C\u8BD1\u6587\u300D",
            restore(
                rule = rule,
                restorationText = "<tag title=\u201Cname\u201D>\u201Ctext\u201D",
                preparedText = "<tag title=\u201Cname\u201D>\u201Ctext\u201D",
                translation = "<tag title=\u201Cname\u201D>\u201C\u8BD1\u6587\u201D",
                targetLanguage = "ZH"
            )
        )
    }

    @Test
    fun source_mixing_equivalent_punctuation_keeps_the_translation() {
        val translation = "A\uFF1AB\uFF1AC"
        assertEquals(
            translation,
            restore(
                restorationText = "A:B\uFF1AC",
                preparedText = "A:B\uFF1AC",
                translation = translation,
                sourceLanguage = "EN",
                targetLanguage = "EN"
            )
        )
    }

    @Test
    fun restoration_is_idempotent() {
        val rule = createRule(listOf("<[^>]+>"))
        val source = "\u300C\u2460\\\\n\u300D<x>"
        val translation = "\u201C1\\n\u201D<extra><x>"

        val restored = restore(
            rule = rule,
            restorationText = source,
            preparedText = source,
            translation = translation,
            sourceLanguage = "JA",
            targetLanguage = "ZH"
        )
        assertEquals(
            restored,
            restore(
                rule = rule,
                restorationText = source,
                preparedText = source,
                translation = restored,
                sourceLanguage = "JA",
                targetLanguage = "ZH"
            )
        )
    }

    // ---- 整体恢复 -------------------------------------------------------

    @Test
    fun fully_preserved_body_restores_the_original_text() {
        val rule = TextProtectionPresets.baseRule()
        // 两行都只含 `<br>`：unpreservedText 为空白，因此整体走"保留原文"分支。
        val prepared = prepareTranslationSource(
            src = "<br>\n<br>",
            nameSrc = null,
            preserveRule = rule,
            startOrdinal = 0
        )
        val context = TranslationRestorationContext(
            sourceText = prepared.sourceText,
            preparedLines = prepared.preparedLines,
            preserveRule = rule,
            referenceMappings = prepared.body.mappings
        )
        val restorer = PostTranslationRestorer(sourceLanguage = "JA", targetLanguage = "ZH")

        assertEquals("<br>\n<br>", restorer.restore(context, "\u4EFB\u610F\u8BD1\u6587"))
    }

    @Test
    fun line_count_match_restores_line_by_line_and_keeps_boundary_whitespace() {
        val rule = TextProtectionPresets.baseRule()
        val prepared = prepareTranslationSource(
            src = "  \u7B2C\u4E00\u884C  \n\u7B2C\u4E8C\u884C",
            nameSrc = null,
            preserveRule = rule,
            startOrdinal = 0
        )
        val context = TranslationRestorationContext(
            sourceText = prepared.sourceText,
            preparedLines = prepared.preparedLines,
            preserveRule = rule,
            referenceMappings = prepared.body.mappings
        )
        val restorer = PostTranslationRestorer(sourceLanguage = "JA", targetLanguage = "ZH")

        // 行首行尾空白来自源文，重新包回译文两侧。
        assertEquals(
            "  \u7B2C\u4E00\u884C\u8BD1\u6587  \n\u7B2C\u4E8C\u884C\u8BD1\u6587",
            restorer.restore(context, "\u7B2C\u4E00\u884C\u8BD1\u6587\n\u7B2C\u4E8C\u884C\u8BD1\u6587")
        )
    }

    @Test
    fun line_count_mismatch_replaces_wholesale_and_still_restores_resource_tokens() {
        val rule = TextProtectionPresets.baseRule()
        val prepared = prepareTranslationSource(
            src = "\u770B\u56FE image.png\n\u7ED3\u675F",
            nameSrc = null,
            preserveRule = rule,
            startOrdinal = 0
        )
        val context = TranslationRestorationContext(
            sourceText = prepared.sourceText,
            preparedLines = prepared.preparedLines,
            preserveRule = rule,
            referenceMappings = prepared.body.mappings
        )
        val restorer = PostTranslationRestorer(sourceLanguage = "JA", targetLanguage = "ZH")

        // 模型少给一行：整体替换，但资源 token 仍按映射精确还原。
        assertEquals(
            "\u770B\u56FE image.png",
            restorer.restore(context, "\u770B\u56FE [REF:0]")
        )
    }

    @Test
    fun post_replacement_only_touches_text_between_resource_tokens() {
        val rule = TextProtectionPresets.baseRule()
        val prepared = prepareTranslationSource(
            src = "\u770B\u56FE image.png",
            nameSrc = null,
            preserveRule = rule,
            startOrdinal = 0
        )
        val context = TranslationRestorationContext(
            sourceText = prepared.sourceText,
            preparedLines = prepared.preparedLines,
            preserveRule = rule,
            referenceMappings = prepared.body.mappings
        )
        val restorer = PostTranslationRestorer(
            sourceLanguage = "JA",
            targetLanguage = "ZH",
            postReplacements = TextReplacements(
                listOf(TextReplacementEntry(src = "\u56FE", dst = "\u56FE\u5F62", caseSensitive = true))
            )
        )

        // "图" 在 token 之外被替换，token 自身完好，最后再还原成真实引用。
        assertEquals(
            "\u770B\u56FE\u5F62 image.png",
            restorer.restore(context, "\u770B\u56FE [REF:0]")
        )
    }

    @Test
    fun null_preserve_rule_still_restores_punctuation_forms() {
        assertEquals(
            "\u300C\u4F60\u597D\u300D",
            restore(
                rule = null,
                restorationText = "\u300C\u4F60\u597D\u300D",
                preparedText = "\u300C\u4F60\u597D\u300D",
                translation = "\"\u4F60\u597D\""
            )
        )
    }

    private data class Case(
        val name: String,
        val source: String,
        val translation: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val expected: String
    )

    /** 默认使用日译中配置，各用例显式提供恢复依据与响应。 */
    private fun restore(
        restorationText: String,
        preparedText: String,
        translation: String,
        rule: TextProtectionRule? = null,
        sourceLanguage: String = "JA",
        targetLanguage: String = "ZH"
    ): String = restoreTranslationLine(
        restorationText = restorationText,
        preparedText = preparedText,
        translation = translation,
        preserveRule = rule,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage
    )

    /** 用测试自有表达式构造保护规则。 */
    private fun createRule(sources: List<String>): TextProtectionRule =
        TextProtectionPresets.baseRule(extraEntries = sources.map { TextPreserveEntry(pattern = it) })
}
