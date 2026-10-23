package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** GET-only metadata. Never generates text, tests a completion, or follows a redirect with a credential. */
class CompatibleModelCatalog(private val settings: NativeModelSettingsPort, private val runtime: RuntimePort,
    private val http: CompatibleHttpTransport = CompatibleHttpTransport()) : NativeModelCatalogPort {
    override suspend fun models(endpoint: String, key: String, savedCredential: ModelConfigurationRef?): List<String> = withContext(Dispatchers.IO) {
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
            val response = withTimeout(20_000) { http.execute(http.request(NativeModelEndpoint.models(base), credential).get().build()) }
            parse(response)
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw AgentModelFailure(ModelFailureKind.TIMEOUT)
        } finally { runtime.endAuthenticationWrite(reservation) }
    }

    internal fun parse(body: String): List<String> {
        try {
            val root = JsonObjectValue.parse(body, JsonInputLimits(2 * 1024 * 1024, 16)).json
            val entries = root["data"] as? JsonArray ?: throw AgentModelFailure(ModelFailureKind.PARSING)
            if (entries.size > 1000) throw AgentModelFailure(ModelFailureKind.CONTENT)
            return entries.map { entry ->
                val id = (entry as? JsonObject)?.get("id") as? JsonPrimitive
                if (id == null || !id.isString || id.content.isBlank() || id.content.length > 200 || id.content.any(Char::isISOControl))
                    throw AgentModelFailure(ModelFailureKind.PARSING)
                TextProjectFingerprint.utf8(id.content)
                id.content
            }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        } catch (classified: AgentModelFailure) { throw classified }
        catch (_: Exception) { throw AgentModelFailure(ModelFailureKind.PARSING) }
    }
}
