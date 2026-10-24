package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentSteerControllerTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private val chat = ChatIdentity(ref.projectId, ChatId("chat"))
    private val identity = RunIdentity(ref.projectId, chat.chatId, RunId("run"), CommandId("run-command"))
    private class Store : AgentInputStatePort {
        var ledger: StoredAgentSteerLedger? = null
        override suspend fun loadInputQueue(ref: LocalProjectRef, chat: ChatId): StoredAgentInputQueue? = null
        override suspend fun replaceInputQueue(write: ProjectWriteLease, snapshot: StoredAgentInputQueue,
            settlement: AgentInputAppendSettlement?): StoredAgentInputQueue = error("unused")
        override suspend fun loadSteerLedger(ref: LocalProjectRef, chat: ChatId, run: RunIdentity) = ledger
        override suspend fun replaceSteerLedger(write: ProjectWriteLease, snapshot: StoredAgentSteerLedger): StoredAgentSteerLedger {
            check(snapshot.revision == (ledger?.revision ?: 0))
            return StoredAgentSteerLedger(snapshot.ref, snapshot.revision + 1, snapshot.state).also { ledger = it }
        }
    }

    @Test fun acceptedInputIsDurableBeforeExactlyOneBoundaryInjection() = runBlocking {
        val runtime = RuntimeCoordinator(); val store = Store()
        val controller = AgentSteerController(runtime, store)
        val lease = runtime.beginRunSelectingChat(identity, RuntimeOwner.AGENT, chat)
        try {
            controller.start(lease, ref)
            val command = AgentSteerCommand(CommandId("guide"), identity, chat, "Check all files")
            assertTrue(controller.submit(ref, command) is AgentSteerSubmitResult.Accepted)
            assertEquals(AgentSteerStatus.ACCEPTED, store.ledger!!.state.attempts.single().status)
            assertTrue(controller.submit(ref, command) is AgentSteerSubmitResult.Duplicate)
            controller.phase(lease, AgentRunStage.TOOL_EXECUTION)
            assertEquals(listOf("Check all files"), controller.boundary(lease).map { it.text })
            assertTrue(controller.boundary(lease).isEmpty())
            controller.finish(lease, AgentRunStage.FINISHED)
            assertEquals(AgentRunStage.FINISHED, store.ledger!!.state.stage)
            assertEquals(AgentSteerStatus.INJECTED, store.ledger!!.state.attempts.single().status)
        } finally { runtime.finishRun(lease); runtime.close() }
    }

    @Test fun stoppingRejectsInjectionAndPersistsPendingCancellation() = runBlocking {
        val runtime = RuntimeCoordinator(); val store = Store()
        val controller = AgentSteerController(runtime, store)
        val lease = runtime.beginRunSelectingChat(identity, RuntimeOwner.AGENT, chat)
        try {
            controller.start(lease, ref)
            controller.submit(ref, AgentSteerCommand(CommandId("guide"), identity, chat, "Next step"))
            runtime.requestStop(lease)
            try { controller.boundary(lease); fail("Injected after stop") } catch (_: IllegalArgumentException) { }
            controller.finish(lease, AgentRunStage.STOPPED)
            assertEquals(AgentRunStage.STOPPED, store.ledger!!.state.stage)
            assertEquals(AgentSteerStatus.CANCELLED, store.ledger!!.state.attempts.single().status)
        } finally { runtime.finishRun(lease); runtime.close() }
    }
}
