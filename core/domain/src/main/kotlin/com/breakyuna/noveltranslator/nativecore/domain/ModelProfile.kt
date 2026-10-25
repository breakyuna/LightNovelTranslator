package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** What a saved model profile is allowed to serve. A BOTH profile owns one immutable revision. */
enum class ModelProfilePurpose {
    TRANSLATION,
    AGENT,
    BOTH;

    fun serves(purpose: ModelProfilePurpose): Boolean =
        this == BOTH || purpose == BOTH || this == purpose
}

/** LG presets are copied into a custom profile before editing; only custom rows are mutable. */
enum class ModelProfileKind { PRESET, CUSTOM }

/**
 * Per-profile scheduling limits. The application-wide limits remain the default
 * for profiles that do not override them, so an existing installation keeps its
 * measured concurrency after the migration.
 */
data class ModelProfileLimits(val concurrency: Int = 4, val requestsPerMinute: Int = 0) {
    init {
        require(concurrency in 1..16)
        require(requestsPerMinute in 0..60_000)
    }

    companion object {
        val DEFAULT = ModelProfileLimits()
    }
}

/**
 * Tri-state capability facts owned by the profile. null means unknown, never an
 * implicit yes: the engine maps it to UNKNOWN and rejects required but unproven
 * tools/vision/reasoning before dispatch.
 */
data class ModelProfileCapabilities(
    val tools: Boolean? = null,
    val vision: Boolean? = null,
    val reasoning: Boolean? = null,
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
) {
    init {
        require(contextWindowTokens == null || contextWindowTokens in 1..10_000_000)
        require(maxOutputTokens == null || maxOutputTokens in 1..10_000_000)
        require(contextWindowTokens == null || maxOutputTokens == null || maxOutputTokens <= contextWindowTokens)
    }

    companion object {
        val UNKNOWN = ModelProfileCapabilities()
    }
}

/** Generation fields that are safe to persist and replay without provider-specific code. */
data class ModelGenerationSettings(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxOutputTokens: Int? = null,
    val reasoningEffort: String? = null,
    val serviceTier: String? = null,
) {
    init {
        require(temperature == null || temperature in 0.0..2.0)
        require(topP == null || topP in 0.0..1.0)
        require(maxOutputTokens == null || maxOutputTokens in 1..10_000_000)
        require(reasoningEffort == null || reasoningEffort.isSafeToken(64))
        require(serviceTier == null || serviceTier.isSafeToken(64))
    }

    companion object {
        val DEFAULT = ModelGenerationSettings()
    }
}

private fun String?.isSafeToken(max: Int): Boolean =
    this != null && isNotBlank() && length <= max && none(Char::isISOControl)

/**
 * One saved model configuration plus its non-secret management metadata. Credentials
 * remain encrypted in the settings store and are never part of this value.
 */
class NativeModelProfile(
    val id: String,
    val name: String,
    val kind: ModelProfileKind,
    val purpose: ModelProfilePurpose,
    val group: String,
    val order: Int,
    val enabled: Boolean,
    val settings: NativeModelSettings,
    val limits: ModelProfileLimits? = null,
    val capabilities: ModelProfileCapabilities = ModelProfileCapabilities.UNKNOWN,
    val generation: ModelGenerationSettings = ModelGenerationSettings.DEFAULT,
    extraHeaders: Map<String, String> = emptyMap(),
    val extraBody: String? = null,
    selectedFor: Set<ModelProfilePurpose> = emptySet(),
) {
    val reference: ModelConfigurationRef get() = settings.reference
    val selectedFor: Set<ModelProfilePurpose> =
        java.util.Collections.unmodifiableSet(LinkedHashSet(selectedFor))
    val extraHeaders: Map<String, String> =
        java.util.Collections.unmodifiableMap(LinkedHashMap(extraHeaders))

    init {
        NativeModelProfileValidation.validateId(id)
        NativeModelProfileValidation.validateName(name)
        require(group.length <= 80 && group.none(Char::isISOControl))
        require(order >= 0)
        require(this.selectedFor.all { it in PURPOSES_FOR_SELECTION && purpose.serves(it) }) {
            "A profile can only be selected for a purpose it serves"
        }
        NativeModelProfileValidation.validateHeaders(extraHeaders)
        NativeModelProfileValidation.validateExtraBody(extraBody)
    }

    fun serves(candidate: ModelProfilePurpose): Boolean = purpose.serves(candidate)

    companion object {
        val PURPOSES_FOR_SELECTION = setOf(ModelProfilePurpose.TRANSLATION, ModelProfilePurpose.AGENT)
    }
}

/** Validated input for a new profile. [keys] are submitted once and never stored here. */
data class NewModelProfile(
    val name: String,
    val kind: ModelProfileKind = ModelProfileKind.CUSTOM,
    val purpose: ModelProfilePurpose,
    val group: String = "",
    val enabled: Boolean = true,
    val endpoint: String,
    val model: String,
    val protocol: NativeModelProtocol,
    val keys: List<String>,
    val limits: ModelProfileLimits? = null,
    val capabilities: ModelProfileCapabilities = ModelProfileCapabilities.UNKNOWN,
    val generation: ModelGenerationSettings = ModelGenerationSettings.DEFAULT,
    val extraHeaders: Map<String, String> = emptyMap(),
    val extraBody: String? = null,
)

