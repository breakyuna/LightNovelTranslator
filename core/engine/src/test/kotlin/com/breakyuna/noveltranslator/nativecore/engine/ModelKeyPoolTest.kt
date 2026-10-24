package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModelKeyPoolTest {
    private val reference = ModelConfigurationRef("remote", 1)
    private val settings = object : NativeModelSettingsPort {
        override suspend fun current(): NativeModelSettings? = null
        override suspend fun resolve(reference: ModelConfigurationRef): NativeModelSettings = error("unused")
        override suspend fun credential(reference: ModelConfigurationRef): String = error("credentials only")
        override suspend fun credentials(reference: ModelConfigurationRef): List<String> = listOf("key-1", "key-2")
        override suspend fun save(endpoint: String, model: String, key: String?): NativeModelSettings = error("unused")
    }

    @Test fun authFailureDisablesOnlyThatKeyAndRateLimitHonorsCooldown() = runBlocking {
        val pool = ModelKeyPool(settings)
        val first = requireNotNull(pool.select(reference))
        assertEquals("key-1", first.credential)
        pool.failure(reference, first, ModelFailureKind.AUTHENTICATION)
        val second = requireNotNull(pool.select(reference))
        assertEquals("key-2", second.credential)
        pool.success(reference, second)
        val third = requireNotNull(pool.select(reference))
        assertEquals("key-2", third.credential)
        pool.failure(reference, third, ModelFailureKind.RATE_LIMIT)
        val failure = try { pool.select(reference); null } catch (error: AgentModelFailure) { error }
        assertEquals(ModelFailureKind.RATE_LIMIT, failure?.kind)
    }

    @Test fun disabledCredentialsAreAuthenticationFailureRatherThanTransientRateLimit() = runBlocking {
        val pool = ModelKeyPool(settings)
        repeat(2) {
            val selected = requireNotNull(pool.select(reference))
            pool.failure(reference, selected, ModelFailureKind.AUTHENTICATION)
        }
        val failure = try { pool.select(reference); null } catch (error: AgentModelFailure) { error }
        assertEquals(ModelFailureKind.AUTHENTICATION, failure?.kind)
    }

    @Test fun recreatedSelectorRestoresRotationAndAuthenticationDisableWithoutSavingCredentials() = runBlocking {
        var saved: ModelKeyPoolState? = null
        val disk = object : ModelKeyStatePort {
            override suspend fun read(configuration: ModelConfigurationRef) = saved
            override suspend fun write(state: ModelKeyPoolState) { saved = state }
        }
        val first = ModelKeyPool(settings, disk)
        val key = requireNotNull(first.select(reference))
        first.failure(reference, key, ModelFailureKind.AUTHENTICATION)
        assertFalse(requireNotNull(saved).keys.first().enabled)
        val recreated = ModelKeyPool(settings, disk)
        assertEquals("key-2", requireNotNull(recreated.select(reference)).credential)
        assertFalse(recreated.describe(reference).first().enabled)
    }

    @Test fun corruptPersistenceNeverFallsBackToAnEnabledPool() = runBlocking {
        val disk = object : ModelKeyStatePort {
            override suspend fun read(configuration: ModelConfigurationRef): ModelKeyPoolState? = error("Corrupt fixture")
            override suspend fun write(state: ModelKeyPoolState) = Unit
        }
        val pool = ModelKeyPool(settings, disk)
        repeat(2) {
            val failure = try { pool.select(reference); null } catch (error: AgentModelFailure) { error }
            assertEquals(ModelFailureKind.UNKNOWN, failure?.kind)
        }
    }

    @Test fun unreadableOrIncompatiblePersistenceIsUnknownRatherThanContentFailure() = runBlocking {
        for (readFailure in listOf(java.io.IOException("Unavailable"), IllegalArgumentException("Invalid state"))) {
            val disk = object : ModelKeyStatePort {
                override suspend fun read(configuration: ModelConfigurationRef): ModelKeyPoolState? = throw readFailure
                override suspend fun write(state: ModelKeyPoolState) = error("Must not overwrite unreadable state")
            }
            val failure = try { ModelKeyPool(settings, disk).select(reference); null }
                catch (error: AgentModelFailure) { error }
            assertEquals(ModelFailureKind.UNKNOWN, failure?.kind)
        }
        val incompatible = object : ModelKeyStatePort {
            override suspend fun read(configuration: ModelConfigurationRef) = ModelKeyPoolState(configuration, 0,
                listOf(ModelKeyState(false, 1, 0, ModelKeyFailure.AUTHENTICATION)))
            override suspend fun write(state: ModelKeyPoolState) = error("Must not overwrite incompatible state")
        }
        val failure = try { ModelKeyPool(settings, incompatible).select(reference); null }
            catch (error: AgentModelFailure) { error }
        assertEquals(ModelFailureKind.UNKNOWN, failure?.kind)
    }

    @Test fun cancelledPersistenceReadRemainsCancellation() = runBlocking {
        val cancelled = kotlinx.coroutines.CancellationException("Cancelled read")
        val disk = object : ModelKeyStatePort {
            override suspend fun read(configuration: ModelConfigurationRef): ModelKeyPoolState? = throw cancelled
            override suspend fun write(state: ModelKeyPoolState) = error("Must not write after cancellation")
        }
        try { ModelKeyPool(settings, disk).select(reference); fail("Ignored cancellation") }
        catch (failure: kotlinx.coroutines.CancellationException) { assertSame(cancelled, failure) }
    }

    @Test fun failedRotationSaveRejectsDispatchAndFailedReceiptSavePreservesReturnedResponse() = runBlocking {
        var failWrites = false
        val disk = object : ModelKeyStatePort {
            override suspend fun read(configuration: ModelConfigurationRef): ModelKeyPoolState? = null
            override suspend fun write(state: ModelKeyPoolState) { if (failWrites) error("Synthetic storage failure") }
        }
        val pool = ModelKeyPool(settings, disk)
        val selected = requireNotNull(pool.select(reference))
        failWrites = true
        pool.success(reference, selected) // Already-received provider response must remain usable.
        val failure = try { pool.select(reference); null } catch (error: AgentModelFailure) { error }
        assertEquals(ModelFailureKind.UNKNOWN, failure?.kind)
        val fresh = ModelKeyPool(settings, disk)
        val rejected = try { fresh.select(reference); null } catch (error: AgentModelFailure) { error }
        assertEquals(ModelFailureKind.UNKNOWN, rejected?.kind)
    }

    @Test fun emptyCredentialListIsExplicitNoAuthNotCooling() = runBlocking {
        val noAuth = object : NativeModelSettingsPort {
            override suspend fun current(): NativeModelSettings? = null
            override suspend fun resolve(reference: ModelConfigurationRef): NativeModelSettings = error("unused")
            override suspend fun credential(reference: ModelConfigurationRef): String = ""
            override suspend fun save(endpoint: String, model: String, key: String?): NativeModelSettings = error("unused")
        }
        assertNull(ModelKeyPool(noAuth).select(reference))
    }
}
