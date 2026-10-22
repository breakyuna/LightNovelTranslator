package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.OperationKind
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RunLease
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeOwner
import com.breakyuna.noveltranslator.nativecore.domain.RuntimePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Runtime-owned scope adapter for G1/G5 prototypes. The caller supplies the
 * application/Service scope; page scopes must not own accepted long-running work.
 * No persistent command/recovery behavior is implied by this process-local API.
 */
class ManagedRunLauncher(private val runtime: RuntimePort) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(
        scope: CoroutineScope,
        identity: RunIdentity,
        owner: RuntimeOwner,
        block: suspend CoroutineScope.(RunOperations) -> Unit,
    ): RunningExecution {
        // Reserve before launch, not inside the coroutine's first suspension.
        val lease = runtime.beginRun(identity, owner)
        val job = try {
            // ATOMIC guarantees finally runs even for an already-cancelled parent.
            scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    currentCoroutineContext().ensureActive()
                    // Await all children and their finally blocks before releasing the run.
                    coroutineScope { block(RunOperations(runtime, lease)) }
                } finally {
                    runtime.finishRun(lease)
                }
            }
        } catch (failure: Throwable) {
            runtime.finishRun(lease)
            throw failure
        }
        return RunningExecution(runtime, lease, job)
    }
}

class RunningExecution internal constructor(
    private val runtime: RuntimePort,
    private val lease: RunLease,
    private val job: Job,
) {
    val identity: RunIdentity get() = lease.identity

    /** Non-blocking request; occupancy remains until the real coroutine finishes. */
    fun stop(): Boolean {
        if (!runtime.requestStop(lease)) return false
        job.cancel()
        return true
    }

    suspend fun join() = job.join()
}

class RunOperations internal constructor(
    private val runtime: RuntimePort,
    private val lease: RunLease,
) {
    /** The adapter must cancel/join native/HTTP resources before this body returns. */
    suspend fun <T> operation(kind: OperationKind, block: suspend CoroutineScope.() -> T): T {
        currentCoroutineContext().ensureActive()
        val operation = runtime.beginOperation(lease, kind)
        try {
            return coroutineScope {
                currentCoroutineContext().ensureActive()
                block()
            }
        } finally {
            runtime.endOperation(operation)
        }
    }

    /**
     * Serializes a prepared write and waits for restoration/cleanup. A database
     * adapter still owns its transaction, receipts and non-cancellable commit window.
     */
    suspend fun <T> projectWrite(block: suspend CoroutineScope.() -> T): T {
        currentCoroutineContext().ensureActive()
        val write = runtime.beginProjectWrite(lease)
        try {
            return coroutineScope {
                currentCoroutineContext().ensureActive()
                block()
            }
        } finally {
            runtime.endProjectWrite(write)
        }
    }
}
