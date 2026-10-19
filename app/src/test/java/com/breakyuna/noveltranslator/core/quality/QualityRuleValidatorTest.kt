package com.breakyuna.noveltranslator.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [QualityRuleValidator] 的纯 JVM 测试：ERROR 阻断保存，WARNING 只提示。 */
class QualityRuleValidatorTest {

    @Test
    fun validEntriesProduceNoIssues() {
        val issues = QualityRuleValidator.validateEntry(
            QualityRuleType.TEXT_PRESERVE,
            0,
            TextPreserveRuleEntry(src = "「[^」]+」", note = "直角引号内容")
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun blankPatternIsError() {
        val issues = QualityRuleValidator.validateEntry(
            QualityRuleType.TEXT_PRESERVE,
            3,
            TextPreserveRuleEntry(src = "   ")
        )
        assertEquals(1, issues.size)
        assertEquals(QualityRuleIssueSeverity.ERROR, issues.single().severity)
        assertEquals(3, issues.single().index)
    }

    @Test
    fun brokenRegexIsError() {
        val issues = QualityRuleValidator.validateEntry(
            QualityRuleType.PRE_REPLACEMENT,
            0,
            ReplacementRuleEntry(pattern = "(unclosed", replacement = "x", isRegex = true)
        )
        assertTrue(issues.any { it.severity == QualityRuleIssueSeverity.ERROR && it.message.contains("无法编译") })
    }

    @Test
    fun matchEverythingPatternWarnsButDoesNotBlock() {
        val issues = QualityRuleValidator.validateEntry(
            QualityRuleType.TEXT_PRESERVE,
            0,
            TextPreserveRuleEntry(src = ".*")
        )
        assertTrue(issues.any { it.severity == QualityRuleIssueSeverity.WARNING })
        assertTrue(QualityRuleValidator.blockingIssues(issues).isEmpty())
    }

    @Test
    fun whitespaceMatchingPreserveRuleWarns() {
        val issues = QualityRuleValidator.validateEntry(
            QualityRuleType.TEXT_PRESERVE,
            0,
            TextPreserveRuleEntry(src = " +")
        )
        assertTrue(issues.any { it.severity == QualityRuleIssueSeverity.WARNING && it.message.contains("空白") })
    }

    @Test
    fun replacementReferencingMissingNumberedGroupIsError() {
        val issues = QualityRuleValidator.validateEntry(
            QualityRuleType.POST_REPLACEMENT,
            0,
            ReplacementRuleEntry(pattern = "(\\w+)", replacement = "$2", isRegex = true)
        )
        assertTrue(issues.any { it.severity == QualityRuleIssueSeverity.ERROR && it.message.contains("捕获组") })
    }

    @Test
    fun replacementReferencingMissingNamedGroupIsError() {
        val issues = QualityRuleValidator.validateEntry(
            QualityRuleType.POST_REPLACEMENT,
            0,
            ReplacementRuleEntry(pattern = "(\\w+)", replacement = "\${missing}", isRegex = true)
        )
        assertTrue(issues.any { it.severity == QualityRuleIssueSeverity.ERROR && it.message.contains("命名捕获组") })
    }

    @Test
    fun replacementWithValidGroupReferenceHasNoBlockingIssue() {
        val issues = QualityRuleValidator.validateEntry(
            QualityRuleType.POST_REPLACEMENT,
            0,
            ReplacementRuleEntry(pattern = "(\\w+)@(\\w+)", replacement = "$2:$1", isRegex = true)
        )
        assertTrue(QualityRuleValidator.blockingIssues(issues).isEmpty())
    }

    @Test
    fun unclosedNamedGroupReferenceWarns() {
        val issues = QualityRuleValidator.validateEntry(
            QualityRuleType.POST_REPLACEMENT,
            0,
            ReplacementRuleEntry(pattern = "x", replacement = "\${name", isRegex = true)
        )
        assertTrue(issues.any { it.severity == QualityRuleIssueSeverity.WARNING && it.message.contains("没有闭合") })
    }

    @Test
    fun literalEntryWithMetaCharactersWarns() {
        val issues = QualityRuleValidator.validateEntry(
            QualityRuleType.PRE_REPLACEMENT,
            0,
            ReplacementRuleEntry(pattern = "a.b", replacement = "x")
        )
        assertTrue(issues.any { it.severity == QualityRuleIssueSeverity.WARNING && it.message.contains("元字符") })
    }

    @Test
    fun modeNotAllowedForSlotIsError() {
        val issues = QualityRuleValidator.validateRuleSet(
            type = QualityRuleType.TEXT_PRESERVE,
            enabled = true,
            mode = QualityRuleMode.LITERAL,
            entries = emptyList()
        )
        assertTrue(issues.any { it.severity == QualityRuleIssueSeverity.ERROR && it.index == -1 && it.message.contains("模式") })
    }

    @Test
    fun tooManyEntriesIsError() {
        val entries = (1..QualityRuleValidator.MAX_ENTRIES_PER_SET + 1)
            .map { TextPreserveRuleEntry(src = "p$it") }
        val issues = QualityRuleValidator.validateRuleSet(
            QualityRuleType.TEXT_PRESERVE,
            enabled = true,
            mode = QualityRuleMode.CUSTOM,
            entries = entries
        )
        assertTrue(issues.any { it.severity == QualityRuleIssueSeverity.ERROR && it.message.contains("条目数") })
    }

    @Test
    fun enabledSlotWithoutEntriesWarns() {
        val issues = QualityRuleValidator.validateRuleSet(
            QualityRuleType.PRE_REPLACEMENT,
            enabled = true,
            mode = QualityRuleMode.CUSTOM,
            entries = emptyList()
        )
        assertTrue(issues.any { it.severity == QualityRuleIssueSeverity.WARNING && it.message.contains("没有条目") })
    }

    @Test
    fun customProtectionWithoutUserRulesWarns() {
        val issues = QualityRuleValidator.validateRuleSet(
            QualityRuleType.TEXT_PRESERVE,
            enabled = true,
            mode = QualityRuleMode.CUSTOM,
            entries = listOf(TextPreserveRuleEntry(src = "  "))
        )
        assertTrue(issues.any { it.severity == QualityRuleIssueSeverity.WARNING && it.message.contains("SMART") })
    }

    @Test
    fun longNoteWarns() {
        val issues = QualityRuleValidator.validateEntry(
            QualityRuleType.TEXT_PRESERVE,
            0,
            TextPreserveRuleEntry(src = "x", note = "n".repeat(QualityRuleValidator.MAX_NOTE_LENGTH + 1))
        )
        assertTrue(issues.any { it.severity == QualityRuleIssueSeverity.WARNING && it.message.contains("备注") })
    }

    @Test
    fun longPatternIsError() {
        val issues = QualityRuleValidator.validateEntry(
            QualityRuleType.TEXT_PRESERVE,
            0,
            TextPreserveRuleEntry(src = "a".repeat(QualityRuleValidator.MAX_PATTERN_LENGTH + 1))
        )
        assertTrue(issues.any { it.severity == QualityRuleIssueSeverity.ERROR && it.message.contains("长度") })
    }

    @Test
    fun blockingIssuesFiltersWarnings() {
        val issues = listOf(
            QualityRuleIssue(0, QualityRuleIssueSeverity.WARNING, "w"),
            QualityRuleIssue(1, QualityRuleIssueSeverity.ERROR, "e")
        )
        val blocking = QualityRuleValidator.blockingIssues(issues)
        assertEquals(1, blocking.size)
        assertEquals("e", blocking.single().message)
    }
}
