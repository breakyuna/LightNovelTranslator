package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/**
 * GET-only metadata. Never generates text, tests a completion, or follows a redirect
 * with a credential. [models] keeps the historical id-only contract; [snapshot] adds
 * explicitly published metadata and only replaces a saved snapshot after a successful,
 * fully parsed response. An explicit empty list is still a successful override.
 */
class CompatibleModelCatalog(private val settings: NativeModelSettingsPort, private val runtime: RuntimePort,
    private val http: CompatibleHttpTransport = CompatibleHttpTransport(),
    /** Optional auth boundary; the default keeps the historical direct API-key read. */
    private val credentials: ModelCredentialResolver = ApiKeyModelCredentialResolver(ModelKeyPool(settings)),
    /** Optional durable snapshot override. Failure never writes to it. */
    private val snapshots: ModelCatalogSnapshotStore? = null,
    private val nowMillis: () -> Long = { System.currentTimeMillis() })
    : NativeModelCatalogPort, ModelCatalogSnapshotPort {
    override suspend fun models(endpoint: String, key: String, savedCredential: ModelConfigurationRef?): List<String> =
        models(endpoint, key, savedCredential, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS)

    override suspend fun models(endpoint: String, key: String, savedCredential: ModelConfigurationRef?,
        protocol: NativeModelProtocol): List<String> = withContext(Dispatchers.IO) {
        val fetched = fetch(endpoint, key, savedCredential, protocol)
        if (protocol == NativeModelProtocol.GOOGLE_GEMINI) parseGemini(fetched.body) else parse(fetched.body)
    }

    /** Fetch, validate and optionally persist one complete metadata snapshot. */
    override suspend fun snapshot(endpoint: String, key: String, savedCredential: ModelConfigurationRef?,
        protocol: NativeModelProtocol): ModelCatalogSnapshot = withContext(Dispatchers.IO) {
        val fetched = fetch(endpoint, key, savedCredential, protocol)
        val descriptors = if (protocol == NativeModelProtocol.GOOGLE_GEMINI) {
            parseGeminiDescriptors(fetched.body)
        } else {
            parseDescriptors(fetched.body)
        }
        val result = ModelCatalogSnapshot(
            endpoint = fetched.base,
            protocol = protocol,
            fetchedAtMillis = nowMillis().coerceAtLeast(0),
            descriptors = descriptors,
        )
        currentCoroutineContext().ensureActive()
        snapshots?.save(result)
        result
    }

    /** Convenience id/metadata view of [snapshot]; it does not fall back to a failed fetch. */
    suspend fun descriptors(endpoint: String, key: String, savedCredential: ModelConfigurationRef? = null,
        protocol: NativeModelProtocol = NativeModelProtocol.OPENAI_CHAT_COMPLETIONS): List<ModelDescriptor> =
        snapshot(endpoint, key, savedCredential, protocol).descriptors

    /** Last successful stored snapshot for this key, or null when none exists. Never fetches. */
    suspend fun cached(endpoint: String,
        protocol: NativeModelProtocol = NativeModelProtocol.OPENAI_CHAT_COMPLETIONS): ModelCatalogSnapshot? =
        withContext(Dispatchers.IO) {
            snapshots?.load(NativeModelEndpoint.base(endpoint), protocol)
        }

    private class Fetched(val base: String, val body: String)

    private suspend fun fetch(endpoint: String, key: String, savedCredential: ModelConfigurationRef?,
        protocol: NativeModelProtocol): Fetched {
        val base = NativeModelEndpoint.normalize(endpoint)
        NativeModelSettingsValidation.validateKey(key)
        currentCoroutineContext().ensureActive()
        val reservation = runtime.beginAuthenticationWrite()
        try {
            val authMode: ModelAuthMode
            val credential: String
            if (savedCredential == null) {
                authMode = ModelAuthMode.API_KEY
                credential = key
            } else {
                if (key.isNotEmpty()) throw InvalidModelSettings()
                val configured = settings.resolve(savedCredential)
                if (NativeModelEndpoint.base(configured.endpoint) != base) throw InvalidModelSettings()
                authMode = credentials.mode(savedCredential)
                credential = when (authMode) {
                    ModelAuthMode.OAUTH -> credentials.resolve(savedCredential).token
                    ModelAuthMode.API_KEY -> settings.credential(savedCredential)
                    ModelAuthMode.NONE -> ""
                }
            }
            currentCoroutineContext().ensureActive()
            val response = withTimeout(20_000) {
                http.execute(request(protocol, base, credential, authMode).get().build())
            }
            return Fetched(base, response)
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw AgentModelFailure(ModelFailureKind.TIMEOUT)
        } finally { runtime.endAuthenticationWrite(reservation) }
    }

    private fun request(protocol: NativeModelProtocol, base: String, credential: String,
        authMode: ModelAuthMode): okhttp3.Request.Builder {
        if (authMode == ModelAuthMode.OAUTH && credential.isEmpty()) {
            throw AgentModelFailure(ModelFailureKind.AUTHENTICATION)
        }
        val url = when (protocol) {
            NativeModelProtocol.ANTHROPIC_MESSAGES -> stripVersion(base, "/v1") + "/v1/models"
            NativeModelProtocol.GOOGLE_GEMINI -> googleBase(base) + "/models"
            else -> base + "/models"
        }
        val builder = when (authMode) {
            ModelAuthMode.NONE -> http.request(url, "", ModelAuthMode.NONE)
            ModelAuthMode.OAUTH -> http.request(url, credential, ModelAuthMode.OAUTH)
            ModelAuthMode.API_KEY -> when (protocol) {
                NativeModelProtocol.ANTHROPIC_MESSAGES -> http.request(url, "", ModelAuthMode.API_KEY).apply {
                    if (credential.isNotEmpty()) header("x-api-key", credential)
                }
                NativeModelProtocol.GOOGLE_GEMINI -> http.request(url, "", ModelAuthMode.API_KEY).apply {
                    if (credential.isNotEmpty()) header("x-goog-api-key", credential)
                }
                else -> http.request(url, credential, ModelAuthMode.API_KEY)
            }
        }
        if (protocol == NativeModelProtocol.ANTHROPIC_MESSAGES) {
            builder.header("anthropic-version", ANTHROPIC_VERSION)
        }
        return builder
    }

    internal fun parse(body: String): List<String> {
        try {
            val entries = rootArray(body, "data")
            return identifiers(entries) { entry ->
                (entry as? JsonObject)?.get("id") as? JsonPrimitive
            }
        } catch (classified: AgentModelFailure) { throw classified }
        catch (_: Exception) { throw AgentModelFailure(ModelFailureKind.PARSING) }
    }

    internal fun parseGemini(body: String): List<String> {
        try {
            val entries = rootArray(body, "models")
            return identifiers(entries) { entry ->
                val name = (entry as? JsonObject)?.get("name") as? JsonPrimitive
                if (name == null || !name.isString) null else JsonPrimitive(name.content.removePrefix("models/"))
            }
        } catch (classified: AgentModelFailure) { throw classified }
        catch (_: Exception) { throw AgentModelFailure(ModelFailureKind.PARSING) }
    }

    /** OpenAI-shaped metadata: only an explicitly published id and display name are used. */
    internal fun parseDescriptors(body: String): List<ModelDescriptor> {
        try {
            val entries = rootArray(body, "data")
            if (entries.size > MAX_MODELS) throw AgentModelFailure(ModelFailureKind.CONTENT)
            val models = LinkedHashMap<String, ModelDescriptor>()
            for (entry in entries) {
                val value = entry as? JsonObject ?: throw AgentModelFailure(ModelFailureKind.PARSING)
                val id = identifier(value["id"] as? JsonPrimitive)
                val displayName = optionalLabel(value, "display_name")
                    ?: optionalLabel(value, "name")
                    ?: id
                models.putIfAbsent(id, ModelDescriptor(id, displayName))
            }
            return models.values.sortedWith(MODEL_ORDER)
        } catch (classified: AgentModelFailure) { throw classified }
        catch (_: Exception) { throw AgentModelFailure(ModelFailureKind.PARSING) }
    }

    /** Gemini publishes token limits and generation methods explicitly; absent fields stay null. */
    internal fun parseGeminiDescriptors(body: String): List<ModelDescriptor> {
        try {
            val entries = rootArray(body, "models")
            if (entries.size > MAX_MODELS) throw AgentModelFailure(ModelFailureKind.CONTENT)
            val models = LinkedHashMap<String, ModelDescriptor>()
            for (entry in entries) {
                val value = entry as? JsonObject ?: throw AgentModelFailure(ModelFailureKind.PARSING)
                val name = value["name"] as? JsonPrimitive
                if (name == null || !name.isString) throw AgentModelFailure(ModelFailureKind.PARSING)
                val id = identifierText(name.content.removePrefix("models/"))
                val displayName = optionalLabel(value, "displayName") ?: id
                val modalities = (
                    optionalLabels(value, "inputModalities", ModelDescriptor.MAX_MODALITIES, ModelDescriptor.MAX_MODALITY_CHARS)
                        .orEmpty() +
                        optionalLabels(value, "outputModalities", ModelDescriptor.MAX_MODALITIES, ModelDescriptor.MAX_MODALITY_CHARS)
                            .orEmpty()
                    ).distinct().takeIf { it.isNotEmpty() }
                val descriptor = try {
                    ModelDescriptor(
                        id = id,
                        displayName = displayName,
                        contextTokens = optionalTokenLimit(value, "inputTokenLimit"),
                        outputTokens = optionalTokenLimit(value, "outputTokenLimit"),
                        modalities = modalities?.takeIf { it.size <= ModelDescriptor.MAX_MODALITIES },
                        generationMethods = optionalLabels(value, "supportedGenerationMethods",
                            ModelDescriptor.MAX_GENERATION_METHODS, ModelDescriptor.MAX_GENERATION_METHOD_CHARS),
                    )
                } catch (_: IllegalArgumentException) {
                    throw AgentModelFailure(ModelFailureKind.PARSING)
                }
                models.putIfAbsent(id, descriptor)
            }
            return models.values.sortedWith(MODEL_ORDER)
        } catch (classified: AgentModelFailure) { throw classified }
        catch (_: Exception) { throw AgentModelFailure(ModelFailureKind.PARSING) }
    }

    private fun rootArray(body: String, key: String): JsonArray {
        val root = JsonObjectValue.parse(body, JsonInputLimits(2 * 1024 * 1024, 16)).json
        return root[key] as? JsonArray ?: throw AgentModelFailure(ModelFailureKind.PARSING)
    }

    private fun identifiers(entries: JsonArray, value: (JsonElement) -> JsonPrimitive?): List<String> {
        if (entries.size > MAX_MODELS) throw AgentModelFailure(ModelFailureKind.CONTENT)
        return entries.map { entry -> identifier(value(entry)) }.distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    private fun identifier(value: JsonPrimitive?): String {
        if (value == null || !value.isString) throw AgentModelFailure(ModelFailureKind.PARSING)
        return identifierText(value.content)
    }

    private fun identifierText(text: String): String {
        if (text.isBlank() || text.length > ModelDescriptor.MAX_ID_CHARS || text.any(Char::isISOControl))
            throw AgentModelFailure(ModelFailureKind.PARSING)
        TextProjectFingerprint.utf8(text)
        return text
    }

    private fun optionalLabel(value: JsonObject, key: String): String? {
        val primitive = value[key] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        val text = primitive.content
        if (text.isBlank() || text.length > ModelDescriptor.MAX_DISPLAY_NAME_CHARS ||
            text.any(Char::isISOControl)) return null
        return try { TextProjectFingerprint.utf8(text); text } catch (_: Exception) { null }
    }

    private fun optionalTokenLimit(value: JsonObject, key: String): Int? {
        val primitive = value[key] as? JsonPrimitive ?: return null
        if (primitive.isString || !INTEGER.matches(primitive.content)) return null
        val parsed = primitive.content.toLongOrNull() ?: return null
        return if (parsed in 1L..ModelDescriptor.MAX_TOKENS.toLong()) parsed.toInt() else null
    }

    private fun optionalLabels(value: JsonObject, key: String, maxEntries: Int, maxChars: Int): List<String>? {
        val entries = value[key] as? JsonArray ?: return null
        if (entries.size > maxEntries) return null
        val labels = entries.mapNotNull { element ->
            val primitive = element as? JsonPrimitive ?: return@mapNotNull null
            if (!primitive.isString) return@mapNotNull null
            val text = primitive.content
            if (text.isBlank() || text.length > maxChars || text.any(Char::isISOControl)) return@mapNotNull null
            try { TextProjectFingerprint.utf8(text); text } catch (_: Exception) { null }
        }.distinct()
        return labels.takeIf { it.isNotEmpty() }
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
        const val MAX_MODELS = 1000
        val INTEGER = Regex("0|[1-9][0-9]*")
        val MODEL_ORDER = compareBy<ModelDescriptor>(String.CASE_INSENSITIVE_ORDER) { it.id }
    }
}
