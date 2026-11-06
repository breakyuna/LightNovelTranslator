package com.breakyuna.noveltranslator.nativecore.domain

/** Opaque process-local ownership. Implementations must reject forged/stale handles. */
interface ArchiveReadLease

interface RunLease { val identity: RunIdentity }
interface OperationLease
interface ProjectWriteLease
interface SkillWriteLease
interface AuthenticationWriteLease

enum class OperationKind { MODEL_REQUEST, TOOL, SCRIPT, HOST_REQUEST }

/**
 * Reserve synchronously before launching asynchronous work. No callback, network,
 * database transaction or coroutine suspension belongs inside a reservation.
 * Stop blocks new operations; holders must cancel/join their actual resources and
 * release their operation/write handles. finishRun is not a cancellation API.
 */
interface RuntimePort {
    fun beginArchiveRead(): ArchiveReadLease = throw UnsupportedOperationException("Archive reservation unavailable")
    fun endArchiveRead(lease: ArchiveReadLease) {}
    fun beginArchiveProjectWrite(lease: ArchiveReadLease): ProjectWriteLease =
        throw UnsupportedOperationException("Archive reservation unavailable")
    fun snapshot(): RuntimeSnapshot
    fun selectChat(chat: ChatIdentity?)
    fun beginRun(identity: RunIdentity, owner: RuntimeOwner): RunLease
    /** Select and reserve atomically; rejection leaves the previous selection intact. */
    fun beginRunSelectingChat(identity: RunIdentity, owner: RuntimeOwner, chat: ChatIdentity): RunLease
    fun beginOperation(run: RunLease, kind: OperationKind): OperationLease
    fun endOperation(operation: OperationLease)
    /** Blocks new model work while existing translation IO can settle. Not cancellation. */
    fun requestPause(run: RunLease): Boolean = false
    fun requestStop(run: RunLease): Boolean
    /** Optionally clear only this selection, atomically when this lease is actually released. */
    fun finishRun(run: RunLease, clearSelectedChat: ChatIdentity? = null)
    /** Execution-metadata finalization may persist cancellation while the real run is stopping. */
    fun beginRunMetadataWrite(run: RunLease): ProjectWriteLease = beginProjectWrite(run)
    fun beginProjectWrite(run: RunLease? = null): ProjectWriteLease
    fun endProjectWrite(write: ProjectWriteLease)
    fun beginSkillWrite(): SkillWriteLease
    fun endSkillWrite(write: SkillWriteLease)
    fun beginAuthenticationWrite(): AuthenticationWriteLease
    fun endAuthenticationWrite(write: AuthenticationWriteLease)
    fun close()
}
