package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.ModelKeyStatePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettingsPort
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

/** One shared process selector, optionally backed by credential-free durable rotation facts. */
class ModelKeyPool(private val settings: NativeModelSettingsPort, private val stateStore: ModelKeyStatePort? = null) {
    private val mutex = Mutex()
    private val unavailable = mutableSetOf<ModelConfigurationRef>()
    private val pools = LinkedHashMap<ModelConfigurationRef, KeyPool>()

    suspend fun select(reference: ModelConfigurationRef): KeySelection? = mutex.withLock {
        if (reference in unavailable) throw AgentModelFailure(ModelFailureKind.UNKNOWN)
        val pool = pools[reference] ?: load(reference)
        if (pool.entries.isEmpty()) {
            null
        } else {
            val selection = pool.select() ?: run {
                pools[reference] = pool
                throw AgentModelFailure(if (pool.enabledCount == 0) ModelFailureKind.AUTHENTICATION else ModelFailureKind.RATE_LIMIT)
            }
            pools[reference] = selection.pool
            // Persist cursor before dispatch; a failed write must not start another request.
            if (!persist(reference, selection.pool)) throw AgentModelFailure(ModelFailureKind.UNKNOWN)
            selection
        }
    }

    suspend fun success(reference: ModelConfigurationRef, selection: KeySelection?) {
        if (selection == null) return
        mutex.withLock {
            val pool = pools[reference] ?: return
            val updated = pool.recordSuccess(selection.entry.id)
            pools[reference] = updated
            persist(reference, updated) // Never discard a received model response because disk failed.
        }
    }

    suspend fun failure(reference: ModelConfigurationRef, selection: KeySelection?, kind: ModelFailureKind,
        retryAfterMillis: Long? = null) {
        if (selection == null) return
        val classified = KeyFailureKind.classify(kind) ?: return
        withContext(NonCancellable) {
            mutex.withLock {
                val pool = pools[reference] ?: return@withLock
                val updated = pool.recordFailure(selection.entry.id, classified, retryAfterMillis = retryAfterMillis)
                pools[reference] = updated
                persist(reference, updated) // Preserve the original provider failure.
            }
        }
    }

    /** Redacted diagnostic snapshot; credential values are never exposed. */
    suspend fun describe(reference: ModelConfigurationRef): List<KeyEntry> = mutex.withLock {
        Collections.unmodifiableList(ArrayList((pools[reference] ?: load(reference)).entries))
    }

    /** Explicit user action; disabling a key never selects a fallback before persistence succeeds. */
    suspend fun setEnabled(reference: ModelConfigurationRef, keyId: String, enabled: Boolean): List<KeyEntry> =
        mutex.withLock {
            val updated = (pools[reference] ?: load(reference)).setEnabled(keyId, enabled)
            pools[reference] = updated
            if (!persist(reference, updated)) throw AgentModelFailure(ModelFailureKind.UNKNOWN)
            Collections.unmodifiableList(ArrayList(updated.entries))
        }

    private suspend fun persist(reference: ModelConfigurationRef, pool: KeyPool): Boolean = try {
        stateStore?.write(pool.state(reference))
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { unavailable.add(reference); false }

    private suspend fun load(reference: ModelConfigurationRef): KeyPool {
        val fresh = KeyPool.of(settings.credentials(reference))
        val pool = try {
            val saved = stateStore?.read(reference)
            if (saved == null) fresh else {
                require(saved.configuration == reference)
                fresh.restore(saved)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Local persistence failures are not model-content failures. Never use fresh keys as fallback.
            throw AgentModelFailure(ModelFailureKind.UNKNOWN)
        }
        pools[reference] = pool
        return pool
    }
}
