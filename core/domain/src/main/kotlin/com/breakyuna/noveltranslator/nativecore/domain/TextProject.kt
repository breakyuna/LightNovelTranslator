package com.breakyuna.noveltranslator.nativecore.domain

import java.io.OutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Native project format; LG files require a separate compatibility converter. */
object NativeProjectFormat {
    const val EXTENSION = "lnt"
    const val SCHEMA_VERSION = 1
    const val APPLICATION_ID = 0x4c4e5431 // LNT1, independent of the filename.
}

data class LocalProjectRef(
    val projectId: ProjectId,
    val instanceId: ProjectInstanceId,
    val generation: RecoveryGeneration,
)

/** Explicit safety limits for the first TXT adapter, not measured device budgets. */
data class TextProjectLimits(
    val maxSourceBytes: Int = 8 * 1024 * 1024,
    val maxItems: Int = 100_000,
    val maxItemChars: Int = 65_536,
    val maxPageSize: Int = 100,
) {
    init {
        require(maxSourceBytes in 1..16 * 1024 * 1024)
        require(maxItems in 1..100_000 && maxItemChars in 1..65_536)
        require(maxPageSize in 1..100)
    }
}

enum class TxtEncoding(val charsetName: String) {
    UTF8("UTF-8"), UTF16LE("UTF-16LE"), UTF16BE("UTF-16BE"),
    GB18030("GB18030"), SHIFT_JIS("Shift_JIS"),
}

enum class TextItemStatus { NONE, PROCESSED, EXCLUDED }

data class TextProjectSummary(
    val ref: LocalProjectRef,
    val name: String,
    val revision: Long,
    val assetPath: RelativePath,
    val itemCount: Int,
)

/**
 * One entry of the local instance index. [summary] is null and [failure] set when the private
 * project cannot currently be opened (missing directory, corrupt file, foreign format), so one
 * broken instance never hides the rest of the recent list. The failure is a classified reason,
 * not a raw IO exception; callers may retry or offer re-import/re-authorization.
 */
class RegisteredProject(
    val ref: LocalProjectRef,
    val summary: TextProjectSummary?,
    val failure: TextProjectException.Reason?,
) {
    init {
        require((summary == null) != (failure == null)) {
            "Exactly one of summary and failure is required"
        }
        require(summary == null || summary.ref == ref) {
            "Summary must belong to the registered instance"
        }
    }

    val readable: Boolean get() = summary != null
}

/** No data-class toString: item bodies must not accidentally enter diagnostics. */
class TextProjectItem(
    val id: ItemId,
    val row: Int,
    val source: String,
    val translation: String,
    val status: TextItemStatus,
    val fingerprint: ObjectFingerprint,
)

class TextItemPage(val revision: Long, items: List<TextProjectItem>) {
    val items: List<TextProjectItem> = java.util.Collections.unmodifiableList(ArrayList(items))
}

/** Manual UI intent, never a model/Agent authorization. All fields participate in deduplication. */
class ManualTextEdit(
    val commandId: CommandId,
    val ref: LocalProjectRef,
    val itemId: ItemId,
    val expectedRevision: Long,
    val baseFingerprint: ObjectFingerprint,
    val translation: String,
    val status: TextItemStatus = TextItemStatus.PROCESSED,
) {
    init { require(expectedRevision >= 0) }
}

enum class ManualEditStatus { COMMITTED, STALE_REVISION, STALE_FINGERPRINT, UNKNOWN_ITEM }

/** Rejections are durable too: retrying a settled command never changes its result. */
data class ManualEditReceipt(
    val commandId: CommandId,
    val ref: LocalProjectRef,
    val itemId: ItemId,
    val status: ManualEditStatus,
    val revision: Long,
    val committedAtMillis: Long,
)

class TextProjectException(val reason: Reason) : IllegalStateException("Text project: $reason") {
    enum class Reason {
        INVALID_TEXT, LIMIT_EXCEEDED, INVALID_FORMAT, UNSUPPORTED_VERSION,
        UNKNOWN_PROJECT, WRONG_IDENTITY, COMMAND_CONFLICT, STALE_PAGE, CORRUPT_PROJECT,

        /** Environment/storage fault (missing directory, unwritable target); project facts are unchanged. */
        STORAGE_IO,
    }
}

/** Versioned, length-safe encoding shared by stored object fingerprints and command receipts. */
object TextProjectFingerprint {
    fun bytes(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    fun item(id: ItemId, row: Int, source: String, translation: String, status: TextItemStatus): ObjectFingerprint =
        ObjectFingerprint(hash(buildJsonObject {
            put("version", 1)
            put("item", id.value)
            put("row", row)
            put("source", source)
            put("translation", translation)
            put("status", status.name)
        }.toString()))

    fun command(edit: ManualTextEdit): String = hash(buildJsonObject {
        put("version", 1)
        put("command", edit.commandId.value)
        put("project", edit.ref.projectId.value)
        put("instance", edit.ref.instanceId.value)
        put("generation", edit.ref.generation.value)
        put("item", edit.itemId.value)
        put("revision", edit.expectedRevision)
        put("base", edit.baseFingerprint.sha256)
        put("translation", edit.translation)
        put("status", edit.status.name)
    }.toString())

    /** Strict encoding rejects isolated surrogates rather than silently hashing replacement bytes. */
    fun utf8(value: String): ByteArray = try {
        val buffer = Charsets.UTF_8.newEncoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value))
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw TextProjectException(TextProjectException.Reason.INVALID_TEXT)
    }

    private fun hash(value: String): String = bytes(utf8(value))
}

/**
 * First native TXT storage slice. Methods do IO off the main thread. Output factories are
 * invoked on the IO dispatcher; the adapter closes each stream before reporting success.
 * Manual edits share one transaction owner; Agent writes still require the approval pipeline.
 */
interface TextProjectPort {
    /**
     * Discover persisted local instances after process restart; bounded, metadata-only and
     * per-entry tolerant: an unreadable instance is returned with [RegisteredProject.failure]
     * instead of failing the whole list.
     */
    suspend fun registeredProjects(limit: Int = 100): List<RegisteredProject>

    suspend fun createTxt(
        commandId: CommandId,
        name: String,
        path: RelativePath,
        source: ByteArray,
        encoding: TxtEncoding = TxtEncoding.UTF8,
    ): TextProjectSummary

    suspend fun open(ref: LocalProjectRef): TextProjectSummary
    suspend fun importProject(commandId: CommandId, source: () -> InputStream): TextProjectSummary
    suspend fun copyProject(commandId: CommandId, ref: LocalProjectRef): TextProjectSummary
    suspend fun recoverProjects(): ProjectRecoveryReport
    suspend fun cleanupTemporaryOutputs(): Int
    suspend fun item(ref: LocalProjectRef, itemId: ItemId): TextItemDetails?
    suspend fun items(ref: LocalProjectRef, revision: Long, afterId: Long = 0, limit: Int = 100): TextItemPage
    suspend fun edit(command: ManualTextEdit): ManualEditReceipt
    suspend fun receipt(ref: LocalProjectRef, commandId: CommandId): ManualEditReceipt?
    suspend fun saveProject(ref: LocalProjectRef, destination: () -> OutputStream)
    suspend fun exportTxt(ref: LocalProjectRef, destination: () -> OutputStream)
    suspend fun exportBilingual(ref: LocalProjectRef, deduplicateSame: Boolean = true, destination: () -> OutputStream)
    suspend fun exportOriginal(ref: LocalProjectRef, destination: () -> OutputStream)
}
