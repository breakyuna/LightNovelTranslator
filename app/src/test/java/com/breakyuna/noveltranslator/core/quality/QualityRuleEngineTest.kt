package com.breakyuna.noveltranslator.core.quality

import com.breakyuna.noveltranslator.core.text.TextReplacementEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QualityReplacements] 与编译辅助函数的纯 JVM 测试。
 *
 * 重点：正则规则不注入 `(?U)`（Android 14+ ICU 不接受内联 `U` 标志），
 * 字面量走 core/text 的 LiteralMatcher，替换串展开语义与 JS `$1` / `${name}` 一致。
 */
class QualityRuleEngineTest {

    // ---------- QualityReplacements ----------

    @Test
    fun literalRuleReplacesCaseInsensitivelyByDefault() {
        val rules = QualityReplacements(
            listOf(ReplacementRuleEntry(pattern = "Alice", replacement = "爱丽丝"))
        )
        // 大小写不敏感：两个不同大小写的出现都命中
        assertEquals("爱丽丝 and 爱丽丝", rules.apply("Alice and ALICE"))
        assertEquals(1, rules.ruleCount)
    }

    @Test
    fun literalRuleHonorsCaseSensitive() {
        val rules = QualityReplacements(
            listOf(
                ReplacementRuleEntry(pattern = "Alice", replacement = "爱丽丝", caseSensitive = true)
            )
        )
        // 大小写敏感：只有精确匹配被替换
        assertEquals("爱丽丝 and ALICE", rules.apply("Alice and ALICE"))
    }

    @Test
    fun literalRuleReplacesAllOccurrences() {
        val rules = QualityReplacements(
            listOf(ReplacementRuleEntry(pattern = "龙", replacement = "dragon"))
        )
        assertEquals("dragon 与 dragon", rules.apply("龙 与 龙"))
    }

    @Test
    fun regexRuleExpandsNumberedGroup() {
        val rules = QualityReplacements(
            listOf(ReplacementRuleEntry(pattern = "(\\w+)@(\\w+)", replacement = "$2:$1", isRegex = true))
        )
        assertEquals("breakyuna:novel", rules.apply("novel@breakyuna"))
    }

    @Test
    fun regexRuleExpandsNamedGroup() {
        val rules = QualityReplacements(
            listOf(
                ReplacementRuleEntry(
                    pattern = "(?<name>\\w+) 先生",
                    replacement = "Mr. \${name}",
                    isRegex = true
                )
            )
        )
        assertEquals("Mr. Smith", rules.apply("Smith 先生"))
    }

    @Test
    fun regexRuleEscapedDollarStaysLiteral() {
        val rules = QualityReplacements(
            listOf(ReplacementRuleEntry(pattern = "价格", replacement = "\\$1 元", isRegex = true))
        )
        assertEquals("$1 元", rules.apply("价格"))
    }

    @Test
    fun rulesApplyInOrder() {
        val rules = QualityReplacements(
            listOf(
                ReplacementRuleEntry(pattern = "a", replacement = "b"),
                ReplacementRuleEntry(pattern = "b", replacement = "c")
            )
        )
        // 第二条看到的是第一条的输出：a -> b -> c
        assertEquals("c", rules.apply("a"))
    }

    @Test
    fun uncompilableRegexIsDroppedFromRuleCount() {
        val rules = QualityReplacements(
            listOf(
                ReplacementRuleEntry(pattern = "ok", replacement = "x"),
                ReplacementRuleEntry(pattern = "(unclosed", replacement = "y", isRegex = true)
            )
        )
        assertEquals(1, rules.ruleCount)
        assertEquals("x", rules.apply("ok"))
    }

    @Test
    fun emptyPatternIsSkipped() {
        val rules = QualityReplacements(listOf(ReplacementRuleEntry(pattern = "", replacement = "x")))
        assertEquals(0, rules.ruleCount)
        assertEquals("原文", rules.apply("原文"))
    }

    @Test
    fun multilineAnchorsApplyPerLine() {
        val rules = QualityReplacements(
            listOf(
                ReplacementRuleEntry(pattern = "(?m)^\"", replacement = "「", isRegex = true),
                ReplacementRuleEntry(pattern = "(?m)\"$", replacement = "」", isRegex = true)
            )
        )
        assertEquals("「你好」\n「世界」", rules.apply("\"你好\"\n\"世界\""))
    }

    // ---------- compileRulePattern ----------

    @Test
    fun compileRulePatternRejectsBrokenRegex() {
        assertNull(compileRulePattern("(unclosed"))
        assertNull(compileRulePattern("[a-"))
    }

    @Test
    fun compileRulePatternAcceptsValidRegex() {
        assertTrue(compileRulePattern("\\[IMG:[^]]+\\]") != null)
    }

    @Test
    fun compileRulePatternCaseSensitivity() {
        val sensitive = compileRulePattern("alice", caseSensitive = true)!!
        val insensitive = compileRulePattern("alice", caseSensitive = false)!!
        assertEquals(false, sensitive.containsMatchIn("ALICE"))
        assertEquals(true, insensitive.containsMatchIn("ALICE"))
    }

    // ---------- compileAlternation ----------

    @Test
    fun compileAlternationEmptyReturnsNull() {
        assertNull(compileAlternation(emptyList()))
    }

    @Test
    fun compileAlternationIsolatesInlineFlags() {
        // 第一条带 (?i)，不能泄漏到第二条分支
        val regex = compileAlternation(listOf("(?i)foo", "BAR"))!!
        assertEquals(true, regex.containsMatchIn("FOO"))
        assertEquals(true, regex.containsMatchIn("BAR"))
        assertEquals(false, regex.containsMatchIn("bar"))
    }

    @Test
    fun compileAlternationReturnsNullWhenAnyPatternBroken() {
        assertNull(compileAlternation(listOf("ok", "(bad")))
    }

    // ---------- expandReplacement ----------

    @Test
    fun expandReplacementHandlesEscapeAndMissingGroup() {
        val match = Regex("(a)(b)?").find("a")!!
        // $2 缺失 -> 空串；\$ -> 字面 $
        assertEquals("x|", expandReplacement("x|$2", match))
        assertEquals("$2", expandReplacement("\\$2", match))
    }
}
