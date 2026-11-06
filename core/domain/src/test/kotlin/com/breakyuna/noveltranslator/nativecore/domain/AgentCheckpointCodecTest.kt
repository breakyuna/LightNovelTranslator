package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentCheckpointCodecTest {
    private val empty = JsonObjectValue.parse("{}", JsonInputLimits(128, 4))
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(3))
    private val chat = ChatId("chat")
    private val branch = BranchId("main")
    private val run = RunIdentity(ref.projectId, chat, RunId("run"), CommandId("command"))
    private val usage = RequestUsage(
        TokenMeasurement(120, UsageSource.ACTUAL),
        TokenMeasurement(40, UsageSource.ACTUAL),
        TokenMeasurement(null, UsageSource.UNKNOWN),
        TokenMeasurement(7, UsageSource.ESTIMATED),
        TokenMeasurement(30, UsageSource.ACTUAL),
    )

    @Test
    fun toolPendingRoundTripKeepsMessagesCallsUsageAndScope() {
        val messages = listOf(
            user("u1"),
            AgentMessage.Assistant(MessageId("a1"), listOf(
                AssistantPart.Text("checking"),
                AssistantPart.Thinking("not persisted as user text", null),
                AssistantPart.ToolCall(AgentToolCall(ToolCallId("call-1"), "read_item",
                    JsonObjectValue.parse("{\"item_id\":1}", JsonInputLimits(128, 4)), "provider-call")),
            )),
        )
        val original = CheckpointEnvelope(
            version = 1,
            strategyVersion = 1,
            run = run,
            branchId = branch,
            stage = CheckpointStage.TOOL_PENDING,
            messages = messages,
            pendingToolCalls = listOf(ToolCallId("call-1")),
            summary = null,
            acceptedCommands = listOf(CommandId("accepted")),
            usage = usage,
            projectRef = ref,
        )

        val decoded = AgentCheckpointCodec.decode(AgentCheckpointCodec.encode(original))

        assertEnvelopeEquals(original, decoded)
        assertEquals(listOf(ToolCallId("call-1")), decoded.pendingToolCalls)
        assertEquals(listOf(CommandId("accepted")), decoded.acceptedCommands)
        assertEquals(usage, decoded.usage)
        assertEquals(ref, decoded.projectRef)
        assertEquals("not persisted as user text",
            ((decoded.messages[1] as AgentMessage.Assistant).parts[1] as AssistantPart.Thinking).text)
    }

    @Test
    fun compactedRoundTripKeepsSummaryAndDoesNotInventPendingCalls() {
        val original = CheckpointEnvelope(
            version = 1,
            strategyVersion = 1,
            run = run,
            branchId = branch,
            stage = CheckpointStage.COMPACTED,
            messages = listOf(user("u1")),
            pendingToolCalls = emptyList(),
            summary = CompactionSummary(MessageId("summary-cut"), "short summary", 2),
            acceptedCommands = emptyList(),
            usage = usage,
            projectRef = ref,
        )

        val decoded = AgentCheckpointCodec.decode(AgentCheckpointCodec.encode(original))

        assertEnvelopeEquals(original, decoded)
        assertEquals("short summary", decoded.summary!!.text)
        assertEquals(MessageId("summary-cut"), decoded.summary!!.upToMessageId)
        assertEquals(2, decoded.summary!!.version)
    }

    @Test
    fun durableEncodeRequiresScopeButLegacyInMemoryEnvelopeStillConstructs() {
        val unscoped = CheckpointEnvelope(
            1, 1, run, branch, CheckpointStage.BEFORE_REQUEST,
            listOf(user("u1")), emptyList(), null, emptyList(), usage,
        )
        assertThrows(IllegalArgumentException::class.java) { AgentCheckpointCodec.encode(unscoped) }
        assertEquals(null, unscoped.projectRef)
    }

    @Test
    fun unknownVersionAndUnknownFieldsAreRejected() {
        val original = CheckpointEnvelope(
            1, 1, run, branch, CheckpointStage.AFTER_RESPONSE,
            listOf(user("u1"), AgentMessage.Assistant(MessageId("a1"), listOf(AssistantPart.Text("ok")))),
            emptyList(), null, emptyList(), usage, ref,
        )
        val encoded = AgentCheckpointCodec.encode(original)
        val root = JsonObjectValue.parse(encoded, JsonInputLimits(AgentCheckpointCodec.MAX_ENCODED_BYTES, 24)).json
        val unknownVersion = buildJsonObject {
            root.forEach { (key, value) -> put(key, value) }
            put("version", JsonPrimitive(2))
        }.toString()
        assertThrows(IllegalArgumentException::class.java) { AgentCheckpointCodec.decode(unknownVersion) }

        val unknownField = buildJsonObject {
            root.forEach { (key, value) -> put(key, value) }
            put("unexpected", JsonPrimitive("value"))
        }.toString()
        assertThrows(IllegalArgumentException::class.java) { AgentCheckpointCodec.decode(unknownField) }
    }

    @Test
    fun scopeMustBelongToTheRunProject() {
        assertThrows(IllegalArgumentException::class.java) {
            CheckpointEnvelope(
                1, 1, run, branch, CheckpointStage.BEFORE_REQUEST,
                listOf(user("u1")), emptyList(), null, emptyList(), usage,
                ref.copy(projectId = ProjectId("other")),
            )
        }
    }

    @Test
    fun encodingAFutureVersionFailsInsteadOfDowngradingToV1() {
        val future = CheckpointEnvelope(
            2, 1, run, branch, CheckpointStage.BEFORE_REQUEST,
            listOf(user("u1")), emptyList(), null, emptyList(), usage, ref,
        )
        assertThrows(IllegalArgumentException::class.java) { AgentCheckpointCodec.encode(future) }
    }

    private fun assertEnvelopeEquals(expected: CheckpointEnvelope, actual: CheckpointEnvelope) {
        assertEquals(expected.version, actual.version)
        assertEquals(expected.strategyVersion, actual.strategyVersion)
        assertEquals(expected.run, actual.run)
        assertEquals(expected.branchId, actual.branchId)
        assertEquals(expected.stage, actual.stage)
        assertEquals(expected.messages.map(AgentMessageCodec::encode), actual.messages.map(AgentMessageCodec::encode))
        assertEquals(expected.pendingToolCalls, actual.pendingToolCalls)
        assertEquals(expected.summary, actual.summary)
        assertEquals(expected.acceptedCommands, actual.acceptedCommands)
        assertEquals(expected.usage, actual.usage)
        assertEquals(expected.projectRef, actual.projectRef)
    }

    private fun user(id: String) = AgentMessage.User(MessageId(id), listOf(UserPart.Text("payload")))
}