/** Metadata-only edit. Configuration and credentials are updated by [NativeModelProfilePort.saveConfiguration]. */
data class ModelProfileEdit(
    val name: String,
    val kind: ModelProfileKind,
    val purpose: ModelProfilePurpose,
    val group: String,
    val enabled: Boolean,
    val limits: ModelProfileLimits? = null,
    val capabilities: ModelProfileCapabilities = ModelProfileCapabilities.UNKNOWN,
    val generation: ModelGenerationSettings = ModelGenerationSettings.DEFAULT,
    val extraHeaders: Map<String, String> = emptyMap(),
    val extraBody: String? = null,
)

/** Credential-free key status for the profile editor. */
data class ModelProfileKeyState(
    val id: String,
    val enabled: Boolean,
    val failures: Int,
    val cooldownUntilMillis: Long,
    val lastFailure: String?,
) {
    init {
        require(id.isNotBlank())
        require(failures >= 0 && cooldownUntilMillis >= 0)
    }
}

/**
 * Profile catalog and credential rotation management. The settings port stays the
 * immutable-revision resolver used by both engines; this port only manages which
 * revisions exist and how they are presented.
 */
interface NativeModelProfilePort {
    suspend fun list(): List<NativeModelProfile> = emptyList()

    suspend fun selected(purpose: ModelProfilePurpose): NativeModelProfile? =
        list().firstOrNull { purpose in it.selectedFor && it.enabled }

    suspend fun find(id: String): NativeModelProfile? = list().firstOrNull { it.id == id }

    /** Resolves the immutable revision named by a task/session; null when the row was removed. */
    suspend fun resolveProfile(reference: ModelConfigurationRef): NativeModelProfile? =
        list().firstOrNull { it.reference == reference }

    suspend fun create(profile: NewModelProfile): NativeModelProfile =
        throw UnsupportedOperationException("Profile creation is not implemented")

    suspend fun edit(id: String, edit: ModelProfileEdit): NativeModelProfile =
        throw UnsupportedOperationException("Profile editing is not implemented")

    /** null keys reuse the current credentials only when the endpoint base is unchanged. */
    suspend fun saveConfiguration(id: String, endpoint: String, model: String,
        protocol: NativeModelProtocol, keys: List<String>?): NativeModelProfile =
        throw UnsupportedOperationException("Profile configuration is not implemented")

    suspend fun copy(id: String, name: String): NativeModelProfile =
        throw UnsupportedOperationException("Profile copy is not implemented")

    suspend fun delete(id: String) = throw UnsupportedOperationException("Profile deletion is not implemented")

    /** Moves one position inside the same group; the store keeps a stable total order. */
    suspend fun move(id: String, offset: Int): List<NativeModelProfile> =
        throw UnsupportedOperationException("Profile reordering is not implemented")

    suspend fun select(id: String, purpose: ModelProfilePurpose): NativeModelProfile =
        throw UnsupportedOperationException("Profile selection is not implemented")

    suspend fun keyStates(id: String): List<ModelProfileKeyState> = emptyList()

    suspend fun setKeyEnabled(id: String, keyId: String, enabled: Boolean): List<ModelProfileKeyState> =
        throw UnsupportedOperationException("Key state management is not implemented")
}

class InvalidModelProfile : IllegalArgumentException("Invalid model profile")

object NativeModelProfileValidation {
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}")
    private val SENSITIVE_HEADERS = setOf(
        "authorization", "proxy-authorization", "cookie", "set-cookie",
        "x-api-key", "api-key", "x-goog-api-key", "anthropic-api-key",
        "content-type", "content-length", "host", "accept-encoding",
        "connection", "transfer-encoding", "upgrade",
    )

    fun validateId(id: String) {
        if (!ID.matches(id)) throw InvalidModelProfile()
    }

    fun validateName(name: String) {
        if (name.isBlank() || name.length > 120 || name.any(Char::isISOControl)) throw InvalidModelProfile()
    }

    fun validateGroup(group: String) {
        if (group.length > 80 || group.any(Char::isISOControl)) throw InvalidModelProfile()
    }

    fun validateHeaders(headers: Map<String, String>) {
        if (headers.size > 32) throw InvalidModelProfile()
        headers.forEach { (name, value) ->
            val normalized = name.lowercase()
            if (!HEADER_NAME.matches(name) || normalized in SENSITIVE_HEADERS ||
                value.length > 2048 || value.any { it == '\r' || it == '\n' || it.isISOControl() }) {
                throw InvalidModelProfile()
            }
        }
    }

    fun validateExtraBody(body: String?) {
        if (body == null) return
        if (body.length > 32_768 || body.any(Char::isISOControl)) throw InvalidModelProfile()
        val parsed = try { Json.parseToJsonElement(body) } catch (_: Exception) { throw InvalidModelProfile() }
        if (parsed !is JsonObject) throw InvalidModelProfile()
    }

    /** LG's shipped idea of a usable profile is a named, enabled row with a base endpoint. */
    fun validate(profile: NewModelProfile) {
        validateName(profile.name)
        validateGroup(profile.group)
        NativeModelSettingsValidation.validateProfile(profile.endpoint, profile.model, profile.keys)
        validateHeaders(profile.extraHeaders)
        validateExtraBody(profile.extraBody)
        if (profile.keys.distinct().size != profile.keys.size) throw InvalidModelProfile()
    }

    fun validate(edit: ModelProfileEdit) {
        validateName(edit.name)
        validateGroup(edit.group)
        validateHeaders(edit.extraHeaders)
        validateExtraBody(edit.extraBody)
    }
}
