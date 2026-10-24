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
