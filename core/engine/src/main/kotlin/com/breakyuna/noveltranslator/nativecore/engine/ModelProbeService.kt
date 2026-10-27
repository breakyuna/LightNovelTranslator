package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Bounded limits for one explicitly requested connectivity probe. */
data class ModelProbeBudget(
    val maxOutputTokens: Int = 16,
    val maxPreviewChars: Int = 1024,
    val timeoutMillis: Long = 30_000,
) {
    init {
        require(maxOutputTokens in 1..1024) { "Invalid probe output budget" }
        require(maxPreviewChars in 1..8192) { "Invalid probe preview budget" }
        require(timeoutMillis in 1_000L..120_000L) { "Invalid probe timeout" }
    }
}

/** Sanitized probe failure. Cancellation is never represented here; it propagates. */
enum class ModelProbeFailure {
    NETWORK,
    TIMEOUT,
    RATE_LIMIT,
    SERVER,
    AUTHENTICATION,
    PARSING,
    CONTENT,
    EMPTY_RESPONSE,
    CONFIGURATION,
    STORAGE,
    UNKNOWN;

    companion object {
        internal fun of(kind: ModelFailureKind): ModelProbeFailure = when (kind) {
            ModelFailureKind.NETWORK -> NETWORK
            ModelFailureKind.TIMEOUT -> TIMEOUT
            ModelFailureKind.RATE_LIMIT -> RATE_LIMIT
            ModelFailureKind.SERVER -> SERVER
            ModelFailureKind.AUTHENTICATION -> AUTHENTICATION
            ModelFailureKind.PARSING -> PARSING
            ModelFailureKind.EMPTY_RESPONSE -> EMPTY_RESPONSE
            ModelFailureKind.CONTEXT_OVERFLOW,
            ModelFailureKind.CONTENT,
            ModelFailureKind.TRUNCATED,
            ModelFailureKind.CONTENT_FILTERED -> CONTENT
            ModelFailureKind.UNKNOWN -> UNKNOWN
        }
    }
}

/** One probe outcome. No history, task, profile or usage ledger write is implied. */
sealed interface ModelProbeResult {
    data class MetadataSuccess(val snapshot: ModelCatalogSnapshot, val elapsedMillis: Long) : ModelProbeResult

    data class GenerationSuccess(
        val preview: String,
        val truncated: Boolean,
        val elapsedMillis: Long,
        val usage: RequestUsage? = null,
    ) : ModelProbeResult {
        override fun toString(): String =
            "GenerationSuccess(chars=${preview.length}, truncated=$truncated, " +
                "elapsedMillis=$elapsedMillis, usage=${if (usage == null) "none" else "present"})"
    }

    data class Failure(val kind: ModelProbeFailure, val retryAfterMillis: Long? = null) : ModelProbeResult {
        init { require(retryAfterMillis == null || retryAfterMillis >= 0) }
    }
}

/** Opaque minimal completion returned by an injected gateway. Preview text is never logged. */
class ModelProbeCompletion(val text: String, val usage: RequestUsage? = null) {
    init { require(text.length <= 2 * 1024 * 1024) { "Probe completion too large" } }

    override fun toString(): String =
        "ModelProbeCompletion(chars=${text.length}, usage=${if (usage == null) "none" else "present"})"
}

/**
 * Injected generation boundary. Implementations resolve the credential for the exact
 * configuration and account for one request containing exactly [prompt]. They must not
 * retry; the service invokes this at most once per explicit
 * [ModelProbeService.probeGeneration].
 */
fun interface ModelProbeGateway {
    suspend fun minimalCompletion(configuration: ModelConfigurationRef, prompt: String,
        maxOutputTokens: Int): ModelProbeCompletion
}

/**
 * Explicit-only connectivity probes. Construction performs no I/O and does not register
 * observers, so entering a page cannot trigger a request. Metadata probing reuses the
 * GET-only catalog and never calls [gateway]; generation probing needs an injected
 * gateway and is never retried or written back to history, tasks or profiles.
 */
