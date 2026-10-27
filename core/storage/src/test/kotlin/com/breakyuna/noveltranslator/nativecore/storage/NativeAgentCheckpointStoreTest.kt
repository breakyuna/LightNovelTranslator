package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NativeAgentCheckpointStoreTest {
    private lateinit var context: Context
    private lateinit var store: NativeAgentCheckpointStore
    private val branch = BranchId("main")
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(2))
    private val chat = ChatId("chat")
    private val usage = RequestUsage(
        TokenMeasurement(12, UsageSource.ACTUAL),
        TokenMeasurement(null, UsageSource.UNKNOWN),
        TokenMeasurement(3, UsageSource.ESTIMATED),
        TokenMeasurement(null, UsageSource.UNKNOWN),
        TokenMeasurement(8, UsageSource.ACTUAL),
    )

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "native_agent_checkpoints").deleteRecursively()
        store = NativeAgentCheckpointStore(context)
    }

    @After fun cleanup() {
        File(context.filesDir, "native_agent_checkpoints").deleteRecursively()
    }

    @Test fun saveLatestAndListSurviveAStoreReopen() = runBlocking {
        store.save(envelope(chat, CheckpointStage.BEFORE_REQUEST, "first"))
        store.save(envelope(chat, CheckpointStage.AFTER_RESPONSE, "second"))
        val latest = store.latest(ref, chat, branch)!!
        assertEquals(CheckpointStage.AFTER_RESPONSE, latest.stage)
        assertEquals("second", (latest.messages.last() as AgentMessage.Assistant).text())
        assertEquals(ref, latest.projectRef)
        assertEquals(chat, latest.run.chatId)

        val reopened = NativeAgentCheckpointStore(context)
        val page = reopened.list(ref, chat, branch, 10)
        assertEquals(listOf(CheckpointStage.AFTER_RESPONSE, CheckpointStage.BEFORE_REQUEST), page.map { it.stage })
        assertEquals("second", (page[0].messages.last() as AgentMessage.Assistant).text())
        assertEquals("first", (page[1].messages.last() as AgentMessage.Assistant).text())
        assertEquals(usage, page[0].usage)
        assertEquals(listOf(CommandId("command")), page[0].acceptedCommands)
    }

    @Test fun projectInstanceGenerationChatAndBranchAreIsolated() = runBlocking {
        val otherChat = ChatId("other-chat")
        val otherRef = ref.copy(instanceId = ProjectInstanceId("other-instance"))
        val otherGeneration = ref.copy(generation = RecoveryGeneration(3))
        store.save(envelope(chat, CheckpointStage.AFTER_RESPONSE, "chat-a"))
        store.save(envelope(otherChat, CheckpointStage.AFTER_RESPONSE, "chat-b"))

        assertEquals("chat-a", textOf(store.latest(ref, chat, branch)))
        assertEquals("chat-b", textOf(store.latest(ref, otherChat, branch)))
        assertNull(store.latest(ref, ChatId("missing"), branch))
        assertNull(store.latest(otherRef, chat, branch))
        assertNull(store.latest(otherGeneration, chat, branch))
        assertNull(store.latest(ref, chat, BranchId("other-branch")))
    }

    @Test fun aForeignChatFileInTheWrongPartitionFailsClosed() = runBlocking {
        store.save(envelope(chat, CheckpointStage.AFTER_RESPONSE, "source"))
        val source = store.partitionDirectory(ref, chat, branch).listFiles()!!.single { it.name.endsWith(".json") }
        val target = store.partitionDirectory(ref, ChatId("target"), branch)
        assertTrue(target.isDirectory || target.mkdirs())
        source.copyTo(File(target, "checkpoint-9999999999999999999-forged.json"))

        val failure = runCatching { store.latest(ref, ChatId("target"), branch) }.exceptionOrNull()
        assertTrue(failure is AgentCheckpointCorruptException)
    }

    @Test fun corruptAndUnknownVersionFilesAreErrorsNotEmptyHistory() = runBlocking {
        val directory = store.partitionDirectory(ref, chat, branch)
        assertTrue(directory.isDirectory || directory.mkdirs())
        val source = File(directory, "checkpoint-9999999999999999999-corrupt.json")
        source.writeText("not json")
        assertTrue(runCatching { store.latest(ref, chat, branch) }.exceptionOrNull() is AgentCheckpointCorruptException)

        source.writeText("{\"version\":2}")
        assertTrue(runCatching { store.latest(ref, chat, branch) }.exceptionOrNull() is AgentCheckpointCorruptException)
        assertTrue(runCatching { store.list(ref, chat, branch, 1) }.exceptionOrNull() is AgentCheckpointCorruptException)
    }

    @Test fun fileSizeLimitsAreEnforced() = runBlocking {
        val directory = store.partitionDirectory(ref, chat, branch)
        assertTrue(directory.isDirectory || directory.mkdirs())
        val oversized = File(directory, "checkpoint-9999999999999999999-oversized.json")
        oversized.writeBytes(ByteArray(NativeAgentCheckpointStore.MAX_FILE_BYTES + 1) { ' '.code.toByte() })
        assertTrue(runCatching { store.latest(ref, chat, branch) }.exceptionOrNull() is AgentCheckpointLimitException)
    }

    @Test fun savingAnOversizedCheckpointFailsBeforePublishing() = runBlocking {
        val body = "x".repeat(1_500_000)
        val messages = listOf(
            AgentMessage.User(MessageId("u1"), listOf(UserPart.Text(body))),
            AgentMessage.Assistant(MessageId("a1"), listOf(AssistantPart.Text(body))),
            AgentMessage.User(MessageId("u2"), listOf(UserPart.Text(body))),
        )
        val oversized = CheckpointEnvelope(
            1, 1, RunIdentity(ref.projectId, chat, RunId("run"), CommandId("command")), branch,
            CheckpointStage.AFTER_RESPONSE, messages, emptyList(), null, emptyList(), usage, ref,
        )
        assertTrue(runCatching { store.save(oversized) }.exceptionOrNull() is AgentCheckpointLimitException)
        assertNull(store.latest(ref, chat, branch))
    }

    @Test fun checkpointCountIsBoundedAndNewestRemainsReadable() = runBlocking {
        repeat(NativeAgentCheckpointStore.MAX_CHECKPOINTS_PER_PARTITION + 1) { index ->
            store.save(envelope(chat, CheckpointStage.AFTER_RESPONSE, "turn-$index"))
        }
        val directory = store.partitionDirectory(ref, chat, branch)
        val count = directory.listFiles()!!.count { it.name.startsWith("checkpoint-") && it.name.endsWith(".json") }
        assertTrue(count <= NativeAgentCheckpointStore.MAX_CHECKPOINTS_PER_PARTITION)
        assertEquals("turn-${NativeAgentCheckpointStore.MAX_CHECKPOINTS_PER_PARTITION}",
            textOf(store.latest(ref, chat, branch)))
    }

    private fun envelope(chatId: ChatId, stage: CheckpointStage, text: String) = CheckpointEnvelope(
        version = 1,
        strategyVersion = 1,
        run = RunIdentity(ref.projectId, chatId, RunId("run"), CommandId("command")),
        branchId = branch,
        stage = stage,
        messages = listOf(
            AgentMessage.User(MessageId("user-$text"), listOf(UserPart.Text("request $text"))),
            AgentMessage.Assistant(MessageId("assistant-$text"), listOf(AssistantPart.Text(text))),
        ),
        pendingToolCalls = emptyList(),
        summary = null,
        acceptedCommands = listOf(CommandId("command")),
        usage = usage,
        projectRef = ref,
    )

    private fun AgentMessage.Assistant.text(): String =
        parts.filterIsInstance<AssistantPart.Text>().joinToString("") { it.text }

    private fun textOf(envelope: CheckpointEnvelope?): String? =
        (envelope?.messages?.last() as? AgentMessage.Assistant)?.text()
}
