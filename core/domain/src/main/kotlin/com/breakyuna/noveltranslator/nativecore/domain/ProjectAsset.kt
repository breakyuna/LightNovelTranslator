package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Format identity of one fixed source asset. The names follow the LG item file types so an
 * imported asset can be routed to exactly one writer; they do not imply that the corresponding
 * importer/writer is already wired to the UI.
 */
enum class ProjectAssetFormat {
    TXT,
    MD_V2,
    SRT,
    VTT,
    LRC,
    ASS,
    SSA,
    EPUB,
    XLSX,
    WOLFXLSX,
    RENPY,
    TRANS,
    RPGMAKER_MV_MZ,
    RPGMAKER_RGSS,
    KVJSON,
    MESSAGEJSON,
    PDF,
    BINARY;

    val textual: Boolean
        get() = this in setOf(TXT, MD_V2, SRT, VTT, LRC, ASS, SSA, RENPY, KVJSON, MESSAGEJSON, TRANS)

    companion object {
        /** Extension-only detection; content sniffing belongs to the format adapters. */
        fun detect(path: String): ProjectAssetFormat {
            val name = path.substringAfterLast('/').lowercase()
            val extension = name.substringAfterLast('.', "")
            return when (extension) {
                "txt" -> TXT
                "md", "markdown" -> MD_V2
                "srt" -> SRT
                "vtt" -> VTT
                "lrc" -> LRC
                "ass" -> ASS
                "ssa" -> SSA
                "epub" -> EPUB
                "xlsx" -> XLSX
                "renpy", "rpy" -> RENPY
                "trans" -> TRANS
                "pdf" -> PDF
                "json" -> if (name.contains("message")) MESSAGEJSON else KVJSON
                else -> BINARY
            }
        }
    }
}

/** Bounded summary of one source asset; bodies are never part of UI state or logs. */
data class ProjectAssetSummary(
    val id: Long,
    val path: RelativePath,
    val format: ProjectAssetFormat,
    val ordinal: Int,
    val enabled: Boolean,
    val originalSize: Long,
    val sha256: String,
    val itemCount: Int,
) {
    init {
        require(id > 0 && ordinal >= 0 && originalSize >= 0 && itemCount >= 0)
        require(sha256.length == 64 && sha256.all { it in "0123456789abcdef" })
    }
}

/**
 * One prepared format unit. [row] keeps the format's source position (line or internal index),
 * while [slot] is the stable per-asset ordering used by storage. [extraField] is a bounded JSON
 * object owned by the format adapter.
 */
class AssetImportUnit(
    val slot: Int,
    val row: Int,
    val source: String,
    val translation: String = "",
    val status: TextItemStatus = TextItemStatus.NONE,
    val tag: String = "",
    nameSource: List<String>? = null,
    nameTranslation: List<String>? = null,
    val extraField: String = "{}",
    val skipInternalFilter: Boolean = false,
) {
    val nameSource: List<String>? = nameSource?.let { frozenList(it) }
    val nameTranslation: List<String>? = nameTranslation?.let { frozenList(it) }
    init {
        require(slot >= 0 && row >= 0)
        require(source.length <= 65_536 && translation.length <= 65_536)
        require(tag.length <= 4_096 && tag.none(Char::isISOControl))
        require(extraField.length <= 65_536)
        require('\u0000' !in source && '\u0000' !in translation && '\u0000' !in extraField)
        TextProjectFingerprint.utf8(source)
        TextProjectFingerprint.utf8(translation)
        TextProjectFingerprint.utf8(extraField)
        require(nameSource == null || this.nameSource!!.size in 1..32)
        require(nameTranslation == null || this.nameTranslation!!.size in 1..32)
        for (name in (this.nameSource ?: emptyList()) + (this.nameTranslation ?: emptyList())) {
            require(name.length <= 4_096 && '\u0000' !in name)
            TextProjectFingerprint.utf8(name)
        }
        JsonObjectValue.parse(extraField, JsonInputLimits(65_536, 32))
    }
    override fun toString(): String = "AssetImportUnit(redacted)"
}

