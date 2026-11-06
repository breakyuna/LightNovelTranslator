package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.ModelCatalogSnapshot
import com.breakyuna.noveltranslator.nativecore.domain.ModelCatalogSnapshotPort
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelDescriptor
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProbeServiceTest {
    private val endpoint = "http://localhost:1234/v1"
    private val configuration = ModelConfigurationRef("fixture", 1)

    private fun snapshot() = ModelCatalogSnapshot(endpoint, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS, 10,
        listOf(ModelDescriptor("model")))

    private fun catalog(calls: () -> Unit = {}): ModelCatalogSnapshotPort =
        ModelCatalogSnapshotPort { _, _, _, _ -> calls(); snapshot() }

    @Test fun constructionAndMetadataProbePerformNoImplicitGeneration() = runBlocking {
        var catalogCalls = 0
        var gatewayCalls = 0
        val service = ModelProbeService(catalog { catalogCalls++ }, ModelProbeGateway { _, _, _ ->
            gatewayCalls++
            ModelProbeCompletion("unused")
        })
        assertEquals(0, catalogCalls)
        assertEquals(0, gatewayCalls)

        val result = service.probeMetadata(endpoint)
        assertTrue(result is ModelProbeResult.MetadataSuccess)
        assertEquals(1, catalogCalls)
        assertEquals(0, gatewayCalls)
        assertEquals(listOf("model"), (result as ModelProbeResult.MetadataSuccess).snapshot.descriptors.map { it.id })
    }

    @Test fun metadataFailuresAreClassifiedAndNotRetried() = runBlocking {
        var calls = 0
        val service = ModelProbeService(ModelCatalogSnapshotPort { _, _, _, _ ->
            calls++
            throw AgentModelFailure(ModelFailureKind.RATE_LIMIT, 5_000)
        })
        val result = service.probeMetadata(endpoint) as ModelProbeResult.Failure
        assertEquals(ModelProbeFailure.RATE_LIMIT, result.kind)
        assertEquals(5_000L, result.retryAfterMillis)
        assertEquals(1, calls)
    }

    @Test fun generationIsBoundedAndRetriedByNobody() = runBlocking {
        var calls = 0
        var requestedTokens = 0
        var requestedPrompt = ""
        val service = ModelProbeService(catalog(), ModelProbeGateway { _, prompt, maxOutputTokens ->
            calls++
            requestedPrompt = prompt
            requestedTokens = maxOutputTokens
            ModelProbeCompletion("secret-preview-".repeat(100))
        }, ModelProbeBudget(maxOutputTokens = 7, maxPreviewChars = 8))
        val result = service.probeGeneration(configuration) as ModelProbeResult.GenerationSuccess
        assertEquals(8, result.preview.length)
        assertTrue(result.truncated)
        assertEquals(7, requestedTokens)
        assertEquals(ModelProbeService.MINIMAL_PROMPT, requestedPrompt)
        assertEquals(1, calls)
        assertFalse(result.toString().contains("secret-preview"))
    }

    @Test fun generationFailureIsClassifiedWithoutAnAutomaticRetry() = runBlocking {
        var calls = 0
        val service = ModelProbeService(catalog(), ModelProbeGateway { _, _, _ ->
            calls++
            throw AgentModelFailure(ModelFailureKind.SERVER)
        })
        val result = service.probeGeneration(configuration) as ModelProbeResult.Failure
        assertEquals(ModelProbeFailure.SERVER, result.kind)
        assertEquals(1, calls)
    }

    @Test fun probeTimeoutIsClassifiedWithoutRetrying() = runBlocking {
        var calls = 0
        val service = ModelProbeService(catalog(), ModelProbeGateway { _, _, _ ->
            calls++
            withTimeout(1) { delay(1_000) }
            ModelProbeCompletion("late")
        })
        val result = service.probeGeneration(configuration) as ModelProbeResult.Failure
        assertEquals(ModelProbeFailure.TIMEOUT, result.kind)
        assertEquals(1, calls)
    }

    @Test fun generationWithoutAnInjectedGatewayDoesNotPerformIo() = runBlocking {
        var catalogCalls = 0
        val service = ModelProbeService(catalog { catalogCalls++ })
        assertEquals(ModelProbeResult.Failure(ModelProbeFailure.CONFIGURATION),
            service.probeGeneration(configuration))
        assertEquals(0, catalogCalls)
    }

    @Test fun callerCancellationPropagatesInsteadOfBecomingAFailure() = runBlocking {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val service = ModelProbeService(catalog(), ModelProbeGateway { _, _, _ ->
            calls++
            entered.complete(Unit)
            awaitCancellation()
        })
        val job = launch { service.probeGeneration(configuration) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, calls)
    }

    @Test fun gatewayCancellationIsRethrown() = runBlocking {
        val service = ModelProbeService(catalog(), ModelProbeGateway { _, _, _ ->
            throw CancellationException("explicit")
        })
        var propagated = false
        try {
            service.probeGeneration(configuration)
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }
}
