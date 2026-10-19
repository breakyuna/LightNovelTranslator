package com.breakyuna.noveltranslator.core.quality

import com.breakyuna.noveltranslator.core.text.TextPreserveRule
import com.breakyuna.noveltranslator.core.translation.TranslationTextProtection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QualityRuleDefaults] 与 [QualityRuleJson] 的纯 JVM 测试。
 *
 * 核心不变量：**默认规则集 = 旧硬编码保护行为的超集**，升级不改变任何既有翻译行为。
 */
class QualityRuleDefaultsTest {

    // ---------- 默认规则集 ----------

    @Test
    fun defaultRuleSetsContainThreeSlotsWithExpectedSwitches() {
        val sets = QualityRuleDefaults.defaultRuleSets(projectId = 42L, now = 1_000L)
        assertEquals(3, sets.size)
        val byType = sets.associateBy { it.type }
        assertTrue(byType.containsKey(QualityRuleType.TEXT_PRESERVE.storageKey))
        assertTrue(byType.getValue(QualityRuleType.PRE_REPLACEMENT.storageKey).enabled.not())
        assertTrue(byType.getValue(QualityRuleType.POST_REPLACEMENT.storageKey).enabled.not())
        assertTrue(byType.getValue(QualityRuleType.TEXT_PRESERVE.storageKey).enabled)
        sets.forEach { assertEquals(42L, it.translationProjectId); assertEquals(1_000L, it.updatedAt) }
    }

    @Test
    fun defaultRuleSetForSingleTypeMatchesAggregate() {
        val single = QualityRuleDefaults.defaultRuleSet(7L, QualityRuleType.POST_REPLACEMENT, now = 5L)
        val fromAll = QualityRuleDefaults.defaultRuleSets(7L, 5L)
            .first { it.type == QualityRuleType.POST_REPLACEMENT.storageKey }
        assertEquals(fromAll, single)
    }

    @Test
    fun defaultEnabledFlags() {
        assertTrue(QualityRuleDefaults.defaultEnabled(QualityRuleType.TEXT_PRESERVE))
        assertFalse(QualityRuleDefaults.defaultEnabled(QualityRuleType.PRE_REPLACEMENT))
        assertFalse(QualityRuleDefaults.defaultEnabled(QualityRuleType.POST_REPLACEMENT))
    }

    @Test
    fun defaultEntriesForPreserveIsEmptyOthersHaveExamples() {
        assertTrue(QualityRuleDefaults.defaultEntries(QualityRuleType.TEXT_PRESERVE).isEmpty())
        assertTrue(QualityRuleDefaults.defaultEntries(QualityRuleType.PRE_REPLACEMENT).isNotEmpty())
        assertTrue(QualityRuleDefaults.defaultEntries(QualityRuleType.POST_REPLACEMENT).isNotEmpty())
    }

    @Test
    fun fallbackRulesEqualLegacyBehavior() {
        val fallback = QualityRuleDefaults.fallbackResolvedRules()
        assertEquals(QualityRuleMode.SMART, fallback.protectionMode)
        assertEquals(QualityRuleDefaults.textPreserveBaseRules, fallback.protectionEntries)
        assertNull(fallback.preReplacements)
        assertNull(fallback.postReplacements)
        assertTrue(fallback.fromFallback)
    }

    // ---------- 默认规则收编旧行为 ----------

    @Test
    fun baseRulesProtectLegacyMarkers() {
        val rule = TextProtectionRule(QualityRuleDefaults.textPreserveBaseRules)
        val segments = rule.collect("见图 [IMG:cover.png] 与 {{name}} 还有 ${var} 与 <b>粗体</b> 与 &amp;").toSet()
        assertTrue(segments.contains("[IMG:cover.png]"))
        assertTrue(segments.contains("{{name}}"))
        assertTrue(segments.contains("\${var}"))
        assertTrue(segments.contains("<b>"))
        assertTrue(segments.contains("&amp;"))
    }

    @Test
    fun maskingSkipsBlankSegmentsLikeLegacyAlternation() {
        // 空白段不生成掩敝标记的语义在 TranslationTextProtection.mask：
        // 旧 alternation 每个分支都要求可见字符，因此纯空白永远不可能被掩蔽。
        val protected = TranslationTextProtection.protect("见图 [IMG:a.png] 与   多个空格")
        val markers = protected.tokens.map { it.original }
        assertTrue(markers.contains("[IMG:a.png]"))
        assertTrue(markers.none { it.isBlank() })
        // 空格原样保留在掩蔽结果里
        assertTrue(protected.masked.contains(" 与   多个空格"))
    }