/** Immutable prepared asset. Storage owns the bytes; adapters own the units and metadata. */
class AssetImportPlan(
    val commandId: CommandId,
    val ref: LocalProjectRef,
    val expectedRevision: Long,
    val path: RelativePath,
    val format: ProjectAssetFormat,
    source: ByteArray,
    units: List<AssetImportUnit>,
    val enabled: Boolean = true,
    val metadata: String = "{}",
) {
    val bytes: ByteArray = source.copyOf()
    val units: List<AssetImportUnit> = frozenList(units)
    init {
        require(expectedRevision >= 0)
        require(bytes.size <= MAX_ASSET_BYTES)
        require(this.units.size <= 100_000)
        require(this.units.map { it.slot }.toSet().size == this.units.size)
        require(this.units.zipWithNext().all { (left, right) -> left.slot < right.slot })
        require(metadata.length <= 65_536 && '\u0000' !in metadata)
        TextProjectFingerprint.utf8(metadata)
        JsonObjectValue.parse(metadata, JsonInputLimits(65_536, 32))
    }
    fun digest(): String = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(buildJsonObject {
        put("format", "asset-import-v1"); put("command", commandId.value)
        put("project", ref.projectId.value); put("instance", ref.instanceId.value)
        put("generation", ref.generation.value); put("revision", expectedRevision)
        put("path", path.value); put("format_name", format.name); put("enabled", enabled)
        put("sha256", TextProjectFingerprint.bytes(bytes)); put("metadata", metadata)
        put("units", units.size)
    }.toString()))

    companion object {
        const val MAX_ASSET_BYTES = 64 * 1024 * 1024
    }
}

enum class AssetMutationKind { IMPORT, REMOVE, REORDER, RESET, SETTINGS }
enum class AssetMutationStatus { COMMITTED, STALE_REVISION, UNKNOWN_ASSET, CONFLICT }

data class AssetMutationReceipt(
    val commandId: CommandId,
    val ref: LocalProjectRef,
    val kind: AssetMutationKind,
    val status: AssetMutationStatus,
    val revision: Long,
    val assetId: Long? = null,
    val committedAtMillis: Long,
)

/** Project-level settings mirrored from the application; never silently changes a running run. */
class ProjectSettings(
    val targetLanguage: String = "",
    val sourceLanguage: String = "auto",
    val bilingualDeduplicateSame: Boolean = true,
    val writeTranslatedNames: Boolean = false,
    val promptEnabled: Boolean = false,
) {
    init {
        require(targetLanguage.length <= 80 && targetLanguage.none(Char::isISOControl))
        require(sourceLanguage.length <= 80 && sourceLanguage.none(Char::isISOControl))
        TextProjectFingerprint.utf8(targetLanguage)
        TextProjectFingerprint.utf8(sourceLanguage)
    }
    private val fingerprintValue: ObjectFingerprint by lazy {
        ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(ProjectSettingsCodec.encode(this))))
    }
    fun fingerprint(): ObjectFingerprint = fingerprintValue
    override fun toString(): String = "ProjectSettings(redacted)"
}

object ProjectSettingsCodec {
    const val MAX_CHARS = 8_192
    fun encode(settings: ProjectSettings): String = buildJsonObject {
        put("format", "lnt-project-settings"); put("version", 1)
        put("target_language", settings.targetLanguage)
        put("source_language", settings.sourceLanguage)
        put("bilingual_deduplicate_same", settings.bilingualDeduplicateSame)
        put("write_translated_names", settings.writeTranslatedNames)
        put("prompt_enabled", settings.promptEnabled)
    }.toString()
    fun decode(text: String): ProjectSettings {
        val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_CHARS, 8)).json
        require(root.keys == setOf("format", "version", "target_language", "source_language",
            "bilingual_deduplicate_same", "write_translated_names", "prompt_enabled"))
        require(root.getValue("format") == JsonPrimitive("lnt-project-settings"))
        require(root.getValue("version") == JsonPrimitive(1))
        fun string(key: String): String {
            val value = root.getValue(key) as? JsonPrimitive ?: throw IllegalArgumentException("settings")
            require(value.isString); return value.content
        }
        fun boolean(key: String): Boolean {
            val value = root.getValue(key) as? JsonPrimitive ?: throw IllegalArgumentException("settings")
            require(!value.isString); return requireNotNull(value.booleanOrNull)
        }
        return ProjectSettings(string("target_language"), string("source_language"),
            boolean("bilingual_deduplicate_same"), boolean("write_translated_names"), boolean("prompt_enabled"))
    }
}

