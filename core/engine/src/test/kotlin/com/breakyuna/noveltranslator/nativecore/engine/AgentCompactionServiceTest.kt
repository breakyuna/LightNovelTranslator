package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentCompactionServiceTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private val chat = ChatId("chat")
    private val policy = AgentCompactionPolicy(maxContextChars = 4_096, maxContextBytes = 8_192,
        minUserTurns = 3, retainUserTurns = 2, maxSummaryChars = 1_000)

    private fun json() = JsonObjectValue.parse("{}", JsonInputLimits(1024, 4))
    private fun user(id: String, text: String = "request") =
        AgentMessage.User(MessageId(id), listOf(UserPart.Text(text)))
    private fun assistant(id: String, text: String = "reply") =
        AgentMessage.Assistant(MessageId(id), listOf(AssistantPart.Text(text)))
    private fun toolCall(id: String): AgentToolCall = AgentToolCall(ToolCallId(id), "project_summary", json())
    private fun toolResult(id: String, call: AgentToolCall) =
        AgentMessage.ToolResult(MessageId(id), call.id, call.name, json(), false)

    private fun toolTurnHistory(): List<AgentMessage> {
        val call = toolCall("call-1")
        return listOf(
            user("u1", "x".repeat(5_000)),
            AgentMessage.Assistant(MessageId("a1"), listOf(AssistantPart.ToolCall(call))),
            toolResult("r1", call),
            user("u2"),
            assistant("a2"),
            user("u3"),
            assistant("a3"),
        )
    }

    private fun smallHistory() = listOf(user("u1", "short"), assistant("a1"), user("u2"), assistant("a2"))

    private class FakeConversation(private val messages: List<AgentMessage>) : AgentConversationPort {
        var reads = 0
        override suspend fun modelHistory(ref: LocalProjectRef, chat: ChatId): List<AgentMessage> {
            reads++
            return messages
        }
        override suspend fun history(ref: LocalProjectRef, chat: ChatId): List<AgentMessage> = error("unused")
        override suspend fun appendMessage(ref: LocalProjectRef, chat: ChatId, message: AgentMessage) = error("unused")
    }

    private class FakeSummary : AgentSummaryPort {
        var calls = 0
        var last: AgentSummaryRequest? = null
        var failure: Exception? = null
        var response = "compacted summary"
        override suspend fun summarize(request: AgentSummaryRequest): String {
            calls++
            last = request
            failure?.let { throw it }
            return response
        }
    }

    private class FakeContexts(private val history: List<AgentMessage>, private var oldSummary: String = "")
        : AgentAutoContextPort {
        var prepareCalls = 0
        var saveCalls = 0
        var saveFailure: Exception? = null
        var archivedDelta = 0
        var saved: String = oldSummary
        var prepared: AgentAutoContextPreview? = null

        override suspend fun prepareAutoContext(ref: LocalProjectRef, chat: ChatId,
            retainUserTurns: Int): AgentAutoContextPreview {
            prepareCalls++
            val cut = requireNotNull(AgentCompactionPlanner.safeCut(history, retainUserTurns))
            val preview = AgentContextPreview(ref, chat, ObjectFingerprint("a".repeat(64)), saved,
                cut.archivedMessages + archivedDelta, cut.retainedMessages)
            return AgentAutoContextPreview(preview, 0L, cut.boundary, retainUserTurns).also { prepared = it }
        }

        override suspend fun saveAutoContext(preview: AgentAutoContextPreview, summary: String) {
            saveCalls++
            saveFailure?.let { throw it }
            require(preview == prepared) { "CAS mismatch" }
            saved = summary
        }
    }

    private class FakeGate : AgentCompactionGatePort {
        var calls = 0
        var block: AgentCompactionBlock? = null
        var laterBlock: AgentCompactionBlock? = null
        override suspend fun blockedReason(ref: LocalProjectRef, chat: ChatId): AgentCompactionBlock? {
            calls++
            return if (calls > 1 && laterBlock != null) laterBlock else block
        }
    }

    private inner class Fakes(history: List<AgentMessage>, oldSummary: String = "") {
        val conversation = FakeConversation(history)
        val summary = FakeSummary()
        val contexts = FakeContexts(history, oldSummary)
        val gate = FakeGate()
        val service = AgentCompactionService(conversation, summary, contexts, gate, policy)
    }

    @Test fun belowThresholdSkipsWithoutPrepareOrSummaryRequest() = runTest {
        val fakes = Fakes(smallHistory())
        assertEquals(AgentCompactionResult.Skipped(AgentCompactionSkip.BELOW_THRESHOLD),
            fakes.service.compact(ref, chat))
        assertEquals(1, fakes.conversation.reads)
        assertEquals(0, fakes.contexts.prepareCalls)
        assertEquals(0, fakes.summary.calls)
    }

    @Test fun compactsOnlyTheExactArchivedPrefixAndPersistsOnce() = runTest {
        val fakes = Fakes(toolTurnHistory(), oldSummary = "Old goals")
        val result = fakes.service.compact(ref, chat)
        assertTrue(result is AgentCompactionResult.Compacted)
        val compacted = result as AgentCompactionResult.Compacted
        assertEquals(3, compacted.plan.cut.archivedMessages)
        assertEquals(1, fakes.contexts.prepareCalls)
        assertEquals(1, fakes.summary.calls)
        assertEquals("Old goals", fakes.summary.last!!.priorSummary)
        assertEquals(listOf("u1", "a1", "r1"), fakes.summary.last!!.archivedMessages.map { it.id.value })
        assertEquals(MessageId("r1"), fakes.summary.last!!.archivedMessages.last().id)
        assertEquals("compacted summary", fakes.contexts.saved)
        assertEquals(1, fakes.contexts.saveCalls)
    }

    @Test fun pendingApprovalAndActiveToolLoopBlockBeforeHistoryIsRead() = runTest {
        val pending = Fakes(toolTurnHistory())
        pending.gate.block = AgentCompactionBlock.PENDING_APPROVAL
        assertEquals(AgentCompactionResult.Skipped(AgentCompactionSkip.PENDING_APPROVAL),
            pending.service.compact(ref, chat))
        assertEquals(0, pending.conversation.reads)
        assertEquals(0, pending.summary.calls)

        val loop = Fakes(toolTurnHistory())
        loop.gate.block = AgentCompactionBlock.ACTIVE_TOOL_LOOP
        assertEquals(AgentCompactionResult.Skipped(AgentCompactionSkip.ACTIVE_TOOL_LOOP),
            loop.service.compact(ref, chat))
        assertEquals(0, loop.contexts.prepareCalls)
    }

    @Test fun userDisabledCompactionNeverReadsHistoryOrRequestsSummary() = runTest {
        val fakes = Fakes(toolTurnHistory())
        fakes.gate.block = AgentCompactionBlock.USER_DISABLED
        assertEquals(AgentCompactionResult.Skipped(AgentCompactionSkip.USER_DISABLED),
            fakes.service.compact(ref, chat))
        assertEquals(0, fakes.conversation.reads)
        assertEquals(0, fakes.contexts.prepareCalls)
        assertEquals(0, fakes.summary.calls)
        assertEquals(0, fakes.contexts.saveCalls)
    }

    @Test fun disablingAfterPreparationStillPreventsThePaidSummaryRequest() = runTest {
        val fakes = Fakes(toolTurnHistory())
        fakes.gate.laterBlock = AgentCompactionBlock.USER_DISABLED
        assertEquals(AgentCompactionResult.Skipped(AgentCompactionSkip.USER_DISABLED),
            fakes.service.compact(ref, chat))
        assertEquals(1, fakes.contexts.prepareCalls)
        assertEquals(0, fakes.summary.calls)
        assertEquals(0, fakes.contexts.saveCalls)
    }

    @Test fun unresolvedToolPairInHistoryIsSkippedWithoutSummary() = runTest {
        val call = toolCall("pending")
        val history = toolTurnHistory().dropLast(1) +
            AgentMessage.Assistant(MessageId("a3"), listOf(AssistantPart.ToolCall(call)))
        val fakes = Fakes(history)
        assertEquals(AgentCompactionResult.Skipped(AgentCompactionSkip.PENDING_TOOL_CALL),
            fakes.service.compact(ref, chat))
        assertEquals(0, fakes.summary.calls)
        assertEquals(0, fakes.contexts.saveCalls)
    }

    @Test fun stalePreviewIsSkippedBeforePayingForASummary() = runTest {
        val fakes = Fakes(toolTurnHistory())
        fakes.contexts.archivedDelta = 1
        assertEquals(AgentCompactionResult.Skipped(AgentCompactionSkip.STALE_CONTEXT),
            fakes.service.compact(ref, chat))
        assertEquals(0, fakes.summary.calls)
        assertEquals(0, fakes.contexts.saveCalls)
    }

    @Test fun summaryFailureLeavesTheOldContextAndIsNotRetried() = runTest {
        val fakes = Fakes(toolTurnHistory())
        fakes.summary.failure = AgentModelFailure(ModelFailureKind.NETWORK)
        assertEquals(AgentCompactionResult.Failed(AgentCompactionFailure.SUMMARY_FAILED, ModelFailureKind.NETWORK),
            fakes.service.compact(ref, chat))
        assertEquals(1, fakes.summary.calls)
        assertEquals(0, fakes.contexts.saveCalls)
        assertEquals("", fakes.contexts.saved)
    }

    @Test fun casLossOnSaveLeavesTheOldContext() = runTest {
        val fakes = Fakes(toolTurnHistory())
        fakes.contexts.saveFailure = IllegalStateException("Conversation changed")
        assertEquals(AgentCompactionResult.Failed(AgentCompactionFailure.PERSIST_FAILED),
            fakes.service.compact(ref, chat))
        assertEquals(1, fakes.summary.calls)
        assertEquals(1, fakes.contexts.saveCalls)
        assertEquals("", fakes.contexts.saved)
    }

    @Test fun cancellationPropagatesWithoutSaving() = runTest {
        val fakes = Fakes(toolTurnHistory())
        fakes.summary.failure = CancellationException("stopped")
        try {
            fakes.service.compact(ref, chat)
            fail("Compaction swallowed cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, fakes.summary.calls)
        assertEquals(0, fakes.contexts.saveCalls)
    }

    @Test fun oversizedSummaryIsRejectedBeforePersisting() = runTest {
        val fakes = Fakes(toolTurnHistory())
        fakes.summary.response = "x".repeat(policy.maxSummaryChars + 1)
        assertEquals(AgentCompactionResult.Failed(AgentCompactionFailure.SUMMARY_TOO_LARGE),
            fakes.service.compact(ref, chat))
        assertEquals(1, fakes.summary.calls)
        assertEquals(0, fakes.contexts.saveCalls)
        assertEquals("", fakes.contexts.saved)
    }

    @Test fun blankSummaryIsRejectedBeforePersisting() = runTest {
        val fakes = Fakes(toolTurnHistory())
        fakes.summary.response = "   "
        assertEquals(AgentCompactionResult.Failed(AgentCompactionFailure.INVALID_SUMMARY),
            fakes.service.compact(ref, chat))
        assertEquals(1, fakes.summary.calls)
        assertEquals(0, fakes.contexts.saveCalls)
    }
}
