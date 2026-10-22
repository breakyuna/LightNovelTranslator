package com.breakyuna.noveltranslator.nativecore.domain

/** Opaque process-local ownership. Implementations must reject forged/stale handles. */
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
    fun snapshot(): RuntimeSnapshot
    fun selectChat(chat: ChatIdentity?)
    fun beginRun(identity: RunIdentity, owner: RuntimeOwner): RunLease
    fun beginOperation(run: RunLease, kind: OperationKind): OperationLease
    fun endOperation(operation: OperationLease)
    fun requestStop(run: RunLease): Boolean
    fun finishRun(run: RunLease)
    fun beginProjectWrite(run: RunLease? = null): ProjectWriteLease
    fun endProjectWrite(write: ProjectWriteLease)
    fun beginSkillWrite(): SkillWriteLease
    fun endSkillWrite(write: SkillWriteLease)
    fun beginAuthenticationWrite(): AuthenticationWriteLease
    fun endAuthenticationWrite(write: AuthenticationWriteLease)
    fun close()
}
