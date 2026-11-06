package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelRequestLimits
import com.breakyuna.noveltranslator.nativecore.domain.ModelRequestLimitsProvider
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil
import kotlin.math.min

/**
 * Process-owned FIFO queues and rate clocks, shared by Agent and translation calls.
 * Waiting adds no Runtime operation and consumes no dispatch permit. Callers may hold
 * their own durable preparation lease throughout the wait. No hidden retry.
 * No child scope is created: cancellation removes its own waiter and wakes the next.
 */
class ModelRequestScheduler(
    private val limits: (ModelConfigurationRef) -> ModelRequestLimits = { ModelRequestLimits() },
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val resourceKey: (ModelConfigurationRef) -> String = { it.id },
    /**
     * Optional persisted application defaults. When set, its value replaces the
     * per-configuration [limits] callback for every resource; when unset the
     * historical callback/default is preserved exactly. A changed provider value
     * for an existing pool is rejected instead of resetting its rate clock.
     */
    private val limitsProvider: ModelRequestLimitsProvider? = null,
    /** Per-profile overrides take precedence over the application default. */
    private val profileLimits: suspend (ModelConfigurationRef) -> ModelRequestLimits? = { null },
) {
    private class Pool(val limits: ModelRequestLimits, now: Long) {
        val queue = ArrayDeque<Any>()
        val changes = MutableStateFlow(0L)
        var active = 0
        var tokens = limits.concurrency.toDouble()
        var refilledAt = now
        var nextRpmAt: Double? = null
        fun changed() { changes.value = changes.value + 1 }
    }
    private val mutex = Mutex()
    private val pools = mutableMapOf<String, Pool>()

    suspend fun <T> execute(configuration: ModelConfigurationRef, block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val ticket = Any()
        // A persisted provider must be read outside the scheduler mutex; the historical
        // per-configuration callback remains inside it when no provider is configured.
        val providedLimits = profileLimits(configuration) ?: limitsProvider?.current()
        val pool = mutex.withLock {
            // Configuration revisions are immutable request identities, not new rate budgets.
            // By default all revisions of one logical configuration share their resource clock.
            val key = resourceKey(configuration)
            require(key.isNotBlank())
            // Bound process metadata without evicting an existing rate clock and granting a new burst.
            require(pools.containsKey(key) || pools.size < 256) { "Too many model configurations" }
            val resolvedLimits = providedLimits ?: limits(configuration)
            val value = pools.getOrPut(key) { Pool(resolvedLimits, nowMillis()) }
            require(value.limits == resolvedLimits) { "Model resource limits changed" }
            require(value.queue.size < 256) { "Model request queue is full" }
            value.queue.addLast(ticket)
            value.changed()
            value
        }
        var acquired = false
        try {
            while (!acquired) {
                currentCoroutineContext().ensureActive()
                var version = 0L
                val waitMillis = mutex.withLock {
                    version = pool.changes.value
                    val now = nowMillis()
                    val elapsed = (now - pool.refilledAt).coerceAtLeast(0)
                    pool.tokens = min(pool.limits.concurrency.toDouble(),
                        pool.tokens + elapsed.toDouble() * pool.limits.concurrency / 1000)
                    pool.refilledAt = maxOf(now, pool.refilledAt)
                    val rateDelay = if (pool.limits.requestsPerMinute > 0)
                        ((pool.nextRpmAt ?: now.toDouble()) - now).coerceAtLeast(0.0)
                    else ((1 - pool.tokens) * 1000 / pool.limits.concurrency).coerceAtLeast(0.0)
                    if (pool.queue.firstOrNull() === ticket && pool.active < pool.limits.concurrency) {
                        if (rateDelay <= 0) {
                            pool.queue.removeFirst()
                            pool.active++
                            if (pool.limits.requestsPerMinute > 0)
                                pool.nextRpmAt = now + 60_000.0 / pool.limits.requestsPerMinute
                            else pool.tokens = (pool.tokens - 1).coerceAtLeast(0.0)
                            acquired = true
                            pool.changed()
                            0L
                        } else ceil(rateDelay).toLong().coerceAtLeast(1)
                    } else null
                }
                if (!acquired) {
                    if (waitMillis == null) pool.changes.first { it != version }
                    else withTimeoutOrNull(waitMillis) { pool.changes.first { it != version } }
                }
            }
            currentCoroutineContext().ensureActive()
            // Deliberately no dispatcher boundary after the response: completed provider receipts
            // must remain available to AgentRequestBoundary even if Stop races with delivery.
            return block()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (acquired) pool.active-- else pool.queue.remove(ticket)
                    pool.changed()
                }
            }
        }
    }
}
