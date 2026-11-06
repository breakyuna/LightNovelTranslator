package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ItemId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import org.junit.Assert.*
import org.junit.Test

class TranslationPlannerTest {
    private fun row(
        id: Long,
        row: Int,
        source: String,
        status: TextItemStatus = TextItemStatus.NONE,
        assetId: Long = 1,
    ): TranslationItemSnapshot = TranslationItemSnapshot(
        ItemId(id), assetId, row, source, status,
        TextProjectFingerprint.item(ItemId(id), row, source, "", status, assetId = assetId, slot = row),
    )

    private fun snapshot(items: List<TranslationItemSnapshot>, revision: Long = 0): TranslationTargetSnapshot =
        TranslationTargetSnapshot(REF, revision, items)

    private fun plan(
        items: List<TranslationItemSnapshot>,
        mode: TranslationRunMode = TranslationRunMode.CONTINUE,
        revision: Long = 0,
        scope: TranslationCommandScope = TranslationCommandScope.all(),
        limits: TranslationPlanLimits = TranslationPlanLimits(),
        gate: TranslationSourceGate = AcceptAllTranslationSourceGate,
        counter: TranslationTokenCounter = EstimatedTranslationTokenCounter,
    ): TranslationPlan = TranslationPlanner(limits, gate, counter).plan(
        snapshot(items, revision),
        TranslationModeCommand(REF, mode, revision, scope),
    )

    @Test fun characterBudgetClosesUnitsWithoutReorderingOrDropping() {
        val items = listOf(
            row(1, 0, "a".repeat(100)),
            row(2, 1, "b".repeat(100)),
            row(3, 2, "c".repeat(100)),
        )
        val plan = plan(items, limits = TranslationPlanLimits(maxCharactersPerUnit = 256))
        assertEquals(2, plan.units.size)
        assertEquals(listOf(1L, 2L), plan.units[0].itemIds().map { it.value })
        assertEquals(listOf(3L), plan.units[1].itemIds().map { it.value })
        assertEquals(200L, plan.units[0].usage.characters)
        assertEquals(TokenCountAccuracy.ESTIMATED, plan.units[0].usage.accuracy)
        assertEquals(0, plan.skipped.size)
    }

    @Test fun itemCountAndLineBudgetsCapUnits() {
        val five = (1L..5L).map { row(it, (it - 1).toInt(), "line $it") }
        val byItems = plan(five, limits = TranslationPlanLimits(maxItemsPerUnit = 2))
        assertEquals(listOf(2, 2, 1), byItems.units.map { it.items.size })

        val threeLines = (1L..2L).map { row(it, (it - 1).toInt(), "a\nb\nc") }
        val byLines = plan(threeLines, limits = TranslationPlanLimits(maxLinesPerUnit = 5))
        assertEquals(listOf(1, 1), byLines.units.map { it.items.size })
        assertTrue(byLines.units.all { it.usage.lines == 3 })
    }

    @Test fun singleItemOverBudgetIsIsolatedAndFlagged() {
        val items = listOf(row(1, 0, "x".repeat(300)))
        val plan = plan(items, limits = TranslationPlanLimits(maxCharactersPerUnit = 256))
        val unit = plan.units.single()
        assertEquals(1, unit.items.size)
        assertEquals(300L, unit.usage.characters)
        assertTrue(unit.overBudget)
    }

    @Test fun tokenBudgetClosesUnitsAndFlagsAnUnsplitOversizeItem() {
        val tenTokens = TranslationTokenCounter { TranslationTokenMeasurement(10, TokenCountAccuracy.EXACT) }
        val pair = listOf(row(1, 0, "a"), row(2, 1, "b"))
        val split = plan(pair, limits = TranslationPlanLimits(maxTokensPerUnit = 10), counter = tenTokens)
        assertEquals(listOf(1, 1), split.units.map { it.items.size })
        assertFalse(split.units.any { it.overBudget })

        val huge = TranslationTokenCounter { TranslationTokenMeasurement(100, TokenCountAccuracy.EXACT) }
        val oversized = plan(listOf(row(3, 0, "c")),
            limits = TranslationPlanLimits(maxTokensPerUnit = 10), counter = huge)
        assertTrue(oversized.units.single().overBudget)
    }