    @Test
    fun resourceTokenPatternOnlyMatchesRefTokens() {
        val rule = TextProtectionRule(QualityRuleDefaults.textPreserveBaseRules)
        val segments = rule.collect("token [REF:12] 与普通文本").toSet()
        assertTrue(segments.contains("[REF:12]"))
        assertFalse(segments.contains("token"))
    }

    // ---------- JSON 编解码 ----------

    @Test
    fun jsonRoundTripForPreserveEntries() {
        val entries = listOf(
            TextPreserveRuleEntry(src = "「[^」]+」", note = "引号"),
            TextPreserveRuleEntry(src = "<br>", note = "")
        )
        val json = QualityRuleJson.encode(QualityRuleType.TEXT_PRESERVE, entries)
        val decoded = QualityRuleJson.decode(QualityRuleType.TEXT_PRESERVE, json)
        assertEquals(entries, decoded)
    }

    @Test
    fun jsonRoundTripForReplacementEntries() {
        val entries = listOf(
            ReplacementRuleEntry(pattern = "(\\w+)", replacement = "$1", isRegex = true, caseSensitive = true, note = "n")
        )
        val json = QualityRuleJson.encode(QualityRuleType.POST_REPLACEMENT, entries)
        assertEquals(entries, QualityRuleJson.decode(QualityRuleType.POST_REPLACEMENT, json))
    }

    @Test
    fun decodeGarbageReturnsEmptyList() {
        assertTrue(QualityRuleJson.decode(QualityRuleType.TEXT_PRESERVE, "").isEmpty())
        assertTrue(QualityRuleJson.decode(QualityRuleType.TEXT_PRESERVE, "{ not array").isEmpty())
        assertTrue(QualityRuleJson.decode(QualityRuleType.TEXT_PRESERVE, "[{ broken").isEmpty())
    }

    @Test
    fun decodeWrongEntryShapeReturnsEmptyList() {
        // 条目缺少必需字段时 Moshi 解析失败 -> 空列表而不是抛错
        val json = """[{"unknown_field": 1}]"""
        assertTrue(QualityRuleJson.decode(QualityRuleType.PRE_REPLACEMENT, json).isEmpty())
    }

    // ---------- compileReplacements ----------

    @Test
    fun compileReplacementsEmptyOrAllBrokenReturnsNull() {
        assertNull(QualityRuleDefaults.compileReplacements(emptyList()))
        assertNull(
            QualityRuleDefaults.compileReplacements(
                listOf(ReplacementRuleEntry(pattern = "(bad", replacement = "x", isRegex = true))
            )
        )
    }

    @Test
    fun compileReplacementsDropsBrokenKeepsGood() {
        val compiled = QualityRuleDefaults.compileReplacements(
            listOf(
                ReplacementRuleEntry(pattern = "(bad", replacement = "x", isRegex = true),
                ReplacementRuleEntry(pattern = "good", replacement = "y")
            )
        )
        assertEquals(1, compiled!!.ruleCount)
        assertEquals("y", compiled.apply("good"))
    }

    // ---------- 枚举 ----------

    @Test
    fun storageKeysAreStable() {
        assertEquals("TEXT_PRESERVE", QualityRuleType.TEXT_PRESERVE.storageKey)
        assertEquals("PRE_REPLACEMENT", QualityRuleType.PRE_REPLACEMENT.storageKey)
        assertEquals("POST_REPLACEMENT", QualityRuleType.POST_REPLACEMENT.storageKey)
        assertEquals(QualityRuleType.PRE_REPLACEMENT, QualityRuleType.fromStorageKey("PRE_REPLACEMENT"))
        assertNull(QualityRuleType.fromStorageKey("nope"))
    }

    @Test
    fun allowedModesPerSlot() {
        assertEquals(
            listOf(QualityRuleMode.OFF, QualityRuleMode.SMART, QualityRuleMode.CUSTOM),
            QualityRuleMode.allowedModes(QualityRuleType.TEXT_PRESERVE)
        )
        assertEquals(
            listOf(QualityRuleMode.LITERAL, QualityRuleMode.REGEX, QualityRuleMode.CUSTOM),
            QualityRuleMode.allowedModes(QualityRuleType.PRE_REPLACEMENT)
        )
    }
}
