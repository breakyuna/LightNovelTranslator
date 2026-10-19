package com.breakyuna.noveltranslator.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSourcePreparerTest {

    @Test
    fun boundary_protected_segments_join_pre_replacement_and_restoration_keeps_pre_replacement_text() {
        val rule = TextProtectionPresets.baseRule(
            extraEntries = listOf(TextPreserveEntry(pattern = "<[^>]+>"))
        )
        val prepared = prepareTranslationSource(
            src = "  <A>one</A>  ",
            nameSrc = null,
            textType = "TXT",
            cleanRuby = false,
            preserveRule = rule,
            preReplacements = TextReplacements(
                listOf(
                    TextReplacementEntry(src = "one", dst = "<Q>one", caseSensitive = true),
                    TextReplacementEntry(src = "A", dst = "B", caseSensitive = true)
                )
            ),
            startOrdinal = 0
        )

        val line = prepared.preparedLines.single()
        assertTrue(line is TranslatableSourceLine)
        line as TranslatableSourceLine
        assertEquals("<A>one</A>", line.restorationText)
        assertEquals("  <B><Q>one</B>  ", line.preparedText)
        assertEquals("  ", line.leadingWhitespace)
        assertEquals("  ", line.trailingWhitespace)
        assertEquals("  <B><Q>one</B>  ", line.preserveAnalysis.text)
        assertTrue(Regex("""^\s+one\s+$""").matches(line.preserveAnalysis.unpreservedText))
    }

    @Test
    fun full_entry_isolates_resources_first_then_cleans_ruby_and_applies_replacements() {
        val rule = TextProtectionPresets.baseRule()
        val prepared = prepareTranslationSource(
            src = "\u7518\u5CB8\u4E45\u5F25[https://mypage.syosetu.com/1300935/]\r\n" +
                "[\u6F22\u5B57/\u304B\u3093\u3058]: https://example.com/(a)",
            nameSrc = "[\u59D3\u540D/\u305B\u3044\u3081\u3044]: https://example.com/name",
            textType = "NONE",
            cleanRuby = true,
            preserveRule = rule,
            preReplacements = TextReplacements(
                listOf(TextReplacementEntry(src = ":", dst = "\uFF1A", caseSensitive = true))
            ),
            startOrdinal = 4
        )

        // 姓名按整段投影，不执行正文清理与替换。
        assertEquals("[\u59D3\u540D/\u305B\u3044\u3081\u3044]: [REF:4]", prepared.name?.text)
        assertEquals(
            listOf(
                // 源文把 URL 包在方括号里，token 自身也带方括号，因此投影后是双层括号；
                // 还原时按 token 精确替换，括号成对且不丢。
                "\u7518\u5CB8\u4E45\u5F25[[REF:5]]",
                "\u6F22\u5B57\uFF1A [REF:6]"
            ),
            prepared.preparedLines.map { it.preparedText }
        )
        assertEquals(
            listOf(
                "https://mypage.syosetu.com/1300935/",
                "https://example.com/(a)"
            ),
            prepared.body.mappings.map { it.value }
        )
        assertEquals(7, prepared.body.nextOrdinal)
    }

    @Test
    fun blank_lines_and_fully_protected_lines_are_marked_preserved() {
        val rule = TextProtectionPresets.baseRule(
            extraEntries = listOf(TextPreserveEntry(pattern = """\d+"""))
        )
        val prepared = prepareTranslationSource(
            src = "\u7B2C\u4E00\u884C\n\n   \n12345\n\u6700\u540E\u4E00\u884C",
            nameSrc = null,
            preserveRule = rule,
            startOrdinal = 0
        )

        assertEquals(5, prepared.preparedLines.size)
        // 空行与纯空白行：preparedText 保持原始行，供译后原样写回。
        assertEquals(PreservedSourceLine("", rule.analyze("")), prepared.preparedLines[1])
        assertEquals(PreservedSourceLine("   ", rule.analyze("   ")), prepared.preparedLines[2])
        // 纯保护行：unpreservedText 为空，因此不建立译后恢复状态。
        val protected = prepared.preparedLines[3]
        assertTrue(protected is PreservedSourceLine)
        assertEquals("12345", protected.preparedText)
        assertTrue(protected.preserveAnalysis.unpreservedText.isBlank())
        // 正常行仍是可翻译行。
        assertTrue(prepared.preparedLines[4] is TranslatableSourceLine)
    }

    @Test
    fun crlf_and_cr_line_endings_are_normalized_to_lf() {
        val rule = TextProtectionPresets.baseRule()
        val prepared = prepareTranslationSource(
            src = "\u4E00\r\n\u4E8C\r\u4E09",
            nameSrc = null,
            preserveRule = rule,
            startOrdinal = 0
        )

        assertEquals(listOf("\u4E00", "\u4E8C", "\u4E09"), prepared.preparedLines.map { it.preparedText })
        assertEquals("\u4E00\r\n\u4E8C\r\u4E09", prepared.sourceText)
    }

    @Test
    fun samples_are_deduplicated_and_markdown_adds_format_samples() {
        val rule = TextProtectionPresets.baseRule()
        val prepared = prepareTranslationSource(
            src = "<br>\u4E00\n\u4E8C<br>\u4E09",
            nameSrc = null,
            textType = "md",
            preserveRule = rule,
            startOrdinal = 0
        )

        // `<br>` 在多行重复出现，只保留一份；每个可翻译行附带 Markdown 格式说明。
        assertEquals(listOf("<br>", "Markdown Code"), prepared.samples)
    }

    @Test
    fun ruby_cleaning_is_skipped_for_script_text_types() {
        val rule = TextProtectionPresets.baseRule()
        val aggressive = prepareTranslationSource(
            src = "[\u6F22\u5B57/\u304B\u3093\u3058]",
            nameSrc = null,
            textType = "NONE",
            cleanRuby = true,
            preserveRule = rule,
            startOrdinal = 0
        )
        val excluded = prepareTranslationSource(
            src = "[\u6F22\u5B57/\u304B\u3093\u3058]",
            nameSrc = null,
            textType = "RPGMAKER",
            cleanRuby = true,
            preserveRule = rule,
            startOrdinal = 0
        )

        assertEquals("\u6F22\u5B57", aggressive.preparedLines.single().preparedText)
        // RPGMAKER 的方括号很可能是控制语法，不套用括号类激进规则。
        assertEquals("[\u6F22\u5B57/\u304B\u3093\u3058]", excluded.preparedLines.single().preparedText)
    }

    @Test
    fun empty_source_produces_a_single_preserved_line() {
        val rule = TextProtectionPresets.baseRule()
        val prepared = prepareTranslationSource(
            src = "",
            nameSrc = null,
            preserveRule = rule,
            startOrdinal = 0
        )

        assertEquals(1, prepared.preparedLines.size)
        assertTrue(prepared.preparedLines.single() is PreservedSourceLine)
        assertEquals(emptyList<String>(), prepared.samples)
    }

    @Test
    fun resource_tokens_survive_ruby_cleaning_and_pre_replacement() {
        val rule = TextProtectionPresets.baseRule()
        val prepared = prepareTranslationSource(
            src = "\u770B\u56FE image.png \u7ED3\u675F",
            nameSrc = null,
            textType = "NONE",
            cleanRuby = true,
            preserveRule = rule,
            preReplacements = TextReplacements(
                listOf(TextReplacementEntry(src = "image", dst = "\u56FE\u7247", caseSensitive = true))
            ),
            startOrdinal = 0
        )

        // 资源先被投影成 token，本地清理和替换都绕开它，token 因此完好无损。
        assertEquals("\u770B\u56FE [REF:0] \u7ED3\u675F", prepared.preparedLines.single().preparedText)
        assertEquals(listOf("image.png"), prepared.body.mappings.map { it.value })
    }

    // ---- TextReplacements ----------------------------------------------

    @Test
    fun literal_replacements_respect_case_sensitivity_and_run_in_order() {
        val replacements = TextReplacements(
            listOf(
                TextReplacementEntry(src = "HP", dst = "\u751F\u547D\u503C", caseSensitive = true),
                // 第二条看到的是第一条的输出，因此"生命值"会被再次改写。
                TextReplacementEntry(src = "\u751F\u547D\u503C", dst = "HP\u503C", caseSensitive = true)
            )
        )

        // 大小写敏感：小写 hp 不被第一条命中。
        assertEquals("HP\u503C hp", replacements.apply("HP hp"))
    }

    @Test
    fun literal_replacements_use_nfkc_and_casefold_semantics() {
        val replacements = TextReplacements(
            listOf(TextReplacementEntry(src = "HP", dst = "\u751F\u547D\u503C", caseSensitive = false))
        )

        // 全角 ＨＰ 归一后命中同一条规则。
        assertEquals("\u751F\u547D\u503C", replacements.apply("\uFF28\uFF30"))
    }

    @Test
    fun regex_replacements_support_capture_groups_and_ignore_case() {
        val replacements = TextReplacements(
            listOf(
                TextReplacementEntry(
                    src = """\[(\w+)\]""",
                    dst = "<$1>",
                    regex = true,
                    caseSensitive = false
                )
            )
        )

        // 分组保留原文形态；`\w+` 要求至少一个字符，空方括号不命中。
        assertEquals("a<TAG>b c<xy>d e[]f", replacements.apply("a[TAG]b c[xy]d e[]f"))
    }

    @Test
    fun regex_replacement_treats_a_lone_dollar_as_literal_text() {
        val replacements = TextReplacements(
            listOf(
                TextReplacementEntry(src = "PRICE", dst = "100$", regex = true, caseSensitive = true)
            )
        )

        // `$` 后面不是合法分组引用时按字面量写入，不抛异常。
        assertEquals("\u4EF7\u683C100$", replacements.apply("\u4EF7\u683CPRICE"))
    }

    @Test
    fun blank_replacement_sources_are_skipped() {
        val replacements = TextReplacements(
            listOf(
                TextReplacementEntry(src = "", dst = "\u7A7A", caseSensitive = true),
                TextReplacementEntry(src = "a", dst = "b", caseSensitive = true)
            )
        )

        // 空白 src 不参与替换，也不影响后续规则。
        assertEquals("bbc", replacements.apply("aac"))
    }

    @Test
    fun later_rules_see_the_output_of_earlier_rules() {
        val replacements = TextReplacements(
            listOf(
                TextReplacementEntry(src = "a", dst = "b", caseSensitive = true),
                TextReplacementEntry(src = "b", dst = "c", caseSensitive = true)
            )
        )

        // 第一条把 a 换成 b，第二条再把 b 换成 c。
        assertEquals("ccc", replacements.apply("aaa"))
    }
}