class ModelProbeService(
    private val catalog: ModelCatalogSnapshotPort,
    private val gateway: ModelProbeGateway? = null,
    private val budget: ModelProbeBudget = ModelProbeBudget(),
    private val elapsedClock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Explicit metadata-only probe. Free of generation calls and billing receipts. */
    suspend fun probeMetadata(endpoint: String, key: String = "",
        savedCredential: ModelConfigurationRef? = null,
        protocol: NativeModelProtocol = NativeModelProtocol.OPENAI_CHAT_COMPLETIONS): ModelProbeResult {
        val started = elapsedClock()
        return try {
            val snapshot = withTimeout(budget.timeoutMillis) {
                catalog.snapshot(endpoint, key, savedCredential, protocol)
            }
            ModelProbeResult.MetadataSuccess(snapshot, elapsed(started))
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            ModelProbeResult.Failure(ModelProbeFailure.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AgentModelFailure) {
            ModelProbeResult.Failure(ModelProbeFailure.of(failure.kind), failure.retryAfterMillis)
        } catch (_: InvalidModelCatalog) {
            ModelProbeResult.Failure(ModelProbeFailure.STORAGE)
        } catch (_: InvalidModelSettings) {
            ModelProbeResult.Failure(ModelProbeFailure.CONFIGURATION)
        } catch (_: InvalidModelNetworkSettings) {
            ModelProbeResult.Failure(ModelProbeFailure.CONFIGURATION)
        } catch (_: ModelNetworkSettingsUnavailable) {
            ModelProbeResult.Failure(ModelProbeFailure.CONFIGURATION)
        } catch (_: RuntimeUnavailable) {
            ModelProbeResult.Failure(ModelProbeFailure.CONFIGURATION)
        } catch (_: Exception) {
            ModelProbeResult.Failure(ModelProbeFailure.UNKNOWN)
        }
    }

    /**
     * Explicit minimal generation probe. [gateway] is required; without one this returns
     * [ModelProbeFailure.CONFIGURATION] without doing any I/O. Exactly one gateway call is
     * made on success or classified failure, and result text is bounded by the budget.
     */
    suspend fun probeGeneration(configuration: ModelConfigurationRef): ModelProbeResult {
        val active = gateway ?: return ModelProbeResult.Failure(ModelProbeFailure.CONFIGURATION)
        val started = elapsedClock()
        return try {
            val completion = withTimeout(budget.timeoutMillis) {
                withContext(Dispatchers.IO) {
                    active.minimalCompletion(configuration, MINIMAL_PROMPT, budget.maxOutputTokens)
                }
            }
            val preview = completion.text.take(budget.maxPreviewChars)
            ModelProbeResult.GenerationSuccess(
                preview = preview,
                truncated = preview.length < completion.text.length,
                elapsedMillis = elapsed(started),
                usage = completion.usage,
            )
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            ModelProbeResult.Failure(ModelProbeFailure.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AgentModelFailure) {
            ModelProbeResult.Failure(ModelProbeFailure.of(failure.kind), failure.retryAfterMillis)
        } catch (_: InvalidModelSettings) {
            ModelProbeResult.Failure(ModelProbeFailure.CONFIGURATION)
        } catch (_: InvalidModelNetworkSettings) {
            ModelProbeResult.Failure(ModelProbeFailure.CONFIGURATION)
        } catch (_: ModelNetworkSettingsUnavailable) {
            ModelProbeResult.Failure(ModelProbeFailure.CONFIGURATION)
        } catch (_: RuntimeUnavailable) {
            ModelProbeResult.Failure(ModelProbeFailure.CONFIGURATION)
        } catch (_: Exception) {
            ModelProbeResult.Failure(ModelProbeFailure.UNKNOWN)
        }
    }

    private fun elapsed(started: Long): Long = (elapsedClock() - started).coerceAtLeast(0)

    companion object {
        /** Fixed connectivity prompt; callers never inject request content into a probe. */
        const val MINIMAL_PROMPT = "Reply with OK."
    }
}
