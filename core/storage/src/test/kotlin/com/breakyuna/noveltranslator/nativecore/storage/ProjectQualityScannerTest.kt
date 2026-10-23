package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProjectQualityScannerTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var store: NativeTextProjectStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        runtime = RuntimeCoordinator(); store = NativeTextProjectStore(context, runtime)
    }
    @After fun cleanup() = runBlocking { store.close(); runtime.close() }
    private suspend fun project(source: String) = store.createTxt(CommandId("create"), "quality", RelativePath("source.txt"), source.toByteArray())
    private suspend fun translate(ref: LocalProjectRef, id: ItemId, text: String) {
        val before = store.item(ref, id)!!
        store.edit(ManualTextEdit(CommandId("edit-${before.revision}"), ref, id, before.revision, before.item.fingerprint, text))
    }
    private suspend fun expect(reason: TextProjectException.Reason, block: suspend () -> Unit) {
        try { block(); fail("Accepted invalid scan/page") } catch (error: TextProjectException) { assertEquals(reason, error.reason) }
    }
    @Test fun fullTextScanBuildsDeduplicatedCountsAndFilterableMetadataWithoutWriting() = runBlocking {
        val project = project("a".repeat(300) + " Alice <x>\nhello\npending")
        val initial = store.textPolicy(project.ref)
        val rules = ProjectTextPolicy(listOf(ProjectTextRule("term", TextRuleKind.TERM, "Alice", "艾莉丝"),
            ProjectTextRule("protect", TextRuleKind.PROTECT, "<x>")))
        store.editTextPolicy(ProjectPolicyEdit(CommandId("rules"), project.ref, initial.revision, initial.fingerprint, rules))
        val ids = store.items(project.ref, 1).items.map { it.id }
        translate(project.ref, ids[0], "她"); translate(project.ref, ids[1], "你好")
        val before = store.open(project.ref).revision
        val scanner = ProjectQualityScanner(store, store)
        val index = scanner.scan(project.ref, before, TextItemQuery(), TextProofreadingOptions())
        assertEquals(3, index.scanned); assertEquals(2, index.evaluatedCount); assertEquals(1, index.cleanCount)
        assertEquals(1, index.warningCounts[TextWarningCode.GLOSSARY]); assertEquals(1, index.warningCounts[TextWarningCode.TEXT_PRESERVE])
        val selection = index.select(TextQualityFilter(TextQualityOutcome.ANY_WARNING, TextWarningCode.GLOSSARY, "term"))
        assertEquals(listOf(ids[0]), scanner.page(selection).items.map { it.id })
        assertEquals(listOf(ids[1]), index.select(TextQualityFilter(TextQualityOutcome.NO_WARNING)).ids)
        assertEquals(listOf(ids[2]), index.select(TextQualityFilter(TextQualityOutcome.NOT_EVALUATED)).ids)
        assertEquals(before, store.open(project.ref).revision)
    }
    @Test fun pagesUseBoundedFixedMembersAndEmptySelectionsRemainEmpty() = runBlocking {
        val project = project((1..47).joinToString("\n") { "source $it" })
        val scanner = ProjectQualityScanner(store, store)
        val index = scanner.scan(project.ref, 0, TextItemQuery(), TextProofreadingOptions())
        val selection = index.select(TextQualityFilter())
        val first = scanner.page(selection, limit = 40)
        val second = scanner.page(selection, first.items.last().id.value, 40)
        assertEquals(40, first.items.size); assertEquals(7, second.items.size)
        assertEquals(47, (first.items + second.items).map { it.id }.distinct().size)
        val empty = scanner.page(index.select(TextQualityFilter(TextQualityOutcome.NO_WARNING)))
        assertTrue(empty.items.isEmpty()); assertEquals(0, empty.matchingItems)
    }
    @Test fun baseQueryIsLiteralCaseSensitiveAndUsesFullSourceOrTranslationFields() = runBlocking {
        val project = project("a".repeat(300) + " %_\n%X\nother")
        val id = store.items(project.ref, 0).items.last().id
        translate(project.ref, id, "target %_")
        val scanner = ProjectQualityScanner(store, store)
        val source = scanner.scan(project.ref, 1, TextItemQuery(text = "%_", field = TextSearchField.SOURCE), TextProofreadingOptions())
        assertEquals(1, source.facts.size)
        val target = scanner.scan(project.ref, 1, TextItemQuery(TextItemStatus.PROCESSED, "%_", TextSearchField.TRANSLATION), TextProofreadingOptions())
        assertEquals(listOf(id), target.facts.map { it.id })
    }
    @Test fun aWriteBetweenReadPagesRejectsTheEntireScan() = runBlocking {
        val project = project((1..25).joinToString("\n") { "row $it" })
        var reads = 0
        val changing = object : TextProjectPort by store {
            override suspend fun items(ref: LocalProjectRef, revision: Long, afterId: Long, limit: Int): TextItemPage {
                if (++reads == 2) translate(ref, ItemId(1), "changed")
                return store.items(ref, revision, afterId, limit)
            }
        }
        expect(TextProjectException.Reason.STALE_PAGE) {
            ProjectQualityScanner(changing, store).scan(project.ref, 0, TextItemQuery(), TextProofreadingOptions())
        }
        assertEquals(1L, store.open(project.ref).revision)
    }
    @Test fun aLaterRuleRevisionInvalidatesEveryPreviouslySelectedPage() = runBlocking {
        val project = project("source")
        val scanner = ProjectQualityScanner(store, store)
        val index = scanner.scan(project.ref, 0, TextItemQuery(), TextProofreadingOptions())
        val before = store.textPolicy(project.ref)
        store.editTextPolicy(ProjectPolicyEdit(CommandId("rules"), project.ref, 0, before.fingerprint, ProjectTextPolicy(prompt = "changed")))
        expect(TextProjectException.Reason.STALE_PAGE) { scanner.page(index.select(TextQualityFilter())) }
        expect(TextProjectException.Reason.STALE_PAGE) { scanner.page(index.select(TextQualityFilter(TextQualityOutcome.NO_WARNING))) }
    }
    @Test fun cancellationCharacterAndResultBudgetsNeverReturnPartialIndexes() = runBlocking {
        val project = project("source\nsource")
        try {
            ProjectQualityScanner(store, store).scan(project.ref, 0, TextItemQuery(), TextProofreadingOptions()) {
                if (it.scanned > 0) throw CancellationException()
            }
            fail("Ignored cancellation")
        } catch (_: CancellationException) { }
        expect(TextProjectException.Reason.LIMIT_EXCEEDED) {
            ProjectQualityScanner(store, store, QualityScanLimits(characters = 5)).scan(project.ref, 0, TextItemQuery(), TextProofreadingOptions())
        }
        assertEquals(0L, store.open(project.ref).revision)
        val before = store.textPolicy(project.ref)
        store.editTextPolicy(ProjectPolicyEdit(CommandId("rules"), project.ref, 0, before.fingerprint,
            ProjectTextPolicy(listOf(ProjectTextRule("a", TextRuleKind.TERM, "source", "A"), ProjectTextRule("b", TextRuleKind.TERM, "source", "B")))))
        translate(project.ref, ItemId(1), "译文")
        expect(TextProjectException.Reason.LIMIT_EXCEEDED) {
            ProjectQualityScanner(store, store, QualityScanLimits(resultEntries = 1)).scan(project.ref, 2, TextItemQuery(), TextProofreadingOptions())
        }
        assertEquals(2L, store.open(project.ref).revision)
    }
    @Test fun adjacentNavigationUsesTheSameLiteralPredicateAndExactRevisionWithoutWriting() = runBlocking {
        val project = project("%_ first\nordinary\n%_ last")
        val query = TextItemQuery(text = "%_", field = TextSearchField.SOURCE)
        val next = store.adjacentTextItem(project.ref, 0, query, ItemId(1), TextItemDirection.NEXT)!!
        assertEquals(ItemId(3), next.item.id)
        assertEquals(ItemId(1), store.adjacentTextItem(project.ref, 0, query, next.item.id, TextItemDirection.PREVIOUS)!!.item.id)
        assertNull(store.adjacentTextItem(project.ref, 0, query, ItemId(1), TextItemDirection.PREVIOUS))
        assertNull(store.adjacentTextItem(project.ref, 0, query, ItemId(3), TextItemDirection.NEXT))
        translate(project.ref, ItemId(2), "translated %_")
        expect(TextProjectException.Reason.STALE_PAGE) { store.adjacentTextItem(project.ref, 0, query, ItemId(1), TextItemDirection.NEXT) }
        val translationQuery = TextItemQuery(TextItemStatus.PROCESSED, "%_", TextSearchField.TRANSLATION)
        assertEquals(ItemId(2), store.adjacentTextItem(project.ref, 1, translationQuery, ItemId(1), TextItemDirection.NEXT)!!.item.id)
        assertEquals(1L, store.open(project.ref).revision)
    }
}
