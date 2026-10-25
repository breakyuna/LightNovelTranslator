package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.InvalidModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsUnavailable
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelEndpoint
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettings
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProfile
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettingsValidation
import java.util.Collections
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Non-streaming HTTP adapter for [ModelProtocols]. It owns no client: all calls
 * use the shared [CompatibleHttpTransport] so cancellation, redirect policy,
 * timeout and the 2 MiB response bound stay in one place.
 *
 * Streaming is intentionally not implemented in this slice. Every codec sends
 * `stream = false`; a future streaming adapter must keep the same capability
 * validation and secret-redaction rules. Vision requirements are rejected here
 * because these codecs have no image part; catalog metadata alone cannot authorize
 * a lossy dispatch.
 *
 * Authentication is header-only and protocol-specific:
 * - OpenAI Chat/Responses and SakuraLLM: `Authorization: Bearer ...`
 * - Anthropic Messages: `x-api-key` plus `anthropic-version`
 * - Google Gemini: `x-goog-api-key`
 *
 * An empty credential means explicit no-auth mode; no empty header is sent.
 * The default [CapabilityCatalog.EMPTY] intentionally rejects tool-bearing
 * requests as UNKNOWN; callers must provide exact model facts before enabling tools.
 * This class has no logger, and [ModelHttpPlan.toString] never prints headers or
 * request bodies.
 */
class ModelProtocolTransport(
    private val transport: CompatibleHttpTransport = CompatibleHttpTransport(),
    private val capabilities: CapabilityCatalog = CapabilityCatalog.EMPTY,
) {
    /**
     * Builds the request before dispatch only after capability validation. The
     * caller is still responsible for the shared scheduler and runtime gate.
     */
    suspend fun complete(
        protocol: ModelProtocol,
        settings: NativeModelSettings,
        credential: String,
        request: ModelProtocolRequest,
        requirements: CapabilityRequirement = CapabilityRequirement.NONE,
        profile: NativeModelProfile? = null,
    ): ModelProtocolResponse {
        NativeModelSettingsValidation.validateKey(credential)
        val effective = if (request.tools.isEmpty()) requirements else requirements.copy(tools = true)
        // This slice carries text/tool JSON only. A vision requirement cannot be
        // satisfied by silently dropping images, even if a catalog says SUPPORTED.
        if (effective.vision) {
            throw UnsupportedModelCapability(CapabilityFeature.VISION, CapabilityRejectionReason.UNSUPPORTED)
        }
        (profile?.capabilityCatalog(protocol) ?: capabilities)
            .requireSupported(protocol, settings.model, effective)
        val plan = plan(protocol, settings.endpoint, settings.model, credential, request, profile)
        val http = try {
            Request.Builder().url(plan.url).apply {
                plan.headers.forEach { (name, value) -> header(name, value) }
                profile?.extraHeaders?.forEach { (name, value) -> header(name, value) }
            }.post(plan.body.toRequestBody(JSON_MEDIA_TYPE)).build()
        } catch (_: IllegalArgumentException) {
            throw AgentModelFailure(ModelFailureKind.CONTENT)
        }
        val payload = try {
            transport.execute(http)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (local: InvalidModelNetworkSettings) {
            throw local
        } catch (local: ModelNetworkSettingsUnavailable) {
            throw local
        } catch (classified: AgentModelFailure) {
            throw classified
        } catch (_: Exception) {
            throw AgentModelFailure(ModelFailureKind.NETWORK)
        }
        return ModelProtocols.response(protocol, payload)
    }

    /** Endpoint construction follows the LinguaGacha/Pi base-URL rules used by the existing catalog. */
    fun endpointFor(protocol: ModelProtocol, endpoint: String, model: String): String = when (protocol) {
        ModelProtocol.OPENAI_CHAT_COMPLETIONS, ModelProtocol.SAKURA_LLM -> {
            val base = stripOperation(NativeModelEndpoint.base(endpoint), "/chat/completions")
            stripOperation(base, "/responses") + "/chat/completions"
        }
        ModelProtocol.OPENAI_RESPONSES -> {
            val base = stripOperation(NativeModelEndpoint.base(endpoint), "/responses")
            stripOperation(base, "/chat/completions") + "/responses"
        }
        ModelProtocol.ANTHROPIC_MESSAGES ->
            stripOperation(NativeModelEndpoint.base(endpoint), "/v1/messages") + "/v1/messages"
        ModelProtocol.GOOGLE_GEMINI -> googleBase(endpoint) + "/models/" + geminiModelPath(model) + ":generateContent"
    }

    internal fun plan(
        protocol: ModelProtocol,
        endpoint: String,
        model: String,
        credential: String,
        request: ModelProtocolRequest,
        profile: NativeModelProfile? = null,
    ): ModelHttpPlan {
        if (request.model != model) throw AgentModelFailure(ModelFailureKind.CONTENT)
        NativeModelSettingsValidation.validateKey(credential)
        val body = applyProfileRequestOverrides(ModelProtocols.request(protocol, request), profile, protocol)
        return ModelHttpPlan(endpointFor(protocol, endpoint, model), headers(protocol, credential), body)
    }

    private fun headers(protocol: ModelProtocol, credential: String): Map<String, String> {
        val headers = linkedMapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json; charset=utf-8",
        )
        if (protocol == ModelProtocol.ANTHROPIC_MESSAGES) headers["anthropic-version"] = ANTHROPIC_VERSION
        if (credential.isEmpty()) return Collections.unmodifiableMap(headers)
        when (protocol) {
            ModelProtocol.OPENAI_CHAT_COMPLETIONS, ModelProtocol.OPENAI_RESPONSES, ModelProtocol.SAKURA_LLM ->
                headers["Authorization"] = "Bearer $credential"
            ModelProtocol.ANTHROPIC_MESSAGES -> headers["x-api-key"] = credential
            ModelProtocol.GOOGLE_GEMINI -> headers["x-goog-api-key"] = credential
        }
        return Collections.unmodifiableMap(headers)
    }

    private fun googleBase(endpoint: String): String {
        val base = NativeModelEndpoint.base(endpoint)
        val path = java.net.URI(base).rawPath.orEmpty()
        return if (path.endsWith("/v1") || path.endsWith("/v1beta") || path.endsWith("/v1alpha")) base
        else "$base/v1beta"
    }

    private fun geminiModelPath(model: String): String {
        val value = model.removePrefix("models/")
        if (value.isEmpty() || value.length > 200 ||
            value.any { it != '.' && it != '_' && it != '-' && !it.isLetterOrDigit() }) {
            throw AgentModelFailure(ModelFailureKind.CONTENT)
        }
        return value
    }

    private fun stripOperation(base: String, suffix: String): String {
        val path = java.net.URI(base).rawPath.orEmpty()
        return if (path.endsWith(suffix)) base.dropLast(suffix.length) else base
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

/** Pure request plan. Body and header values are intentionally omitted from [toString]. */
internal class ModelHttpPlan(url: String, headers: Map<String, String>, val body: String) {
    val url: String = url
    val headers: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(headers))

    override fun toString(): String =
        "ModelHttpPlan(url=$url, headers=${headers.keys.sorted()}, body=redacted)"
}
