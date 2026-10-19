package com.breakyuna.noveltranslator.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteralMatcherTest {

    @Test
    fun same_batch_distinguishes_case_rules_and_keeps_duplicate_text_identities() {
        val matcher = compileLiteralPatterns(
            listOf(
                LiteralPattern("sensitive", "HP", caseSensitive = true),
                LiteralPattern("folded-a", "stra\u00DFe", caseSensitive = false),
                LiteralPattern("folded-b", "STRASSE", caseSensitive = false)
            )
        )

        assertEquals(
            listOf(
                LiteralPatternMatch("folded-a", listOf(TextRange(3, 10), TextRange(11, 17))),
                LiteralPatternMatch("folded-b", listOf(TextRange(3, 10), TextRange(11, 17)))
            ),
            matcher.match("hp STRASSE Stra\u00DFe")
        )
    }

    @Test
    fun keeps_overlapping_hits_and_original_utf16_ranges() {
        val matcher = compileLiteralPatterns(
            listOf(
                LiteralPattern("aa", "aa", caseSensitive = true),
                LiteralPattern("emoji", "\uD83D\uDE00", caseSensitive = true),
                LiteralPattern("ss", "ss", caseSensitive = false)
            )
        )

        assertEquals(
            listOf(
                LiteralPatternMatch("aa", listOf(TextRange(0, 2), TextRange(1, 3))),
                LiteralPatternMatch("emoji", listOf(TextRange(5, 7))),
                LiteralPatternMatch("ss", listOf(TextRange(7, 8)))
            ),
            matcher.match("aaa\r\n\uD83D\uDE00\u00DF")
        )
    }

    @Test
    fun scan_streams_only_actual_hits_with_source_ranges() {
        val matcher = compileLiteralPatterns(
            listOf(
                LiteralPattern("root", "\u57CE", caseSensitive = true),
                LiteralPattern("full", "\u767D\u4E4B\u57CE", caseSensitive = true),
                LiteralPattern("miss", "\u6E2F\u53E3", caseSensitive = true)
            )
        )
        val matches = ArrayList<Pair<String, TextRange>>()
        matcher.scan("\u767D\u4E4B\u57CE") { key, range -> matches += key to range }

        assertEquals(
            listOf(
                "full" to TextRange(0, 3),
                "root" to TextRange(2, 3)
            ),
            matches
        )
    }

    @Test
    fun keys_boolean_and_range_matching_share_the_same_unicode_semantics() {
        val matcher = compileLiteralPatterns(
            listOf(
                LiteralPattern("folded", "STRASSE", caseSensitive = false),
                LiteralPattern("sigma", "\u039F\u03A3", caseSensitive = false),
                LiteralPattern("accent", "\u00E9", caseSensitive = false),
                LiteralPattern("hangul", "\uAC00", caseSensitive = false),
                LiteralPattern("kana", "\u30AB\u30BF\u30AB\u30CA", caseSensitive = false),
                LiteralPattern("voiced-kana", "\u30AC", caseSensitive = false),
                LiteralPattern("ligature", "fi", caseSensitive = false),
                LiteralPattern("duplicate", "STRASSE", caseSensitive = false),
                LiteralPattern("emoji", "\uD83D\uDC69\u200D\uD83D\uDCBB", caseSensitive = true),
                LiteralPattern("sensitive", "JK", caseSensitive = true)
            )
        )
        val texts = listOf(
            "\u65E0\u5173\u6587\u672C",
            "Stra\u00DFe",
            "\u524D\u7F00 \u03BF\u03C2 \u540E\u7F00",
            "\u7EC4\u5408\u5B57\u7B26 e\u0301",
            "\u97D3\u6587 Jamo \u1100\u1161",
            "\u517C\u5BB9 Jamo \u3131\u314F \u4E0E\u534A\u89D2 \uFF61\uFFC2",
            "\u534A\u89D2\u5047\u540D \uFF76\uFF80\uFF76\uFF85",
            "\u6E4A\u97F3 \uFF76\uFF9E \u3068 \u30AB\u3099",
            "\u5408\u5B57 \uFB01",
            "\u91CD\u590D\u547D\u4E2D STRASSE Stra\u00DFe",
            "emoji \uD83D\uDC69\u200D\uD83D\uDCBB test",
            "\u5168\u89D2 \uFF2A\uFF2B",
            "\u5C0F\u5199 \uFF4A\uFF4B"
        )

        for (text in texts) {
            val ranged = matcher.match(text).map { it.key }.sorted()
            val keys = ArrayList<String>()
            matcher.scanKeys(text) { keys += it }
            assertEquals(text, ranged, keys.sorted())
            assertEquals(text, ranged.isNotEmpty(), matcher.matches(text))
        }
    }

    @Test
    fun selected_identities_return_all_ranges_and_keep_input_order() {
        val matcher = compileLiteralPatterns(
            listOf(
                LiteralPattern("folded", "s", caseSensitive = false),
                LiteralPattern("suffix", "a", caseSensitive = true),
                LiteralPattern("full", "aa", caseSensitive = true),
                LiteralPattern("duplicate", "AA", caseSensitive = false)
            )
        )
        val text = "aaa \u00DF"
        val keys = listOf("folded", "full", "duplicate")
        for (selected in listOf(keys, listOf("full"), listOf("folded"), emptyList())) {
            assertEquals(
                selected.toString(),
                matcher.match(text).filter { it.key in selected },
                matcher.match(text, selected.toSet())
            )
        }
    }

    @Test
    fun uses_nfkc_and_compatible_casefold() {
        assertEquals("strasse i \u03BF\u03C3", normalizeLiteralText("\uFF33\uFF34\uFF32\uFF21\u1E9E\uFF25 I \u039F\u03A3", false))

        val matcher = compileLiteralPatterns(listOf(LiteralPattern("hangul", "\uAC00", caseSensitive = true)))
        assertEquals(
            listOf(LiteralPatternMatch("hangul", listOf(TextRange(0, 2), TextRange(3, 5)))),
            // 兼容 Jamo 与半角 Jamo 各自 NFKC 后都能合成一个完整音节。
            matcher.match("\u3131\u314F \uFFA1\uFFC2")
        )
    }

    @Test
    fun case_sensitive_rules_still_apply_nfkc_but_not_case_folding() {
        val matcher = compileLiteralPatterns(listOf(LiteralPattern("term", "JK", caseSensitive = true)))

        assertEquals(
            listOf(LiteralPatternMatch("term", listOf(TextRange(0, 2)))),
            matcher.match("\uFF2A\uFF2B")
        )
        assertEquals(emptyList<LiteralPatternMatch>(), matcher.match("\uFF4A\uFF4B"))
    }

    @Test
    fun contextual_case_folding_merges_duplicate_ranges_from_character_expansion() {
        val matcher = compileLiteralPatterns(
            listOf(
                LiteralPattern("sigma", "\u039F\u03A3", caseSensitive = false),
                LiteralPattern("expanded", "s", caseSensitive = false)
            )
        )

        assertEquals(
            listOf(
                LiteralPatternMatch("sigma", listOf(TextRange(0, 2), TextRange(3, 5), TextRange(6, 8))),
                LiteralPatternMatch("expanded", listOf(TextRange(9, 10)))
            ),
            matcher.match("\u039F\u03A3 \u03BF\u03C2 \u03BF\u03C3 \u00DF")
        )
    }

    @Test
    fun empty_patterns_are_ignored_and_duplicate_keys_are_rejected() {
        val emptyMatcher = compileLiteralPatterns(listOf(LiteralPattern("empty", "", caseSensitive = false)))
        assertEquals(emptyList<LiteralPattern>(), emptyMatcher.patterns)
        assertFalse(emptyMatcher.matches("\u4EFB\u610F\u6587\u672C"))

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
    fun matches_stops_at_the_first_hit_and_scan_keys_visits_each_identity_once() {
        val matcher = compileLiteralPatterns(
            listOf(
                LiteralPattern("a", "a", caseSensitive = false),
                LiteralPattern("b", "b", caseSensitive = false)
            )
        )

        assertTrue(matcher.matches("zzz a zzz b zzz"))
        val keys = ArrayList<String>()
        matcher.scanKeys("a b a b") { key -> keys += key }
        assertEquals(listOf("a", "b"), keys)
    }

    @Test
    fun empty_text_yields_no_matches() {
        val matcher = compileLiteralPatterns(listOf(LiteralPattern("a", "a", caseSensitive = false)))

        assertFalse(matcher.matches(""))
        assertEquals(emptyList<LiteralPatternMatch>(), matcher.match(""))
        val keys = ArrayList<String>()
        matcher.scanKeys("") { keys += it }
        assertEquals(emptyList<String>(), keys)
    }

    @Test
    fun very_long_text_scans_without_losing_ranges() {
        val matcher = compileLiteralPatterns(listOf(LiteralPattern("needle", "NEEDLE", caseSensitive = false)))
        val text = "x".repeat(50_000) + " needle " + "y".repeat(50_000)

        val matches = matcher.match(text)
        assertEquals(1, matches.size)
        // "x"*50000 占 0..49999，" needle " 占 50000..50007，故 needle 为 [50001, 50007)。
        assertEquals(TextRange(50_001, 50_007), matches.single().ranges.single())
    }
}
