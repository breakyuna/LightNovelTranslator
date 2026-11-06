package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import java.io.File
import kotlinx.coroutines.runBlocking
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
class NativeTextItemQueryTest {
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var store: NativeTextProjectStore

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        runtime = RuntimeCoordinator(); store = NativeTextProjectStore(context, runtime)
    }
    @After fun cleanup() = runBlocking { store.close(); runtime.close() }
    private suspend fun create(text: String, command: String = "create") =
        store.createTxt(CommandId(command), "fixture", RelativePath("source.txt"), text.toByteArray())
    private suspend fun edit(ref: LocalProjectRef, id: Long, text: String, status: TextItemStatus) {
        val item = requireNotNull(store.item(ref, ItemId(id)))
        assertEquals(ManualEditStatus.COMMITTED, store.edit(ManualTextEdit(CommandId("edit-$id"), ref,
            item.item.id, item.revision, item.item.fingerprint, text, status)).status)
    }

    @Test fun statusAndFieldSearchReturnCurrentCountsFromTheSameProjectRevision() = runBlocking {
        val ref = create("Alpha\nalpha\n日本語\nneedle\nother").ref
        edit(ref, 1, "needle 译文", TextItemStatus.PROCESSED)
        edit(ref, 2, "", TextItemStatus.EXCLUDED)
        val query = TextItemQuery(text = "needle")
        val page = store.queryPage(ref, query)
        assertEquals(2L, page.summary.revision)
        assertEquals(listOf(ItemId(1), ItemId(4)), page.items.map { it.id })
        assertEquals(2, page.matchingItems)
        assertEquals(TextItemStatistics(3, 1, 1, 3), page.statistics)
        assertEquals(listOf(ItemId(4)), store.queryPage(ref, query.copy(field = TextSearchField.SOURCE)).items.map { it.id })
        assertEquals(listOf(ItemId(1)), store.queryPage(ref, query.copy(field = TextSearchField.TRANSLATION)).items.map { it.id })
        assertEquals(listOf(ItemId(4)), store.queryPage(ref, query.copy(status = TextItemStatus.NONE)).items.map { it.id })
        assertEquals(page.statistics, store.readPage(ref).statistics)
        assertEquals(5, store.readPage(ref).matchingItems)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun translatablePendingCountsUseKotlinWhitespaceAndIgnoreQueryOrWindowMembership() = runBlocking {
        val ref = create("\n \t\n\u00A0\u2003\u3000\nword\n\ttext \n\u200B").ref
        val before = store.readPage(ref, limit = 1)
        assertEquals(TextItemStatistics(6, 0, 0, 3), before.statistics)
        assertEquals(3, store.items(ref, 0).items.count { it.status == TextItemStatus.NONE && it.source.isNotBlank() })
        val query = TextItemQuery(text = "word")
        assertEquals(before.statistics, store.queryPage(ref, query).statistics)
        assertEquals(before.statistics, store.readItemWindow(ref, query, TextItemWindow(listOf(ItemId(4)))).statistics)
        edit(ref, 4, "translated", TextItemStatus.PROCESSED)
        edit(ref, 5, "", TextItemStatus.EXCLUDED)
        assertEquals(TextItemStatistics(4, 1, 1, 1), store.readPage(ref).statistics)
        edit(ref, 6, "translated", TextItemStatus.PROCESSED)
        assertEquals(TextItemStatistics(3, 2, 1, 0), store.readPage(ref).statistics)
    }

    @Test fun searchTreatsSqlSyntaxWildcardsAndBackslashesAsLiteralText() = runBlocking {
        val ref = create("plain\n' OR 1=1 --\n100%_\\literal\n100xx\\literal").ref
        assertEquals(listOf(ItemId(2)), store.queryPage(ref, TextItemQuery(text = "' OR 1=1 --")).items.map { it.id })
        assertEquals(listOf(ItemId(3)), store.queryPage(ref, TextItemQuery(text = "%_")).items.map { it.id })
        assertEquals(listOf(ItemId(3), ItemId(4)), store.queryPage(ref, TextItemQuery(text = "\\literal")).items.map { it.id })
        assertTrue(store.queryPage(ref, TextItemQuery(text = "^plain$")).items.isEmpty())
        assertEquals(0L, store.open(ref).revision)
    }

    @Test fun unicodeAndCaseSensitiveSearchDoNotDuplicateARowThatMatchesBothFields() = runBlocking {
        val ref = create("Alpha\nalpha\n日本語").ref
        edit(ref, 3, "日本語", TextItemStatus.PROCESSED)
        assertEquals(listOf(ItemId(1)), store.queryPage(ref, TextItemQuery(text = "Alpha")).items.map { it.id })
        assertTrue(store.queryPage(ref, TextItemQuery(text = "ALPHA")).items.isEmpty())
        val japanese = store.queryPage(ref, TextItemQuery(text = "日本語"))
        assertEquals(1, japanese.matchingItems); assertEquals(1, japanese.items.size)
    }

    @Test fun sparseFilteredPagesUseStableItemIdsAndCountsIgnoreTheCursor() = runBlocking {
        val ref = create((1..9).joinToString("\n") { "row-$it" }).ref
        edit(ref, 2, "two", TextItemStatus.PROCESSED); edit(ref, 4, "four", TextItemStatus.PROCESSED)
        val query = TextItemQuery(status = TextItemStatus.NONE)
        val first = store.queryPage(ref, query, limit = 3)
        val second = store.queryPage(ref, query, afterId = first.items.last().id.value, limit = 3)
        val third = store.queryPage(ref, query, afterId = second.items.last().id.value, limit = 3)
        assertEquals(listOf(1L, 3L, 5L, 6L, 7L, 8L, 9L), (first.items + second.items + third.items).map { it.id.value })
        for (page in listOf(first, second, third, store.queryPage(ref, query, afterId = 99))) {
            assertEquals(7, page.matchingItems); assertEquals(TextItemStatistics(7, 2, 0, 7), page.statistics)
        }
    }

    @Test fun editingAFilteredMemberKeepsItsWindowUntilExplicitRequery() = runBlocking {
        val ref = create("needle-one\nother\nneedle-two").ref
        val query = TextItemQuery(TextItemStatus.NONE, "needle")
        val before = store.queryPage(ref, query)
        val window = TextItemWindow(before.items.map { it.id })
        edit(ref, 1, "saved", TextItemStatus.PROCESSED)
        val refreshed = store.readItemWindow(ref, query, window)
        assertEquals(listOf(ItemId(1), ItemId(3)), refreshed.items.map { it.id })
        assertEquals("saved", refreshed.items.first().translation)
        assertEquals(1L, refreshed.summary.revision); assertEquals(1, refreshed.matchingItems)
        assertEquals(TextItemStatistics(2, 1, 0, 2), refreshed.statistics)
        assertEquals(TextItemStatus.NONE, before.items.first().status)
        assertEquals(listOf(ItemId(3)), store.queryPage(ref, query).items.map { it.id })
    }

    @Test fun unknownWindowMembersFailWithoutPartialResults() = runBlocking {
        val ref = create("one\ntwo").ref
        try { store.readItemWindow(ref, TextItemQuery(), TextItemWindow(listOf(ItemId(1), ItemId(999)))); fail() }
        catch (error: TextProjectException) { assertEquals(TextProjectException.Reason.STALE_PAGE, error.reason) }
        assertEquals(0L, store.open(ref).revision)
    }

    @Test fun queryCountsAndSavedWindowNeverCrossProjectInstances() = runBlocking {
        val first = create("needle\nneedle")
        val second = create("different", "second")
        assertEquals(2, store.queryPage(first.ref, TextItemQuery(text = "needle")).matchingItems)
        val empty = store.queryPage(second.ref, TextItemQuery(text = "needle"))
        assertEquals(0, empty.matchingItems); assertEquals(TextItemStatistics(1, 0, 0, 1), empty.statistics)
        try {
            store.queryPage(first.ref.copy(projectId = second.ref.projectId), TextItemQuery()); fail()
        } catch (error: TextProjectException) { assertEquals(TextProjectException.Reason.WRONG_IDENTITY, error.reason) }
    }
}
