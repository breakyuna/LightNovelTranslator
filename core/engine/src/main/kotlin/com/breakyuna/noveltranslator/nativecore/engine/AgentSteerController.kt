package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One process owner; restored ledgers are inert and never automatically injected. */
class AgentSteerController(private val runtime: RuntimePort, private val store: AgentInputStatePort) : AgentSteerExecutionPort {
    private val mutex = Mutex()
    private var active: Session? = null
    private class Session(val lease: RunLease, var ledger: StoredAgentSteerLedger)

    suspend fun start(lease: RunLease, ref: LocalProjectRef) = mutex.withLock {
        check(active == null)
        require(runtime.snapshot().activeRun?.identity == lease.identity)
        val chat = requireNotNull(lease.identity.chatId)
        require(store.loadSteerLedger(ref, chat, lease.identity) == null) { "Existing steer run requires recovery" }
        val state = AgentSteerState.start(lease.identity, ChatIdentity(ref.projectId, chat))
        val session = Session(lease, StoredAgentSteerLedger.initial(ref, state))
        persist(session, state)
        active = session
    }

    suspend fun submit(ref: LocalProjectRef, command: AgentSteerCommand): AgentSteerSubmitResult = mutex.withLock {
        val session = requireNotNull(active) { "No active steer run" }
        require(session.ledger.ref == ref && session.lease.identity == command.run)
        running(session)
        val result = session.ledger.state.submit(command)
        when (result) {
            is AgentSteerSubmitResult.Accepted -> persist(session, result.state)
            is AgentSteerSubmitResult.Duplicate -> Unit
            is AgentSteerSubmitResult.Rejected -> Unit
        }
        result
    }

    override suspend fun phase(lease: RunLease, stage: AgentRunStage) = mutex.withLock {
        val session = session(lease)
        running(session)
        val next = session.ledger.state.moveTo(stage) as? AgentSteerStageResult.Applied
            ?: error("Invalid steer phase")
        persist(session, next.state)
    }

    override suspend fun boundary(lease: RunLease): List<AgentSteerInjection> = mutex.withLock {
        val session = session(lease)
        running(session)
        val boundary = session.ledger.state.moveTo(AgentRunStage.TOOL_LOOP_BOUNDARY) as AgentSteerStageResult.Applied
        persist(session, boundary.state)
        running(session)
        when (val result = session.ledger.state.injectAtToolBoundary()) {
            is AgentSteerInjectionResult.Injected -> {
                persist(session, result.state)
                result.injections
            }
            is AgentSteerInjectionResult.NothingToInject -> emptyList()
            is AgentSteerInjectionResult.Rejected -> error("Invalid steer boundary")
        }
    }

    suspend fun finish(lease: RunLease, stage: AgentRunStage) = mutex.withLock {
        val session = active?.takeIf { it.lease === lease } ?: return@withLock
        try {
            if (stage == AgentRunStage.STOPPED) {
                val stopping = session.ledger.state.stop() as AgentSteerStageResult.Applied
                persist(session, stopping.state)
                persist(session, (session.ledger.state.markStopped() as AgentSteerStageResult.Applied).state)
            } else {
                val next = if (stage == AgentRunStage.FINISHED) session.ledger.state.finish() else session.ledger.state.fail()
                persist(session, (next as AgentSteerStageResult.Applied).state)
            }
        } finally { active = null }
    }

    private fun session(lease: RunLease): Session = requireNotNull(active?.takeIf { it.lease === lease })
    private fun running(session: Session) {
        val run = runtime.snapshot().activeRun
        require(run?.identity == session.lease.identity && run.phase == RuntimePhase.RUNNING)
    }
    private suspend fun persist(session: Session, state: AgentSteerState) {
        val write = runtime.beginRunMetadataWrite(session.lease)
        try {
            if (state.stage.active) running(session)
            session.ledger = store.replaceSteerLedger(write, session.ledger.withState(state))
        }
        finally { runtime.endProjectWrite(write) }
    }
}
