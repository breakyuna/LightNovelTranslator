package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** GET-only metadata. Never generates text, tests a completion, or follows a redirect with a credential. */
class CompatibleModelCatalog(private val settings: NativeModelSettingsPort, private val runtime: RuntimePort,
    private val http: CompatibleHttpTransport = CompatibleHttpTransport()) : NativeModelCatalogPort {
    override suspend fun models(endpoint: String, key: String, savedCredential: ModelConfigurationRef?): List<String> =
        models(endpoint, key, savedCredential, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS)

    override suspend fun models(endpoint: String, key: String, savedCredential: ModelConfigurationRef?,
        protocol: NativeModelProtocol): List<String> = withContext(Dispatchers.IO) {
        val base = NativeModelEndpoint.normalize(endpoint)
        NativeModelSettingsValidation.validateKey(key)
        currentCoroutineContext().ensureActive()
        val reservation = runtime.beginAuthenticationWrite()
        try {
            val credential = if (savedCredential == null) key else {
                if (key.isNotEmpty()) throw InvalidModelSettings()
                val configured = settings.resolve(savedCredential)
                if (NativeModelEndpoint.base(configured.endpoint) != base) throw InvalidModelSettings()
                settings.credential(savedCredential)
            }
            currentCoroutineContext().ensureActive()
            val response = withTimeout(20_000) {
                http.execute(request(protocol, base, credential).get().build())
            }
            if (protocol == NativeModelProtocol.GOOGLE_GEMINI) parseGemini(response) else parse(response)
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw AgentModelFailure(ModelFailureKind.TIMEOUT)
        } finally { runtime.endAuthenticationWrite(reservation) }
    }

    private fun request(protocol: NativeModelProtocol, base: String, credential: String): okhttp3.Request.Builder {
        val url = when (protocol) {
            NativeModelProtocol.ANTHROPIC_MESSAGES -> stripVersion(base, "/v1") + "/v1/models"
            NativeModelProtocol.GOOGLE_GEMINI -> googleBase(base) + "/models"
            else -> base + "/models"
        }
        return when (protocol) {
            NativeModelProtocol.ANTHROPIC_MESSAGES -> http.request(url, "").apply {
                if (credential.isNotEmpty()) header("x-api-key", credential)
                header("anthropic-version", ANTHROPIC_VERSION)
            }
            NativeModelProtocol.GOOGLE_GEMINI -> http.request(url, "").apply {
                if (credential.isNotEmpty()) header("x-goog-api-key", credential)
            }
            else -> http.request(url, credential)
        }
    }

    internal fun parse(body: String): List<String> {
        try {
            val root = JsonObjectValue.parse(body, JsonInputLimits(2 * 1024 * 1024, 16)).json
            val entries = root["data"] as? JsonArray ?: throw AgentModelFailure(ModelFailureKind.PARSING)
            return identifiers(entries) { entry ->
                (entry as? JsonObject)?.get("id") as? JsonPrimitive
            }
        } catch (classified: AgentModelFailure) { throw classified }
        catch (_: Exception) { throw AgentModelFailure(ModelFailureKind.PARSING) }
    }

    internal fun parseGemini(body: String): List<String> {
        try {
            val root = JsonObjectValue.parse(body, JsonInputLimits(2 * 1024 * 1024, 16)).json
            val entries = root["models"] as? JsonArray ?: throw AgentModelFailure(ModelFailureKind.PARSING)
            return identifiers(entries) { entry ->
                val name = (entry as? JsonObject)?.get("name") as? JsonPrimitive
                if (name == null || !name.isString) null else JsonPrimitive(name.content.removePrefix("models/"))
            }
        } catch (classified: AgentModelFailure) { throw classified }
        catch (_: Exception) { throw AgentModelFailure(ModelFailureKind.PARSING) }
    }

    private fun identifiers(entries: JsonArray, value: (JsonElement) -> JsonPrimitive?): List<String> {
        if (entries.size > 1000) throw AgentModelFailure(ModelFailureKind.CONTENT)
        return entries.map { entry ->
            val id = value(entry)
            if (id == null || !id.isString || id.content.isBlank() || id.content.length > 200 || id.content.any(Char::isISOControl))
                throw AgentModelFailure(ModelFailureKind.PARSING)
            TextProjectFingerprint.utf8(id.content)
            id.content
        }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    private fun stripVersion(base: String, version: String): String {
        val path = java.net.URI(base).rawPath.orEmpty().trimEnd('/')
        return if (path.endsWith(version)) base.removeSuffix(version) else base
    }

    private fun googleBase(base: String): String {
        val path = java.net.URI(base).rawPath.orEmpty()
        return if (path.endsWith("/v1") || path.endsWith("/v1beta") || path.endsWith("/v1alpha")) base
        else "$base/v1beta"
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
