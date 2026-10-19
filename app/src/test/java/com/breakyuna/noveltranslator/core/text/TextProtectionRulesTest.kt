package com.breakyuna.noveltranslator.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextProtectionRulesTest {

    @Test
    fun base_rules_are_always_active_and_overlap_is_adjudicated_by_rule_order() {
        val rule = TextProtectionPresets.baseRule(
            extraEntries = listOf(TextPreserveEntry(pattern = "[a-z-]+"))
        )

        assertEquals(listOf("<br>", " ", "[REF:12]"), rule.collect("<br> [REF:12]正文"))
    }

    @Test
    fun custom_entries_compile_in_order_and_adjudicate_overlapping_candidates() {
        val rule = TextProtectionPresets.baseRule(
            extraEntries = listOf(
                TextPreserveEntry(pattern = "<A>"),
                TextPreserveEntry(pattern = "<[^>]+>")
            )
        )

        assertEquals(listOf("<A>", "<B>"), rule.collect("x<A>y<B>z"))
    }

    @Test
    fun invalid_pattern_fails_at_construction() {
        assertThrows(Exception::class.java) {
            TextProtectionRule(listOf(TextPreserveEntry(pattern = "(")))
        }
    }

    @Test
    fun unicode_property_escapes_are_supported() {
        val rule = TextProtectionPresets.baseRule(
            extraEntries = listOf(TextPreserveEntry(pattern = """\p{Script=Han}+"""))
        )

        assertEquals(listOf("与"), collectNonBlankTextPreserveSegments("Alice 与 Bob", rule))
    }

    @Test
    fun blank_only_matches_are_ignored_by_non_blank_collection() {
        val rule = TextProtectionPresets.baseRule(
            extraEntries = listOf(
                TextPreserveEntry(pattern = """\s+"""),
                TextPreserveEntry(pattern = """\[[^\]]+\]""")
            )
        )

        assertEquals(listOf("[A]"), collectNonBlankTextPreserveSegments(" \t[A]\n ", rule))
    }

    @Test
    fun removing_preserved_content_keeps_word_boundaries_and_line_breaks() {
        // 直接构造规则集，不叠加基础层，与来源实现的这条用例保持一致。
        val rule = TextProtectionRule(
            listOf(
                TextPreserveEntry(pattern = "<[^>]+>"),
                TextPreserveEntry(pattern = """\s""")
            )
        )
        val analysis = rule.analyze("one<tag>two\nthree <A\nB>four")

        assertEquals(
            listOf(listOf("one", "two"), listOf("three"), listOf("four")),
            analysis.unpreservedText.split("\n").map { line ->
                line.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            }
        )
        assertEquals(listOf("<tag>", "\n", " ", "<A\nB>"), analysis.segments)
    }

    @Test
    fun only_unpreserved_text_is_transformed() {
        val rule = TextProtectionPresets.baseRule(
            extraEntries = listOf(TextPreserveEntry(pattern = "<[^>]+>"))
        )

        assertEquals("A<X>B", rule.transformUnpreserved("a<X>b") { it.uppercase() })
    }

    @Test
    fun replace_callback_index_counts_only_replaced_segments() {
        val rule = TextProtectionRule(
            listOf(TextPreserveEntry(pattern = """\s+"""), TextPreserveEntry(pattern = "o"))
        )

        // "  x oy" 中 \s+ 命中两段空白，o 命中字母 o，回调索引按实际替换顺序递增。
        assertEquals("[0]x[1][2]y", rule.replace("  x oy") { _, index -> "[$index]" })
        // "a o b" 有三段命中：两个空格与字母 o。
        assertEquals("a---b", rule.replace("a o b", "-"))
    }

    @Test
    fun empty_text_analysis_has_no_segments() {
        val rule = TextProtectionPresets.baseRule()
        val analysis = rule.analyze("")

        assertEquals(emptyList<String>(), analysis.segments)
        assertEquals("", analysis.unpreservedText)
        assertEquals("", analysis.text)
    }

    @Test
    fun overlapping_candidates_at_same_start_prefer_the_earlier_rule() {
        val rule = TextProtectionRule(
            listOf(
                TextPreserveEntry(pattern = "ab"),
                TextPreserveEntry(pattern = "abc")
            )
        )

        // 同起点按规则定义序优先，因此 "ab" 胜出，"abc" 因重叠被丢弃。
        assertEquals(listOf("ab"), rule.collect("abc"))
        assertEquals("Xc", rule.replace("abc", "X"))
    }

    @Test
    fun same_start_prefers_the_longer_candidate_when_rules_are_ordered_that_way() {
        val rule = TextProtectionRule(
            listOf(
                TextPreserveEntry(pattern = "abc"),
                TextPreserveEntry(pattern = "ab")
            )
        )

        assertEquals(listOf("abc"), rule.collect("abc"))
    }

    @Test
    fun analyze_binds_the_analyzed_text() {
        val rule = TextProtectionRule(listOf(TextPreserveEntry(pattern = "a")))
        val analysis = rule.analyze("bab")

        assertEquals("bab", analysis.text)
        assertEquals(listOf("a"), analysis.segments)
        // 保护段替换为等长空白，不改变行长，因此可以直接按同一坐标系比较。
        assertEquals("b b", analysis.unpreservedText)
    }
}
