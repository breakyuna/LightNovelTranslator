package com.breakyuna.noveltranslator.core.quality

import com.breakyuna.noveltranslator.data.model.QualityRuleSetEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QualityRuleResolver] 的纯 JVM 测试。
 *
 * 用函数注入的假数据驱动，不碰 Room / Android 框架。
 */
class QualityRuleResolverTest {

    private fun row(
        type: QualityRuleType,
        enabled: Boolean,
        mode: QualityRuleMode,
        entries: List<QualityRuleEntry> = emptyList()
    ) = QualityRuleSetEntity(
        translationProjectId = 1L,
        type = type.storageKey,
        enabled = enabled,
        mode = mode.name,
        entriesJson = QualityRuleJson.encode(type, entries)
    )

    // ---------- 空数据 / 回退 ----------

    @Test
    fun emptyRowsFallsBackToSmartBaseRules() {
        val resolved = QualityRuleResolver(loadRuleSets = { emptyList() }).resolveFromRows(emptyList())
        assertEquals(QualityRuleMode.SMART, resolved.protectionMode)
        assertEquals(QualityRuleDefaults.textPreserveBaseRules, resolved.protectionEntries)
        assertNull(resolved.preReplacements)
        assertNull(resolved.postReplacements)
        assertTrue(resolved.fromFallback)
        assertTrue(resolved.protectionEnabled)
    }

    @Test
    fun throwingLoaderFallsBackAndReports() {
        val diagnostics = mutableListOf<String>()
        val resolver = QualityRuleResolver(
            loadRuleSets = { error("db exploded") },
            onDiagnostic = { message, _ -> diagnostics += message }
        )
        kotlinx.coroutines.runBlocking {
            val resolved = resolver.resolve(1L)
            assertTrue(resolved.fromFallback)
            assertEquals(QualityRuleDefaults.textPreserveBaseRules, resolved.protectionEntries)
        }
        assertEquals(1, diagnostics.size)
        assertTrue(diagnostics.single().contains("回退"))
    }

    @Test
    fun cancellationIsRethrownNotSwallowed() {
        val resolver = QualityRuleResolver(
            loadRuleSets = { throw CancellationException("cancelled") }
        )
        var thrown: Throwable? = null
        kotlinx.coroutines.runBlocking {
            try {
                resolver.resolve(1L)
            } catch (error: Throwable) {
                thrown = error
            }
        }
        assertTrue(thrown is CancellationException)
    }

    // ---------- 保护槽位 ----------

    @Test
    fun smartModeUsesBaseRulesOnly() {
        val rows = listOf(
            row(QualityRuleType.TEXT_PRESERVE, enabled = true, mode = QualityRuleMode.SMART)
        )
        val resolved = QualityRuleResolver(loadRuleSets = { rows }).resolveFromRows(rows)
        assertEquals(QualityRuleDefaults.textPreserveBaseRules, resolved.protectionEntries)
    }

    @Test
    fun customModeAppendsUserRulesAfterBase() {
        val rows = listOf(
            row(
                QualityRuleType.TEXT_PRESERVE,
                enabled = true,
                mode = QualityRuleMode.CUSTOM,
                entries = listOf(TextPreserveRuleEntry(src = "「[^」]+」"))
            )
        )
        val resolved = QualityRuleResolver(loadRuleSets = { rows }).resolveFromRows(rows)
        assertEquals(QualityRuleDefaults.textPreserveBaseRules.size + 1, resolved.protectionEntries.size)
        assertEquals("「[^」]+」", resolved.protectionEntries.last().pattern)
    }

    @Test
    fun customModeDropsUncompilableUserRuleAndDiagnoses() {
        val diagnostics = mutableListOf<String>()
        val rows = listOf(
            row(
                QualityRuleType.TEXT_PRESERVE,
                enabled = true,
                mode = QualityRuleMode.CUSTOM,
                entries = listOf(
                    TextPreserveRuleEntry(src = "(unclosed"),
                    TextPreserveRuleEntry(src = "valid")
                )
            )
        )
        val resolved = QualityRuleResolver(
            loadRuleSets = { rows },
            onDiagnostic = { message, _ -> diagnostics += message }
        ).resolveFromRows(rows)
        assertEquals(QualityRuleDefaults.textPreserveBaseRules.size + 1, resolved.protectionEntries.size)
        assertTrue(diagnostics.any { it.contains("无法编译") })
    }

    @Test
    fun disabledProtectionTurnsOff() {
        val rows = listOf(
            row(QualityRuleType.TEXT_PRESERVE, enabled = false, mode = QualityRuleMode.SMART)
        )
        val resolved = QualityRuleResolver(loadRuleSets = { rows }).resolveFromRows(rows)
        assertEquals(QualityRuleMode.OFF, resolved.protectionMode)
        assertTrue(resolved.protectionEntries.isEmpty())
        assertFalse(resolved.protectionEnabled)
    }

    @Test
    fun offModeEmptiesProtection() {
        val rows = listOf(
            row(QualityRuleType.TEXT_PRESERVE, enabled = true, mode = QualityRuleMode.OFF)
        )
        val resolved = QualityRuleResolver(loadRuleSets = { rows }).resolveFromRows(rows)
        assertTrue(resolved.protectionEntries.isEmpty())
    }

