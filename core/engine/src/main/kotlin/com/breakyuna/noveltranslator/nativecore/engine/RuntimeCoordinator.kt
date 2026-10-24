package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ActiveRun
import com.breakyuna.noveltranslator.nativecore.domain.AuthenticationWriteLease
import com.breakyuna.noveltranslator.nativecore.domain.ChatIdentity
import com.breakyuna.noveltranslator.nativecore.domain.OperationKind
import com.breakyuna.noveltranslator.nativecore.domain.OperationLease
import com.breakyuna.noveltranslator.nativecore.domain.ProjectWriteLease
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RunLease
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeOwner
import com.breakyuna.noveltranslator.nativecore.domain.RuntimePhase
import com.breakyuna.noveltranslator.nativecore.domain.RuntimePort
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeSnapshot
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeUnavailable
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeUnavailable.Reason
import com.breakyuna.noveltranslator.nativecore.domain.SkillWriteLease
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The sole in-process reservation owner for the native runtime prototype.
 * All identities and occupancy are checked under one short monitor. Handles use
 * object identity, so a late finally/Stop cannot mutate a subsequent execution.
 * This class neither launches work nor persists receipts; it cannot by itself
 * prove cancellation, command deduplication or recovery after process death.
 */
class RuntimeCoordinator : RuntimePort {
    private class Run(
        override val identity: RunIdentity,
        val owner: RuntimeOwner,
    ) : RunLease {
        var phase = RuntimePhase.RUNNING
        var clearSelectedChat: ChatIdentity? = null
        val operations = mutableSetOf<Operation>()
    }

    private class Operation(val run: Run, val kind: OperationKind) : OperationLease
    private class Write(val run: Run?) : ProjectWriteLease
    private class SkillWrite : SkillWriteLease
    private class AuthenticationWrite : AuthenticationWriteLease

    private val monitor = Any()
    private var revision = 0L
    private var selected: ChatIdentity? = null
    private var active: Run? = null
    private var projectWrite: Write? = null
    private var skillWrite: SkillWrite? = null
    private var authenticationWrite: AuthenticationWrite? = null
    private var closed = false
    private val mutableSnapshots = MutableStateFlow(snapshotLocked())
    val snapshots: StateFlow<RuntimeSnapshot> = mutableSnapshots.asStateFlow()

    override fun snapshot(): RuntimeSnapshot = synchronized(monitor) { snapshotLocked() }

    override fun selectChat(chat: ChatIdentity?): Unit = mutate {
        ensureOpen()
        if (active != null || projectWrite != null) unavailable(Reason.BUSY)
        if (selected != chat) {
            selected = chat
            advanceLocked()
        }
    }

    override fun beginRun(identity: RunIdentity, owner: RuntimeOwner): RunLease = mutate {
        reserveRunLocked(identity, owner, selected)
    }

    override fun beginRunSelectingChat(identity: RunIdentity, owner: RuntimeOwner, chat: ChatIdentity): RunLease = mutate {
        reserveRunLocked(identity, owner, chat)
    }

    private fun reserveRunLocked(identity: RunIdentity, owner: RuntimeOwner, chat: ChatIdentity?): RunLease {
        ensureOpen()
        if (active != null || projectWrite != null || authenticationWrite != null ||
            (owner == RuntimeOwner.AGENT && skillWrite != null)
        ) unavailable(Reason.BUSY)
        if (chat == null || identity.projectId != chat.projectId ||
            (identity.chatId != null && identity.chatId != chat.chatId) ||
            (owner == RuntimeOwner.AGENT && identity.chatId == null)
        ) unavailable(Reason.WRONG_PROJECT)
        return Run(identity, owner).also {
            selected = chat
            active = it
            advanceLocked()
        }
    }

    override fun beginOperation(run: RunLease, kind: OperationKind): OperationLease = mutate {
        ensureOpen()
        val current = currentRun(run)
        if (!(current.owner == RuntimeOwner.TRANSLATION && current.phase == RuntimePhase.PAUSING && kind == OperationKind.HOST_REQUEST))
            ensureRunning(current)
        Operation(current, kind).also {
            current.operations.add(it)
            advanceLocked()
        }
    }

    override fun endOperation(operation: OperationLease): Unit = mutate {
        val handle = operation as? Operation
        if (handle != null && active === handle.run && handle.run.operations.remove(handle)) {
            releaseFinishedRunLocked()
            advanceLocked()
        }
    }

    override fun requestPause(run: RunLease): Boolean = mutate {
        if (active !== run) return@mutate false
        val current = active ?: return@mutate false
        if (current.owner != RuntimeOwner.TRANSLATION || current.phase !in setOf(RuntimePhase.RUNNING, RuntimePhase.PAUSING))
            return@mutate false
        if (current.phase == RuntimePhase.RUNNING) {
            current.phase = RuntimePhase.PAUSING
            advanceLocked()
        }
        true
    }

