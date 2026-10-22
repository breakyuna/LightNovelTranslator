package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CheckpointTest {
    private val empty = JsonObjectValue.parse("{}", JsonInputLimits(128, 4))
    private val usage = RequestUsage(
        TokenMeasurement(10, UsageSource.ACTUAL), TokenMeasurement(null, UsageSource.UNKNOWN),
        TokenMeasurement(null, UsageSource.UNKNOWN), TokenMeasurement(null, UsageSource.UNKNOWN),
        TokenMeasurement(5, UsageSource.ACTUAL),
    )
    private val run = RunIdentity(ProjectId("project"), ChatId("chat"), RunId("run"), CommandId("command"))

    @Test
    fun toolPendingCheckpointMustNameTheOpenCallsItStillHas() {
        val history = listOf(user("u"), calls("a", "one"))
        val checkpoint = checkpoint(CheckpointStage.TOOL_PENDING, history, listOf(ToolCallId("one")))
        assertEquals(1, checkpoint.pendingToolCalls.size)

        expectIllegalArgument {
            checkpoint(CheckpointStage.TOOL_PENDING, history, emptyList())
        }
        expectIllegalArgument {
            checkpoint(CheckpointStage.TOOL_PENDING, history, listOf(ToolCallId("other")))
        }
    }

    @Test
    fun completedHistoryCannotDeclarePendingCalls() {
        expectIllegalArgument {
            checkpoint(
                CheckpointStage.AFTER_RESPONSE,
                listOf(user("u"), calls("a", "one"), result("r", "one")),
                listOf(ToolCallId("one")),
            )
        }
    }

    @Test
    fun onlyCompactedCheckpointsCarryASummary() {
        val summary = CompactionSummary(MessageId("m"), "synthetic summary", 1)
        val compacted = CheckpointEnvelope(
            1, 1, run, BranchId("main"), CheckpointStage.COMPACTED,
            listOf(user("u")), emptyList(), summary, listOf(CommandId("c")), usage,
        )
        assertEquals(summary, compacted.summary)
        expectIllegalArgument {
            CheckpointEnvelope(
                1, 1, run, BranchId("main"), CheckpointStage.BEFORE_REQUEST,
                listOf(user("u")), emptyList(), summary, listOf(CommandId("c")), usage,
            )
        }
    }

    @Test
    fun versionsAndAcceptedCommandIdentitiesAreValidated() {
        expectIllegalArgument {
            CheckpointEnvelope(
                0, 1, run, BranchId("main"), CheckpointStage.BEFORE_REQUEST,
                listOf(user("u")), emptyList(), null, emptyList(), usage,
            )
        }
        expectIllegalArgument {
            CheckpointEnvelope(
                1, 1, run, BranchId("main"), CheckpointStage.BEFORE_REQUEST,
                listOf(user("u")), emptyList(), null, listOf(CommandId("c"), CommandId("c")), usage,
            )
        }
    }

    private fun checkpoint(
        stage: CheckpointStage,
        messages: List<AgentMessage>,
        pending: List<ToolCallId>,
    ) = CheckpointEnvelope(1, 1, run, BranchId("main"), stage, messages, pending, null, emptyList(), usage)

    private fun user(id: String) = AgentMessage.User(MessageId(id), listOf(UserPart.Text("synthetic")))
    private fun calls(id: String, vararg calls: String) = AgentMessage.Assistant(MessageId(id), calls.map {
        AssistantPart.ToolCall(AgentToolCall(ToolCallId(it), "lookup", empty))
    })
    private fun result(id: String, call: String, name: String = "lookup") =
        AgentMessage.ToolResult(MessageId(id), ToolCallId(call), name, empty, false)

    private fun expectIllegalArgument(body: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { body() }
    }
}
