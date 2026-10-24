package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettingsPort
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

/**
 * Process-local, per-configuration KeyPool selector. It never starts a request and
 * never retries: one explicit model call selects one available credential, and a
 * later explicit attempt may select another after the classified failure has been
 * recorded. Cooldown state is intentionally in memory; persistent key state belongs
 * to a later storage task and is not implied here.
 */
class ModelKeyPool(private val settings: NativeModelSettingsPort) {
    private val mutex = Mutex()
    private val pools = LinkedHashMap<ModelConfigurationRef, KeyPool>()

    suspend fun select(reference: ModelConfigurationRef): KeySelection? = mutex.withLock {
        val pool = pools[reference] ?: load(reference)
        if (pool.entries.isEmpty()) {
            null
        } else {
            val selection = pool.select() ?: run {
                pools[reference] = pool
                throw AgentModelFailure(ModelFailureKind.RATE_LIMIT)
            }
            pools[reference] = selection.pool
            selection
        }
    }

    suspend fun success(reference: ModelConfigurationRef, selection: KeySelection?) {
        if (selection == null) return
        mutex.withLock {
            val pool = pools[reference] ?: return
            pools[reference] = pool.recordSuccess(selection.entry.id)
        }
    }

    suspend fun failure(reference: ModelConfigurationRef, selection: KeySelection?, kind: ModelFailureKind) {
        if (selection == null) return
        val classified = KeyFailureKind.classify(kind) ?: return
        mutex.withLock {
            val pool = pools[reference] ?: return
            pools[reference] = pool.recordFailure(selection.entry.id, classified)
        }
    }

    /** Redacted diagnostic snapshot; credential values are never exposed. */
    suspend fun describe(reference: ModelConfigurationRef): List<KeyEntry> = mutex.withLock {
        Collections.unmodifiableList(ArrayList((pools[reference] ?: load(reference)).entries))
    }

    private suspend fun load(reference: ModelConfigurationRef): KeyPool =
        KeyPool.of(settings.credentials(reference)).also { pools[reference] = it }
}
