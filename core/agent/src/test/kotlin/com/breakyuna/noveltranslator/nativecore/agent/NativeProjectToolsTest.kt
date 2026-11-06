package com.breakyuna.noveltranslator.nativecore.agent

import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.*
import org.junit.Test

class NativeProjectToolsTest {
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    private val identity = RunIdentity(ref.projectId, ChatId("c"), RunId("r"), CommandId("command"))
    private val first = item(1, 0, "one", "一")
    private val second = item(2, 1, "two", "二")

    @Test fun readOnlyProjectToolsExposeMetadataAndLiteralSearch() = runTest {
        val runtime = RuntimeCoordinator().also { it.selectChat(ChatIdentity(identity.projectId, identity.chatId!!)) }
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val registry = nativeProjectTools(runtime, FakeProjects(), ref)
        try {
            val summary = registry.execute(run, AgentToolCall(ToolCallId("summary"), "project_summary", json())) as AgentToolOutcome.Success
            assertEquals("fixture", summary.result.json.getValue("name").jsonPrimitive.content)
            assertEquals(1, summary.result.json.getValue("assets").jsonArray.size)
            assertEquals("ja", summary.result.json.getValue("target_language").jsonPrimitive.content)

            val list = registry.execute(run, AgentToolCall(ToolCallId("list"), "list_items", json("""{"limit":1}"""))) as AgentToolOutcome.Success
            assertEquals(1, list.result.json.getValue("returned").jsonPrimitive.int)
            assertEquals(1L, list.result.json.getValue("next_after_id").jsonPrimitive.long)

            val search = registry.execute(run, AgentToolCall(ToolCallId("search"), "search_items",
                json("""{"text":"two"}"""))) as AgentToolOutcome.Success
            assertEquals(1, search.result.json.getValue("matching").jsonPrimitive.int)
            assertEquals("2", search.result.json.getValue("items").jsonArray.single().jsonObject.getValue("item_id").jsonPrimitive.content)

            val stats = registry.execute(run, AgentToolCall(ToolCallId("stats"), "project_statistics", json())) as AgentToolOutcome.Success
            assertEquals(1, stats.result.json.getValue("processed").jsonPrimitive.int)
            assertEquals(1, stats.result.json.getValue("unprocessed").jsonPrimitive.int)
        } finally { runtime.finishRun(run) }
    }

    private fun item(id: Long, row: Int, source: String, translation: String) = TextProjectItem(
        ItemId(id), row, source, translation,
        if (row == 0) TextItemStatus.PROCESSED else TextItemStatus.NONE,
        ObjectFingerprint(("a".repeat(63)) + (row + 1)), 1, row, TextItemMetadata())

    private inner class FakeProjects : TextProjectPort {
        private val summary = TextProjectSummary(ref, "fixture", 7, RelativePath("source.txt"), 2,
            listOf(ProjectAssetSummary(1, RelativePath("source.txt"), ProjectAssetFormat.TXT, 0, true,
                10, "b".repeat(64), 2)),
            ProjectSettings(targetLanguage = "ja", sourceLanguage = "en"))
        private val statistics = TextItemStatistics(unprocessed = 1, processed = 1, excluded = 0, translatableUnprocessed = 1)

        override suspend fun open(ref: LocalProjectRef): TextProjectSummary = summary
        override suspend fun readPage(ref: LocalProjectRef, afterId: Long, limit: Int): TextProjectPage {
            val items = listOf(first, second).filter { it.id.value > afterId }.take(limit)
            return TextProjectPage(summary, items, statistics, summary.itemCount)
        }
        override suspend fun queryPage(ref: LocalProjectRef, query: TextItemQuery, afterId: Long, limit: Int): TextProjectPage {
            val matched = listOf(first, second).filter { item ->
                (query.status == null || item.status == query.status) &&
                    (query.text.isEmpty() || item.source.contains(query.text) || item.translation.contains(query.text))
            }
            val page = matched.filter { it.id.value > afterId }.take(limit)
            return TextProjectPage(summary, page, statistics, matched.size)
        }
        override suspend fun item(ref: LocalProjectRef, itemId: ItemId): TextItemDetails? =
            listOf(first, second).firstOrNull { it.id == itemId }?.let { TextItemDetails(7, it) }

        override suspend fun registeredProjects(limit: Int): List<RegisteredProject> = error("unused")
        override suspend fun createTxt(commandId: CommandId, name: String, path: RelativePath, source: ByteArray,
            encoding: TxtEncoding): TextProjectSummary = error("unused")
        override suspend fun readItemWindow(ref: LocalProjectRef, query: TextItemQuery, window: TextItemWindow): TextProjectPage = error("unused")
        override suspend fun importProject(commandId: CommandId, source: () -> InputStream): TextProjectSummary = error("unused")
        override suspend fun copyProject(commandId: CommandId, ref: LocalProjectRef): TextProjectSummary = error("unused")
        override suspend fun recoverProjects(): ProjectRecoveryReport = error("unused")
        override suspend fun cleanupTemporaryOutputs(): Int = error("unused")
        override suspend fun items(ref: LocalProjectRef, revision: Long, afterId: Long, limit: Int): TextItemPage = error("unused")
        override suspend fun edit(command: ManualTextEdit): ManualEditReceipt = error("unused")
        override suspend fun prepareBatch(commandId: CommandId, ref: LocalProjectRef, revision: Long,
            window: TextItemWindow, change: TextBatchChange): ManualTextBatch? = error("unused")
        override suspend fun editBatch(batch: ManualTextBatch): ManualBatchReceipt = error("unused")
        override suspend fun receipt(ref: LocalProjectRef, commandId: CommandId): ManualEditReceipt? = error("unused")
        override suspend fun saveProject(ref: LocalProjectRef, destination: () -> OutputStream): Unit = error("unused")
        override suspend fun exportTxt(ref: LocalProjectRef, destination: () -> OutputStream): Unit = error("unused")
        override suspend fun exportBilingual(ref: LocalProjectRef, deduplicateSame: Boolean,
            destination: () -> OutputStream): Unit = error("unused")
        override suspend fun exportOriginal(ref: LocalProjectRef, destination: () -> OutputStream): Unit = error("unused")
    }
}
