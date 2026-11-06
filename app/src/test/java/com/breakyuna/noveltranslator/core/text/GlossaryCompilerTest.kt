package com.breakyuna.noveltranslator.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GlossaryCompilerTest {

    private val entries = listOf(
        GlossaryEntry("hp", "HP", "\u751F\u547D\u503C", caseSensitive = true),
        GlossaryEntry("name", "Alice", "\u7231\u4E3D\u4E1D", info = "\u89D2\u8272", caseSensitive = false),
        GlossaryEntry("empty", "MP", "   ", caseSensitive = false),
        GlossaryEntry("duplicate", "HP", "\u4F53\u529B", caseSensitive = true)
    )

    @Test
    fun insensitive_terms_match_across_compatibility_character_forms() {
        val compiled = compileGlossary(
            listOf(GlossaryEntry("compat", "HP", "\u751F\u547D\u503C", caseSensitive = false))
        )

        assertEquals(
            listOf(GlossarySourceMatch(GlossaryEntry("compat", "HP", "\u751F\u547D\u503C"))),
            compiled.matchSource("\uFF28\uFF30")
        )
    }

    @Test
    fun repeated_hits_in_one_text_collapse_to_a_single_entry_identity() {
        val compiled = compileGlossary(entries)

        assertEquals(
            listOf("hp", "duplicate"),
            compiled.matchSource("HP HP").map { it.entry.entryId }
        )
        assertEquals(
            listOf("name"),
            compiled.matchSource("ALICE").map { it.entry.entryId }
        )
    }

    @Test
    fun application_is_checked_strictly_against_the_target_text() {
        val compiled = compileGlossary(entries)
        // 源文命中 hp、empty(MP)、name、duplicate 四条；empty 的 dst 是空白，不形成应用结果。
        val matches = compiled.matchSource("HP MP Alice")
        val verification = compiled.verifyApplication(matches, "\u751F\u547D\u503C \u7231\u4E3D\u4E1D")

        assertEquals(listOf("hp", "name", "duplicate"), verification.applications.map { it.entryId })
        // duplicate 与 hp 同源文，但要求译文中出现"体力"，因此判为未应用。
        assertEquals(listOf(true, true, false), verification.applications.map { it.applied })
        assertEquals(GlossaryApplicationState.PARTIAL, verification.state)
    }

    @Test
    fun a_missing_target_term_yields_partial_or_missing() {
        val compiled = compileGlossary(entries)
        val matches = compiled.matchSource("HP Alice")

        // 只应用了一个术语 → partial。
        assertEquals(
            GlossaryApplicationState.PARTIAL,
            compiled.verifyApplication(matches, "\u751F\u547D\u503C").state
        )
        // 两个都没应用 → missing。
        assertEquals(
            GlossaryApplicationState.MISSING,
            compiled.verifyApplication(matches, "\u65E0\u5173\u8BD1\u6587").state
        )
        // 空译文同样算 missing，且不应抛异常。
        assertEquals(
            GlossaryApplicationState.MISSING,
            compiled.verifyApplication(matches, "").state
        )
    }

    @Test
    fun target_field_reuses_the_literal_matcher_unicode_and_case_semantics() {
        val compiled = compileGlossary(
            listOf(
                GlossaryEntry("wide", "JK", "JK", caseSensitive = true),
                GlossaryEntry("folded", "HP", "HP", caseSensitive = false)
            )
        )
        val matches = compiled.matchSource("\uFF2A\uFF2B hp")

        assertEquals(
            listOf(true, true),
            compiled.verifyApplication(matches, "\uFF2A\uFF2B \uFF48\uFF50").applications.map { it.applied }
        )
        assertEquals(
            listOf(false, true),
            compiled.verifyApplication(matches, "\uFF4A\uFF4B \uFF48\uFF50").applications.map { it.applied }
        )
    }

    @Test
    fun multiple_terms_sharing_one_target_text_keep_independent_identities() {
        val compiled = compileGlossary(
            listOf(
                GlossaryEntry("first", "A", "X", caseSensitive = true),
                GlossaryEntry("second", "B", "X", caseSensitive = true)
            )
        )
        val matches = compiled.matchSource("A B")

        assertEquals(
            listOf("first", "second"),
            compiled.verifyApplication(matches, "X").applications.map { it.entryId }
        )
    }

    @Test
    fun no_match_yields_none_state() {
        val compiled = compileGlossary(entries)

        assertEquals(GlossaryApplicationState.NONE, compiled.verifyApplication(emptyList(), "X").state)
        assertEquals(
            GlossaryApplicationState.NONE,
            resolveGlossaryApplicationState(emptyList())
        )
    }

    @Test
    fun all_applied_and_none_applied_states() {
        val compiled = compileGlossary(
            listOf(
                GlossaryEntry("a", "A", "X", caseSensitive = true),
                GlossaryEntry("b", "B", "Y", caseSensitive = true)
            )
        )
        val matches = compiled.matchSource("A B")

        assertEquals(
            GlossaryApplicationState.APPLIED,
            compiled.verifyApplication(matches, "X Y").state
        )
        assertEquals(
            GlossaryApplicationState.MISSING,
            compiled.verifyApplication(matches, "Q R").state
        )
    }

    @Test
    fun blank_source_terms_are_dropped_and_duplicate_ids_keep_the_first() {
        val compiled = compileGlossary(
            listOf(
                GlossaryEntry("same", "A", "\u7537", caseSensitive = true),
                GlossaryEntry("same", "A", "\u5973", caseSensitive = true),
                GlossaryEntry("blank", "  ", "X", caseSensitive = true),
                GlossaryEntry("blank-dst", "B", "", caseSensitive = true)
            )
        )

        // 重复 entry_id 只保留首条；空白 src 的条目无法命中，直接剔除。
        assertEquals(listOf("same", "blank-dst"), compiled.entries.map { it.entryId })
        // 源侧命中按 src 判断，与 dst 是否空白无关。
        val matches = compiled.matchSource("A B")
        assertEquals(listOf("same", "blank-dst"), matches.map { it.entry.entryId })
        // 空白 dst 的条目不形成应用结果。
        val verification = compiled.verifyApplication(matches, "\u7537")
        assertEquals(listOf("same"), verification.applications.map { it.entryId })
        assertEquals(GlossaryApplicationState.APPLIED, verification.state)
    }

    @Test
    fun duplicate_pattern_keys_within_one_compilation_are_rejected() {
        // entry_id 同时是字面匹配器的 key；两个不同 src 共用同一 id 时只保留首条，
        // 因此这里用 LiteralMatcher 直接验证 key 唯一性约束。
        assertThrows(IllegalArgumentException::class.java) {
            compileLiteralPatterns(
                listOf(
                    LiteralPattern("same", "a", caseSensitive = true),
                    LiteralPattern("same", "b", caseSensitive = false)
                )
            )
        }
    }

    @Test
    fun empty_and_whitespace_only_texts_never_match() {
        val compiled = compileGlossary(entries)

        assertEquals(emptyList<GlossarySourceMatch>(), compiled.matchSource(""))
        assertEquals(emptyList<GlossarySourceMatch>(), compiled.matchSource("   "))
        assertEquals(GlossaryApplicationState.NONE, compiled.verifyApplication(emptyList(), "").state)
    }

    @Test
    fun format_glossary_entry_renders_a_stable_single_line_form() {
        assertEquals("HP -> \u751F\u547D\u503C", formatGlossaryEntry(entries[0]))
        assertEquals(
            "Alice -> \u7231\u4E3D\u4E1D #\u89D2\u8272",
            formatGlossaryEntry(entries[1])
        )
    }
}
