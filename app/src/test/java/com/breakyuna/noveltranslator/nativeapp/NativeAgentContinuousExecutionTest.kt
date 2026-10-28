package com.breakyuna.noveltranslator.nativeapp

import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.TranslatorApplication
import com.breakyuna.noveltranslator.nativecore.agent.NativeKoogConversation
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeAgentContinuousExecutionTest {
    @Test fun repeatedReadsContinueBeyondEightRoundsAndAcrossKoogSlices() = runBlocking {
        execute(false)
    }
    @Test fun cancellationAfterACompletedSliceStartsNoFurtherRequests() = runBlocking {
        execute(true)
    }
    private suspend fun execute(cancel: Boolean) = withTimeout(30_000) {
        val app = ApplicationProvider.getApplicationContext<TranslatorApplication>()
        val graph = app.nativeGraph
        val project = graph.projects.createTxt(CommandId(UUID.randomUUID().toString()), "Continuous fixture",
            RelativePath("source.txt"), "source".toByteArray())
        val chat = graph.agentStore.currentChat(project.ref)
        val identity = RunIdentity(project.ref.projectId, chat, RunId(UUID.randomUUID().toString()), CommandId(UUID.randomUUID().toString()))
        graph.runtime.selectChat(ChatIdentity(project.ref.projectId, chat))
        val lease = graph.runtime.beginRun(identity, RuntimeOwner.AGENT)
        var requests = 0
        val gateway = object : AgentModelPort {
            override suspend fun complete(request: AgentModelRequest, emit: suspend (AgentModelEvent) -> Unit): AgentModelResponse {
                requests++
                if (cancel && requests == 35) throw CancellationException("Synthetic stop")
                val results = request.history.filterIsInstance<AgentMessage.ToolResult>()
                assertTrue(results.all { !it.isError })
                val parts = if (requests <= 160) listOf(AssistantPart.ToolCall(AgentToolCall(
                    ToolCallId("read-$requests"), "project_summary", JsonObjectValue.parse("{}", JsonInputLimits(4096, 4)))))
                    else listOf(AssistantPart.Text("done"))
                fun unknown() = TokenMeasurement(null, UsageSource.UNKNOWN)
                return AgentModelResponse(AgentMessage.Assistant(MessageId("response-$requests"), parts),
                    RequestUsage(unknown(), unknown(), unknown(), unknown(), unknown()),
                    if (requests <= 160) ModelStopReason.TOOL_CALLS else ModelStopReason.COMPLETE)
            }
        }
        val conversation = NativeKoogConversation(graph.runtime, graph.projects, graph.agentWorkspace,
            graph.agentStore, graph.agentStore, gateway, loopContext = graph.agentStore)
        try {
            try {
                val completion = conversation.send(lease, project.ref, graph.simulationConfiguration, "Keep reading until done")
                assertFalse(cancel)
                assertFalse(completion.waitingApproval)
                assertEquals(161, requests)
                assertTrue(graph.agentStore.modelHistory(project.ref, chat).size < 128)
                val archived = graph.agentStore.exportHistory(project.ref, chat)
                assertEquals(322, archived.size)
                assertEquals(1, archived.filterIsInstance<AgentMessage.User>().size)
            } catch (failure: CancellationException) {
                if (!cancel) throw failure
                assertEquals(35, requests)
            }
        } finally {
            graph.runtime.finishRun(lease)
            graph.runtime.selectChat(null)
            graph.close()
        }
    }
}
