package com.breakyuna.noveltranslator.nativecore.domain

import java.util.Collections
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Corrupt, tampered or out-of-range catalog state. Never replaced by an empty snapshot. */
class InvalidModelCatalog : IllegalArgumentException("Invalid model catalog")

/** Where a snapshot came from. Only an explicit provider metadata request is represented today. */
enum class ModelCatalogSource { ENDPOINT }

private const val MAX_ENDPOINT_CHARS = 2048

private fun catalogLabel(value: String, maxChars: Int): Boolean =
    value.isNotEmpty() && value.length <= maxChars && value.none(Char::isISOControl)

/**
 * Explicitly published metadata for one model. A null field means the provider did
 * not publish that fact; it is never inferred from an id, family name or another
 * protocol's response shape. Optional lists are immutable and de-duplicated.
 */
class ModelDescriptor(
    val id: String,
    val displayName: String = id,
    val contextTokens: Int? = null,
    val outputTokens: Int? = null,
    modalities: List<String>? = null,
    generationMethods: List<String>? = null,
) {
    val modalities: List<String>? = modalities?.let { frozenList(it.distinct()) }
    val generationMethods: List<String>? = generationMethods?.let { frozenList(it.distinct()) }

    /** Gemini's `supportedGenerationMethods` verbatim, when the provider published it. */
    val supportedGenerationMethods: List<String>? get() = generationMethods

    init {
        require(id.isNotEmpty() && id.length <= MAX_ID_CHARS && id.none(Char::isISOControl)) { "Invalid model id" }
        require(displayName.isNotEmpty() && displayName.length <= MAX_DISPLAY_NAME_CHARS &&
            displayName.none(Char::isISOControl)) { "Invalid model display name" }
        require(contextTokens == null || contextTokens in 1..MAX_TOKENS) { "Invalid context token limit" }
        require(outputTokens == null || outputTokens in 1..MAX_TOKENS) { "Invalid output token limit" }
        this.modalities?.let { values ->
            require(values.isNotEmpty() && values.size <= MAX_MODALITIES) { "Invalid model modalities" }
            values.forEach { require(catalogLabel(it, MAX_MODALITY_CHARS)) { "Invalid model modality" } }
        }
        this.generationMethods?.let { values ->
            require(values.isNotEmpty() && values.size <= MAX_GENERATION_METHODS) {
                "Invalid supported generation methods"
            }
            values.forEach {
                require(catalogLabel(it, MAX_GENERATION_METHOD_CHARS)) { "Invalid generation method" }
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is ModelDescriptor &&
        id == other.id && displayName == other.displayName && contextTokens == other.contextTokens &&
        outputTokens == other.outputTokens && modalities == other.modalities &&
        generationMethods == other.generationMethods

    override fun hashCode(): Int = listOf(id, displayName, contextTokens, outputTokens, modalities,
        generationMethods).hashCode()

    override fun toString(): String =
        "ModelDescriptor(id=$id, displayName=$displayName, contextTokens=$contextTokens, " +
            "outputTokens=$outputTokens, modalities=$modalities, generationMethods=$generationMethods)"

    companion object {
        const val MAX_ID_CHARS = 200
        const val MAX_DISPLAY_NAME_CHARS = 300
        const val MAX_TOKENS = 100_000_000
        const val MAX_MODALITIES = 16
        const val MAX_MODALITY_CHARS = 64
        const val MAX_GENERATION_METHODS = 32
        const val MAX_GENERATION_METHOD_CHARS = 100
    }
}

/** Normalized endpoint plus protocol; the durable isolation key for one snapshot. */
data class ModelCatalogKey(val endpoint: String, val protocol: NativeModelProtocol) {
    init {
        require(endpoint.isNotEmpty() && endpoint.length <= MAX_ENDPOINT_CHARS)
        require(endpoint == NativeModelEndpoint.base(endpoint)) { "Endpoint must be normalized" }
    }
}

/**
 * Immutable successful catalog result for one endpoint and protocol. [fetchedAtMillis]
 * is the successful fetch time, including an explicit empty result (an empty override).
 * [fingerprint] is a canonical SHA-256 over exactly these facts, so a stale or tampered
 * file can be rejected instead of silently becoming current.
 */
class ModelCatalogSnapshot(
    val endpoint: String,
    val protocol: NativeModelProtocol,
    val fetchedAtMillis: Long,
    descriptors: List<ModelDescriptor> = emptyList(),
    val source: ModelCatalogSource = ModelCatalogSource.ENDPOINT,
) {
    val descriptors: List<ModelDescriptor> = frozenList(descriptors)

    init {
        require(endpoint.isNotEmpty() && endpoint.length <= MAX_ENDPOINT_CHARS)
        require(endpoint == NativeModelEndpoint.base(endpoint)) { "Endpoint must be normalized" }
        require(fetchedAtMillis >= 0) { "Invalid fetch time" }
        require(this.descriptors.size <= ModelCatalogCodec.MAX_MODELS) { "Too many models" }
        require(this.descriptors.map { it.id }.toSet().size == this.descriptors.size) { "Duplicate model ids" }
    }

    val key: ModelCatalogKey get() = ModelCatalogKey(endpoint, protocol)

    /** Short alias for [fetchedAtMillis]; both names describe the same successful fetch time. */
    val fetchedAt: Long get() = fetchedAtMillis

    val fingerprint: String by lazy(LazyThreadSafetyMode.PUBLICATION) { ModelCatalogCodec.fingerprint(this) }

    override fun equals(other: Any?): Boolean = other is ModelCatalogSnapshot &&
        endpoint == other.endpoint && protocol == other.protocol &&
        fetchedAtMillis == other.fetchedAtMillis && descriptors == other.descriptors &&
        source == other.source

    override fun hashCode(): Int = listOf(endpoint, protocol, fetchedAtMillis, descriptors, source).hashCode()

    override fun toString(): String =
        "ModelCatalogSnapshot(endpoint=$endpoint, protocol=$protocol, models=${descriptors.size}, " +
            "source=$source, fetchedAtMillis=$fetchedAtMillis, fingerprint=$fingerprint)"
}

/**
 * Durable snapshot boundary. [save] must atomically replace the whole list for the
 * snapshot key, including an explicit empty list. A read failure must not be treated
 * as "no snapshot".
 */
interface ModelCatalogSnapshotStore {
    suspend fun load(endpoint: String, protocol: NativeModelProtocol): ModelCatalogSnapshot?
    suspend fun save(snapshot: ModelCatalogSnapshot): ModelCatalogSnapshot
}

/** Explicit metadata fetch boundary used by the probe; no generation is implied. */
fun interface ModelCatalogSnapshotPort {
    suspend fun snapshot(endpoint: String, key: String, savedCredential: ModelConfigurationRef?,
        protocol: NativeModelProtocol): ModelCatalogSnapshot
}

/**
 * Versioned, credential-free JSON for a set of snapshots. Decoding is strict: unknown
 * fields, duplicate keys, non-integral numbers, out-of-range values and fingerprint
 * mismatches all fail closed. An empty snapshot is a real entry, not an absent one.
 */
object ModelCatalogCodec {
    const val FORMAT = "lnt-native-model-catalog"
    const val FORMAT_VERSION = 1
    const val MAX_CHARS = 8 * 1024 * 1024
    const val MAX_MODELS = 1000
    const val MAX_SNAPSHOTS = 128

    private const val SNAPSHOT_FORMAT = "lnt-model-catalog-snapshot"
    private val ROOT_KEYS = setOf("format", "formatVersion", "snapshots")
    private val SNAPSHOT_KEYS = setOf(
        "endpoint", "protocol", "source", "fetchedAtMillis", "fingerprint", "descriptors",
    )
    private val DESCRIPTOR_REQUIRED = setOf("id", "displayName")
    private val DESCRIPTOR_OPTIONAL = setOf(
        "contextTokens", "outputTokens", "modalities", "generationMethods",
    )
    private val INTEGER = Regex("0|[1-9][0-9]*")
    private val FINGERPRINT = Regex("[0-9a-f]{64}")

    fun encode(snapshots: List<ModelCatalogSnapshot>): String {
        require(snapshots.size <= MAX_SNAPSHOTS) { "Too many model catalogs" }
        val keys = HashSet<ModelCatalogKey>()
        snapshots.forEach { require(keys.add(it.key)) { "Duplicate model catalog key" } }
        val ordered = snapshots.sortedWith(SNAPSHOT_ORDER)
        val text = buildJsonObject {
            put("format", FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("snapshots", buildJsonArray { ordered.forEach { add(snapshotJson(it)) } })
        }.toString()
        if (text.length > MAX_CHARS) throw InvalidModelCatalog()
        return text
    }

    fun decode(text: String): List<ModelCatalogSnapshot> {
        try {
            val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_CHARS, 16)).json
            require(root.keys == ROOT_KEYS)
            require(string(root, "format") == FORMAT)
            require(integer(root, "formatVersion") == FORMAT_VERSION.toLong())
            val entries = root["snapshots"] as? JsonArray ?: throw InvalidModelCatalog()
            require(entries.size <= MAX_SNAPSHOTS)
            val keys = HashSet<ModelCatalogKey>()
            val decoded = entries.map { element ->
                val snapshot = decodeSnapshot(element as? JsonObject ?: throw InvalidModelCatalog())
                require(keys.add(snapshot.key)) { "Duplicate model catalog key" }
                snapshot
            }
            return Collections.unmodifiableList(ArrayList(decoded.sortedWith(SNAPSHOT_ORDER)))
        } catch (invalid: InvalidModelCatalog) {
            throw invalid
        } catch (_: Exception) {
            throw InvalidModelCatalog()
        }
    }

    /** Canonical SHA-256 over every snapshot fact except the fingerprint itself. */
    fun fingerprint(snapshot: ModelCatalogSnapshot): String =
        TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(buildJsonObject {
            put("format", SNAPSHOT_FORMAT)
            put("formatVersion", 1)
            put("endpoint", snapshot.endpoint)
            put("protocol", snapshot.protocol.name)
            put("source", snapshot.source.name)
            put("fetchedAtMillis", snapshot.fetchedAtMillis)
            put("descriptors", buildJsonArray { snapshot.descriptors.forEach { add(descriptorJson(it)) } })
        }.toString()))

    private fun decodeSnapshot(value: JsonObject): ModelCatalogSnapshot {
        require(value.keys == SNAPSHOT_KEYS)
        val endpoint = string(value, "endpoint")
        val protocol = NativeModelProtocol.entries.firstOrNull { it.name == string(value, "protocol") }
            ?: throw InvalidModelCatalog()
        val source = ModelCatalogSource.entries.firstOrNull { it.name == string(value, "source") }
            ?: throw InvalidModelCatalog()
        val fetchedAtMillis = integer(value, "fetchedAtMillis")
        val storedFingerprint = string(value, "fingerprint")
        require(FINGERPRINT.matches(storedFingerprint))
        val entries = value["descriptors"] as? JsonArray ?: throw InvalidModelCatalog()
        require(entries.size <= MAX_MODELS)
        val descriptors = entries.map { element ->
            decodeDescriptor(element as? JsonObject ?: throw InvalidModelCatalog())
        }
        val snapshot = ModelCatalogSnapshot(endpoint, protocol, fetchedAtMillis, descriptors, source)
        require(snapshot.fingerprint == storedFingerprint) { "Model catalog fingerprint mismatch" }
        return snapshot
    }

    private fun decodeDescriptor(value: JsonObject): ModelDescriptor {
        require(value.keys.containsAll(DESCRIPTOR_REQUIRED))
        require(value.keys.all { it in DESCRIPTOR_REQUIRED || it in DESCRIPTOR_OPTIONAL })
        return ModelDescriptor(
            id = string(value, "id"),
            displayName = string(value, "displayName"),
            contextTokens = optionalInteger(value, "contextTokens"),
            outputTokens = optionalInteger(value, "outputTokens"),
            modalities = optionalStrings(value, "modalities",
                ModelDescriptor.MAX_MODALITIES, ModelDescriptor.MAX_MODALITY_CHARS),
            generationMethods = optionalStrings(value, "generationMethods",
                ModelDescriptor.MAX_GENERATION_METHODS, ModelDescriptor.MAX_GENERATION_METHOD_CHARS),
        )
    }

    private fun snapshotJson(snapshot: ModelCatalogSnapshot) = buildJsonObject {
        put("endpoint", snapshot.endpoint)
        put("protocol", snapshot.protocol.name)
        put("source", snapshot.source.name)
        put("fetchedAtMillis", snapshot.fetchedAtMillis)
        put("fingerprint", snapshot.fingerprint)
        put("descriptors", buildJsonArray { snapshot.descriptors.forEach { add(descriptorJson(it)) } })
    }

    private fun descriptorJson(descriptor: ModelDescriptor) = buildJsonObject {
        put("id", descriptor.id)
        put("displayName", descriptor.displayName)
        descriptor.contextTokens?.let { put("contextTokens", it) }
        descriptor.outputTokens?.let { put("outputTokens", it) }
        descriptor.modalities?.let { values ->
            put("modalities", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
        }
        descriptor.generationMethods?.let { values ->
            put("generationMethods", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private fun optionalInteger(value: JsonObject, key: String): Int? {
        if (key !in value) return null
        val parsed = integer(value, key)
        require(parsed in 1L..ModelDescriptor.MAX_TOKENS.toLong())
        return parsed.toInt()
    }

    private fun optionalStrings(value: JsonObject, key: String, maxEntries: Int, maxChars: Int): List<String>? {
        if (key !in value) return null
        val entries = value[key] as? JsonArray ?: throw InvalidModelCatalog()
        require(entries.size in 1..maxEntries)
        return entries.map { element ->
            val primitive = element as? JsonPrimitive ?: throw InvalidModelCatalog()
            require(primitive.isString)
            require(catalogLabel(primitive.content, maxChars))
            primitive.content
        }
    }

    private fun string(value: JsonObject, key: String): String {
        val primitive = value[key] as? JsonPrimitive ?: throw InvalidModelCatalog()
        require(primitive.isString)
        return primitive.content
    }

    private fun integer(value: JsonObject, key: String): Long {
        val primitive = value[key] as? JsonPrimitive ?: throw InvalidModelCatalog()
        require(!primitive.isString && INTEGER.matches(primitive.content))
        return primitive.content.toLongOrNull() ?: throw InvalidModelCatalog()
    }

    private val SNAPSHOT_ORDER = compareBy<ModelCatalogSnapshot>({ it.endpoint }, { it.protocol.ordinal })
}