/** Immutable normalized extra data for one item. Defaults reproduce the pre-v3 TXT shape. */
class TextItemMetadata(
    val tag: String = "",
    nameSource: List<String>? = null,
    nameTranslation: List<String>? = null,
    val extraField: String = "{}",
    val skipInternalFilter: Boolean = false,
) {
    val nameSource: List<String>? = nameSource?.let { frozenList(it) }
    val nameTranslation: List<String>? = nameTranslation?.let { frozenList(it) }
    init {
        require(tag.length <= 4_096 && tag.none(Char::isISOControl))
        require(extraField.length <= 65_536)
        for (name in (this.nameSource ?: emptyList()) + (this.nameTranslation ?: emptyList())) {
            require(name.length <= 4_096 && '\u0000' !in name)
            TextProjectFingerprint.utf8(name)
        }
        TextProjectFingerprint.utf8(extraField)
        JsonObjectValue.parse(extraField, JsonInputLimits(65_536, 32))
    }
    val isDefault: Boolean get() = tag.isEmpty() && nameSource == null && nameTranslation == null &&
        extraField == "{}" && !skipInternalFilter
    override fun toString(): String = "TextItemMetadata(redacted)"
}

/** Asset-level commands share the global project revision and have durable receipts. */
object AssetCommandFingerprint {
    fun remove(ref: LocalProjectRef, revision: Long, assetId: Long, commandId: CommandId): String =
        hash("asset-remove-v1", ref, revision, commandId.value, assetId.toString())

    fun reorder(ref: LocalProjectRef, revision: Long, ids: List<Long>, commandId: CommandId): String =
        hash("asset-reorder-v1", ref, revision, commandId.value, ids.joinToString(","))

    fun reset(ref: LocalProjectRef, revision: Long, assetId: Long, commandId: CommandId): String =
        hash("asset-reset-v1", ref, revision, commandId.value, assetId.toString())

    private fun hash(kind: String, ref: LocalProjectRef, revision: Long, command: String, detail: String): String =
        TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(buildJsonObject {
            put("format", kind); put("project", ref.projectId.value); put("instance", ref.instanceId.value)
            put("generation", ref.generation.value); put("revision", revision); put("command", command)
            put("detail", detail)
        }.toString()))
}

/** Bounded multi-asset lifecycle port. All writes share ProjectWriteStore's revision timeline. */
interface ProjectAssetPort {
    suspend fun assets(ref: LocalProjectRef): List<ProjectAssetSummary>
    suspend fun projectSettings(ref: LocalProjectRef): ProjectSettings
    suspend fun editProjectSettings(commandId: CommandId, ref: LocalProjectRef, expectedRevision: Long,
        settings: ProjectSettings): AssetMutationReceipt
    suspend fun importAsset(plan: AssetImportPlan): AssetMutationReceipt
    suspend fun removeAsset(commandId: CommandId, ref: LocalProjectRef, expectedRevision: Long,
        assetId: Long): AssetMutationReceipt
    suspend fun reorderAssets(commandId: CommandId, ref: LocalProjectRef, expectedRevision: Long,
        assetIds: List<Long>): AssetMutationReceipt
    suspend fun resetAsset(commandId: CommandId, ref: LocalProjectRef, expectedRevision: Long,
        assetId: Long): AssetMutationReceipt
    suspend fun readAsset(ref: LocalProjectRef, assetId: Long): ByteArray
    suspend fun exportAsset(ref: LocalProjectRef, assetId: Long, destination: () -> java.io.OutputStream)
}

/** Deep, read-only JSON helper shared by format metadata; rejects non-object roots. */
internal fun frozenJsonObject(text: String): JsonObject =
    JsonObjectValue.parse(text, JsonInputLimits(65_536, 32)).json