    @Test fun tokenAccuracyIsExactOnlyWhenEveryMeasurementIsExact() {
        val exact = TranslationTokenCounter { source ->
            TranslationTokenMeasurement(source.length.toLong(), TokenCountAccuracy.EXACT)
        }
        val exactPlan = plan(listOf(row(1, 0, "hello")), counter = exact)
        assertEquals(TokenCountAccuracy.EXACT, exactPlan.units.single().usage.accuracy)
        assertEquals(TokenCountAccuracy.EXACT, exactPlan.tokenAccuracy)
        assertEquals(5L, exactPlan.units.single().usage.tokens)
        assertEquals(5L, exactPlan.tokenCount)

        var seen = 0
        val mixed = TranslationTokenCounter { source ->
            val accuracy = if (seen++ == 0) TokenCountAccuracy.EXACT else TokenCountAccuracy.ESTIMATED
            TranslationTokenMeasurement(source.length.toLong(), accuracy)
        }
        val mixedPlan = plan(listOf(row(1, 0, "hello"), row(2, 1, "world")), counter = mixed)
        assertEquals(TokenCountAccuracy.ESTIMATED, mixedPlan.units.single().usage.accuracy)
        assertEquals(TokenCountAccuracy.ESTIMATED, mixedPlan.tokenAccuracy)
    }

    @Test fun unitsNeverMixAssetsAndContextStaysInTheSameFile() {
        val items = listOf(
            row(1, 0, "甲。", assetId = 1),
            row(2, 1, "乙。", assetId = 1),
            row(3, 0, "丙。", assetId = 2),
            row(4, 1, "丁", assetId = 2),
        )
        val plan = plan(items, limits = TranslationPlanLimits(maxItemsPerUnit = 1, precedingLines = 2))
        assertEquals(4, plan.units.size)
        assertTrue(plan.units.all { unit -> unit.items.all { it.assetId == unit.assetId } })
        val secondFileSecondRow = plan.units.first { it.items.single().id == ItemId(4) }
        assertEquals(listOf(3L), secondFileSecondRow.precedings.map { it.id.value })
    }

    @Test fun precedingContextIsBoundedAndStopsAtAnUnfinishedLine() {
        val items = listOf(
            row(1, 0, "第一句。"),
            row(2, 1, "第二句。"),
            row(3, 2, "目标"),
        )
        val plan = plan(items, limits = TranslationPlanLimits(
            maxItemsPerUnit = 1,
            precedingLines = 3,
            precedingCharacters = 6,
            precedingTokens = 100,
        ))
        val target = plan.units.first { it.items.single().id == ItemId(3) }
        assertEquals(listOf(2L), target.precedings.map { it.id.value })

        val unfinished = listOf(row(10, 0, "未完"), row(11, 1, "目标"))
        val second = plan(unfinished, limits = TranslationPlanLimits(
            maxItemsPerUnit = 1,
            precedingLines = 3,
            precedingCharacters = 100,
            precedingTokens = 100,
        ))
        assertTrue(second.units.first { it.items.single().id == ItemId(11) }.precedings.isEmpty())
    }

    @Test fun blankRuleLanguageAndOversizeSourcesAreClassifiedWithoutBlockingOthers() {
        val gate = object : TranslationSourceGate {
            override fun ruleSkip(item: TranslationItemSnapshot): Boolean = item.source.startsWith("rule:")
            override fun languageSkip(item: TranslationItemSnapshot): Boolean =
                item.source.startsWith("lang:") || item.source.contains("both")
        }
        val items = listOf(
            row(1, 0, "   "),
            row(2, 1, "rule:both"),
            row(3, 2, "lang:x"),
            row(4, 3, "x".repeat(65_501)),
            row(5, 4, "hello"),
        )
        val plan = plan(items, gate = gate)
        assertEquals(listOf(5L), plan.plannedItemIds().map { it.value })
        val reasons = plan.skipped.associate { it.id.value to it.reason }
        assertEquals(TranslationSkipReason.EMPTY_SOURCE, reasons[1])
        assertEquals(TranslationSkipReason.RULE_SKIPPED, reasons[2])
        assertEquals(TranslationSkipReason.LANGUAGE_SKIPPED, reasons[3])
        assertEquals(TranslationSkipReason.SOURCE_TOO_LARGE, reasons[4])
    }