    override fun requestStop(run: RunLease): Boolean = mutate {
        if (active !== run) return@mutate false
        val current = active ?: return@mutate false
        if (current.phase == RuntimePhase.RUNNING || current.phase == RuntimePhase.PAUSING) {
            current.phase = RuntimePhase.STOPPING
            advanceLocked()
        }
        true
    }

    override fun finishRun(run: RunLease, clearSelectedChat: ChatIdentity?): Unit = mutate {
        if (active === run) {
            val current = active!!
            if (current.phase != RuntimePhase.FINISHING) current.clearSelectedChat = clearSelectedChat
            current.phase = RuntimePhase.FINISHING
            releaseFinishedRunLocked()
            advanceLocked()
        }
    }

    override fun beginProjectWrite(run: RunLease?): ProjectWriteLease = mutate {
        ensureOpen()
        if (projectWrite != null) unavailable(Reason.BUSY)
        val current = if (run != null) {
            currentRun(run).also {
                if (!(it.owner == RuntimeOwner.TRANSLATION && it.phase == RuntimePhase.PAUSING)) ensureRunning(it)
                if (it.owner == RuntimeOwner.MODEL_PROBE) unavailable(Reason.BUSY)
            }
        } else {
            if (active != null) unavailable(Reason.BUSY)
            null
        }
        Write(current).also {
            projectWrite = it
            advanceLocked()
        }
    }

    override fun endProjectWrite(write: ProjectWriteLease): Unit = mutate {
        if (projectWrite === write) {
            projectWrite = null
            releaseFinishedRunLocked()
            advanceLocked()
        }
    }

    override fun beginSkillWrite(): SkillWriteLease = mutate {
        ensureOpen()
        if (skillWrite != null || active?.owner == RuntimeOwner.AGENT) unavailable(Reason.BUSY)
        SkillWrite().also {
            skillWrite = it
            advanceLocked()
        }
    }

    override fun endSkillWrite(write: SkillWriteLease): Unit = mutate {
        if (skillWrite === write) {
            skillWrite = null
            advanceLocked()
        }
    }

    override fun beginAuthenticationWrite(): AuthenticationWriteLease = mutate {
        ensureOpen()
        if (active != null || authenticationWrite != null) unavailable(Reason.BUSY)
        AuthenticationWrite().also {
            authenticationWrite = it
            advanceLocked()
        }
    }

    override fun endAuthenticationWrite(write: AuthenticationWriteLease): Unit = mutate {
        if (authenticationWrite === write) {
            authenticationWrite = null
            advanceLocked()
        }
    }

    /** Stop accepting work. Existing owners still have to finish and release. */
    override fun close(): Unit = mutate {
        if (!closed) {
            closed = true
            active?.let { if (it.phase == RuntimePhase.RUNNING) it.phase = RuntimePhase.STOPPING }
            advanceLocked()
        }
    }

    private fun currentRun(run: RunLease): Run {
        if (active !== run) unavailable(Reason.STALE_EXECUTION)
        return active ?: unavailable(Reason.STALE_EXECUTION)
    }

    private fun ensureOpen() {
        if (closed) unavailable(Reason.CLOSED)
    }

    private fun ensureRunning(run: Run) {
        if (run.phase != RuntimePhase.RUNNING) unavailable(Reason.STOPPING)
    }

    private fun releaseFinishedRunLocked() {
        val run = active ?: return
        if (run.phase == RuntimePhase.FINISHING && run.operations.isEmpty() && projectWrite?.run !== run) {
            if (run.clearSelectedChat != null && selected == run.clearSelectedChat) selected = null
            active = null
        }
    }

    private fun snapshotLocked() = RuntimeSnapshot(
        revision = revision,
        selectedChat = selected,
        activeRun = active?.let { ActiveRun(it.identity, it.owner, it.phase, it.operations.size, it.operations.count { op -> op.kind == OperationKind.MODEL_REQUEST }) },
        projectWriteRunning = projectWrite != null,
        skillWriteRunning = skillWrite != null,
        authenticationWriteRunning = authenticationWrite != null,
        closed = closed,
    )

    private fun advanceLocked() {
        revision += 1
    }

    private fun <T> mutate(action: () -> T): T {
        val (result, snapshot) = synchronized(monitor) {
            action() to snapshotLocked()
        }
        // A StateFlow collector can resume inline. Never notify it under monitor.
        // Another mutation may publish first; revisions prevent late regression.
        while (true) {
            val published = mutableSnapshots.value
            if (published.revision >= snapshot.revision) break
            if (mutableSnapshots.compareAndSet(published, snapshot)) break
        }
        return result
    }

    private fun unavailable(reason: Reason): Nothing = throw RuntimeUnavailable(reason)
}