    @Test
    fun unknownProtectionModeNormalizesToDefault() {
        val rows = listOf(
            QualityRuleSetEntity(
                translationProjectId = 1L,
                type = QualityRuleType.TEXT_PRESERVE.storageKey,
                enabled = true,
                mode = "NOT_A_MODE",
                entriesJson = "[]"
            )
        )
        val resolved = QualityRuleResolver(loadRuleSets = { rows }).resolveFromRows(rows)
        // 未知模式回落 TEXT_PRESERVE 的默认 SMART
        assertEquals(QualityRuleMode.SMART, resolved.protectionMode)
        assertEquals(QualityRuleDefaults.textPreserveBaseRules, resolved.protectionEntries)
    }

    @Test
    fun unknownRowTypeIsIgnored() {
        val rows = listOf(
            QualityRuleSetEntity(
                translationProjectId = 1L,
                type = "MYSTERY_SLOT",
                enabled = true,
                mode = QualityRuleMode.SMART.name,
                entriesJson = "[]"
            )
        )
        val resolved = QualityRuleResolver(loadRuleSets = { rows }).resolveFromRows(rows)
        // 不视为空数据集：未知行存在时 fromFallback=false，但保护仍走默认 SMART
        assertFalse(resolved.fromFallback)
        assertEquals(QualityRuleDefaults.textPreserveBaseRules, resolved.protectionEntries)
    }

    // ---------- 替换槽位 ----------

    @Test
    fun enabledReplacementSlotCompilesAndApplies() {
        val rows = listOf(
            row(
                QualityRuleType.PRE_REPLACEMENT,
                enabled = true,
                mode = QualityRuleMode.CUSTOM,
                entries = listOf(ReplacementRuleEntry(pattern = "龙语", replacement = "dragon tongue"))
            )
        )
        val resolved = QualityRuleResolver(loadRuleSets = { rows }).resolveFromRows(rows)
        assertNotNull(resolved.preReplacements)
        assertEquals("dragon tongue 法术", resolved.preReplacements!!.apply("龙语 法术"))
    }

    @Test
    fun disabledReplacementSlotIsNull() {
        val rows = listOf(
            row(
                QualityRuleType.PRE_REPLACEMENT,
                enabled = false,
                mode = QualityRuleMode.CUSTOM,
                entries = listOf(ReplacementRuleEntry(pattern = "a", replacement = "b"))
            )
        )
        val resolved = QualityRuleResolver(loadRuleSets = { rows }).resolveFromRows(rows)
        assertNull(resolved.preReplacements)
    }

    @Test
    fun literalModeForcesLiteralInterpretation() {
        val rows = listOf(
            row(
                QualityRuleType.POST_REPLACEMENT,
                enabled = true,
                mode = QualityRuleMode.LITERAL,
                entries = listOf(ReplacementRuleEntry(pattern = "a.c", replacement = "X"))
            )
        )
        val resolved = QualityRuleResolver(loadRuleSets = { rows }).resolveFromRows(rows)
        // 字面量：点号按原样匹配（不匹配 "abc"），命中的 "a.c" 被替换
        assertEquals("X abc", resolved.postReplacements!!.apply("a.c abc"))
    }

    @Test
    fun regexModeForcesRegexInterpretation() {
        val rows = listOf(
            row(
                QualityRuleType.POST_REPLACEMENT,
                enabled = true,
                mode = QualityRuleMode.REGEX,
                entries = listOf(ReplacementRuleEntry(pattern = "a.c", replacement = "X"))
            )
        )
        val resolved = QualityRuleResolver(loadRuleSets = { rows }).resolveFromRows(rows)
        assertEquals("X", resolved.postReplacements!!.apply("abc"))
    }

    @Test
    fun replacementDropsUncompilableEntryAndDiagnoses() {
        val diagnostics = mutableListOf<String>()
        val rows = listOf(
            row(
                QualityRuleType.PRE_REPLACEMENT,
                enabled = true,
                mode = QualityRuleMode.CUSTOM,
                entries = listOf(
                    ReplacementRuleEntry(pattern = "(bad", replacement = "x", isRegex = true),
                    ReplacementRuleEntry(pattern = "good", replacement = "y")
                )
            )
        )
        val resolved = QualityRuleResolver(
            loadRuleSets = { rows },
            onDiagnostic = { message, _ -> diagnostics += message }
        ).resolveFromRows(rows)
        assertNotNull(resolved.preReplacements)
        assertEquals("y", resolved.preReplacements!!.apply("good"))
        assertTrue(diagnostics.any { it.contains("无法编译") })
    }

    @Test
    fun blankPatternEntriesAreFiltered() {
        val rows = listOf(
            row(
                QualityRuleType.PRE_REPLACEMENT,
                enabled = true,
                mode = QualityRuleMode.CUSTOM,
                entries = listOf(ReplacementRuleEntry(pattern = "", replacement = "x"))
            )
        )
        val resolved = QualityRuleResolver(loadRuleSets = { rows }).resolveFromRows(rows)
        assertNull(resolved.preReplacements)
    }

    @Test
    fun corruptedEntriesJsonYieldsNullReplacement() {
        val rows = listOf(
            QualityRuleSetEntity(
                translationProjectId = 1L,
                type = QualityRuleType.PRE_REPLACEMENT.storageKey,
                enabled = true,
                mode = QualityRuleMode.CUSTOM.name,
                entriesJson = "{ 这不是数组"
            )
        )
        val resolved = QualityRuleResolver(loadRuleSets = { rows }).resolveFromRows(rows)
        assertNull(resolved.preReplacements)
    }

    @Test
    fun resolveIsSuspendableUnderRunTest() = runTest {
        val resolver = QualityRuleResolver(loadRuleSets = { emptyList() })
        assertTrue(resolver.resolve(1L).fromFallback)
    }
}