    @Test fun newContinueAndResetSelectDifferentStatuses() {
        val items = listOf(
            row(1, 0, "one", TextItemStatus.NONE),
            row(2, 1, "two", TextItemStatus.ERROR),
            row(3, 2, "three", TextItemStatus.PROCESSED),
            row(4, 3, "four", TextItemStatus.EXCLUDED),
        )
        val newPlan = plan(items, mode = TranslationRunMode.NEW)
        assertEquals(listOf(1L), newPlan.plannedItemIds().map { it.value })
        assertEquals(TranslationSelectionReason.FAILED_ITEM,
            newPlan.selection.exclusions.first { it.id == ItemId(2) }.reason)
        assertEquals(TranslationSelectionReason.ALREADY_TRANSLATED,
            newPlan.selection.exclusions.first { it.id == ItemId(3) }.reason)
        assertEquals(TranslationSelectionReason.EXCLUDED,
            newPlan.selection.exclusions.first { it.id == ItemId(4) }.reason)

        val continuePlan = plan(items, mode = TranslationRunMode.CONTINUE)
        assertEquals(listOf(1L, 2L), continuePlan.plannedItemIds().map { it.value })

        val resetPlan = plan(items, mode = TranslationRunMode.RESET)
        assertEquals(listOf(1L, 2L, 3L), resetPlan.plannedItemIds().map { it.value })
        assertEquals(listOf(TranslationTargetAction.RETRANSLATE, TranslationTargetAction.RETRANSLATE,
            TranslationTargetAction.RETRANSLATE), resetPlan.selection.targets.map { it.action })
        assertEquals(TranslationSelectionReason.EXCLUDED,
            resetPlan.selection.exclusions.first { it.id == ItemId(4) }.reason)
    }

    @Test fun itemScopeRestrictsTargetsAndReportsOutOfScopeRows() {
        val items = listOf(row(1, 0, "one"), row(2, 1, "two"), row(3, 2, "three"))
        val plan = plan(items, scope = TranslationCommandScope.items(listOf(ItemId(3), ItemId(1))))
        assertEquals(listOf(1L, 3L), plan.plannedItemIds().map { it.value })
        assertEquals(TranslationSelectionReason.NOT_IN_SCOPE,
            plan.selection.exclusions.first { it.id == ItemId(2) }.reason)
    }

    @Test fun staleRevisionIdentityAndUnknownScopeYieldEmptyPlans() {
        val items = listOf(row(1, 0, "one"))
        val planner = TranslationPlanner()
        val staleRevision = planner.plan(snapshot(items, revision = 5),
            TranslationModeCommand(REF, TranslationRunMode.CONTINUE, 4))
        assertEquals(TranslationModeStatus.STALE_REVISION, staleRevision.status)
        assertTrue(staleRevision.units.isEmpty())

        val otherRef = LocalProjectRef(ProjectId("other"), ProjectInstanceId("instance"), RecoveryGeneration(1))
        val staleIdentity = planner.plan(snapshot(items),
            TranslationModeCommand(otherRef, TranslationRunMode.CONTINUE, 0))
        assertEquals(TranslationModeStatus.STALE_IDENTITY, staleIdentity.status)

        val unknown = planner.plan(snapshot(items), TranslationModeCommand(REF, TranslationRunMode.CONTINUE, 0,
            TranslationCommandScope.items(listOf(ItemId(1), ItemId(9)))))
        assertEquals(TranslationModeStatus.UNKNOWN_SCOPE_ITEMS, unknown.status)
        assertTrue(unknown.units.isEmpty())
        assertEquals(listOf(ItemId(9)), unknown.selection.unknownScopeItemIds)
    }

    @Test fun rowsAreCanonicalizedWithinEachFile() {
        val items = listOf(row(2, 1, "second"), row(1, 0, "first"))
        val plan = plan(items)
        assertEquals(listOf(1L, 2L), plan.units.single().itemIds().map { it.value })
    }

    @Test fun planFingerprintIsDeterministicAndRevisionSensitive() {
        val items = listOf(row(1, 0, "one"), row(2, 1, "two"))
        val first = plan(items, revision = 1)
        val second = plan(items, revision = 1)
        assertEquals(first.fingerprint(), second.fingerprint())
        assertNotEquals(first.fingerprint(), plan(items, revision = 2).fingerprint())
        assertNotEquals(first.fingerprint(), plan(items, revision = 1,
            limits = TranslationPlanLimits(maxLinesPerUnit = 100)).fingerprint())
    }

    private companion object {
        val REF = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    }
}
