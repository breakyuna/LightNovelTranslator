package com.breakyuna.noveltranslator.nativecore.domain

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Product object families that can be prepared for approval and later committed. */
enum class ChangeTargetKind {
    ITEM,
    PDF_PAGE,
    ASSET,
    GLOSSARY,
    TEXT_PRESERVE,
    PRE_REPLACEMENT,
    POST_REPLACEMENT,
    CUSTOM_PROMPT,
}

enum class ChangeOperation { CREATE, UPDATE, DELETE, REORDER }

/** Stable object identity; a list position or translated text is never an identity. */
data class ChangeTarget(
    val kind: ChangeTargetKind,
    val id: String,
    val parentId: String? = null,
) {
    init {
        require(id.isNotBlank() && id.none { it.isISOControl() }) { "Invalid change target" }
        require(parentId == null || (parentId.isNotBlank() && parentId.none { it.isISOControl() })) {
            "Invalid change parent"
        }
    }
}

private fun requireSha256(value: String) {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid SHA-256" }
}

/** Canonical fingerprint of the object value a change was prepared against. */
@JvmInline
value class ObjectFingerprint(val sha256: String) {
    init { requireSha256(sha256) }
}

/** Canonical fingerprint of a whole prepared batch; approval binds to this value. */
@JvmInline
value class ChangeSetDigest(val sha256: String) {
    init { requireSha256(sha256) }
}

/**
 * A single prepared mutation. CREATE has no base revision; every other operation
 * must name the exact committed value it was computed from, so a stale batch is
 * rejected instead of overwriting newer work.
 */
data class PreparedChange(
    val target: ChangeTarget,
    val operation: ChangeOperation,
    val baseFingerprint: ObjectFingerprint?,
    val contentDigest: ObjectFingerprint,
) {
    init {
        when (operation) {
            ChangeOperation.CREATE -> require(baseFingerprint == null) {
                "A created object has no base revision"
            }
            ChangeOperation.UPDATE, ChangeOperation.DELETE, ChangeOperation.REORDER ->
                require(baseFingerprint != null) { "An existing object requires a base fingerprint" }
        }
    }
}

/** Counts shown to the user before approval; assets are a native addition to the LG summary. */
data class ChangeSummary(
    val pages: Int,
    val items: Int,
    val assets: Int,
    val glossary: Int,
    val textPreserve: Int,
    val preReplacement: Int,
    val postReplacement: Int,
    val prompts: Int,
) {
    val total: Int
        get() = pages + items + assets + glossary + textPreserve + preReplacement + postReplacement + prompts

    companion object {
        internal fun of(changes: List<PreparedChange>): ChangeSummary {
            var pages = 0
            var items = 0
            var assets = 0
            var glossary = 0
            var textPreserve = 0
            var preReplacement = 0
            var postReplacement = 0
            var prompts = 0
            changes.forEach { change ->
                when (change.target.kind) {
                    ChangeTargetKind.PDF_PAGE -> pages += 1
                    ChangeTargetKind.ITEM -> items += 1
                    ChangeTargetKind.ASSET -> assets += 1
                    ChangeTargetKind.GLOSSARY -> glossary += 1
                    ChangeTargetKind.TEXT_PRESERVE -> textPreserve += 1
                    ChangeTargetKind.PRE_REPLACEMENT -> preReplacement += 1
                    ChangeTargetKind.POST_REPLACEMENT -> postReplacement += 1
                    ChangeTargetKind.CUSTOM_PROMPT -> prompts += 1
                }
            }
            return ChangeSummary(
                pages, items, assets, glossary, textPreserve, preReplacement, postReplacement, prompts,
            )
        }
    }
}

class InvalidChangeSet(val reason: Reason) : IllegalArgumentException("Invalid prepared change set: $reason") {
    enum class Reason { EMPTY, DUPLICATE_TARGET, INVALID_UNICODE }
}

/**
 * Immutable batch owned by one Project/Chat/Run. The digest is computed from this
 * batch and must be re-checked by the write store before commit; editing later
 * without a new approval is not possible because the digest no longer matches.
 */
class PreparedChangeSet(
    val id: ChangeSetId,
    val projectId: ProjectId,
    val chatId: ChatId,
    val runId: RunId,
    changes: List<PreparedChange>,
) {
    val changes: List<PreparedChange> = frozenList(changes)

    init {
        requireValidUnicode(id.value)
        if (this.changes.isEmpty()) throw InvalidChangeSet(InvalidChangeSet.Reason.EMPTY)
        val seen = mutableSetOf<String>()
        this.changes.forEach { change ->
            requireValidUnicode(change.target.id)
            change.target.parentId?.let(::requireValidUnicode)
            if (!seen.add(change.target.kind.name + '\u0000' + change.target.id)) {
                throw InvalidChangeSet(InvalidChangeSet.Reason.DUPLICATE_TARGET)
            }
        }
    }

    val digest: ChangeSetDigest = ChangeSetDigest(CanonicalChangeSet.sha256(this))
    val summary: ChangeSummary = ChangeSummary.of(this.changes)
}

// UTF-8 encoding silently replaces isolated surrogates; reject them before hashing
// so two different strings can never share a fingerprint.
private fun requireValidUnicode(value: String) {
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character.isHighSurrogate()) {
            if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                throw InvalidChangeSet(InvalidChangeSet.Reason.INVALID_UNICODE)
            }
            index += 2
        } else if (character.isLowSurrogate()) {
            throw InvalidChangeSet(InvalidChangeSet.Reason.INVALID_UNICODE)
        } else {
            index += 1
        }
    }
}

/** Deterministic, versioned encoding shared by approval and future write receipts. */
internal object CanonicalChangeSet {
    private const val VERSION = 1

    fun sha256(changeSet: PreparedChangeSet): String {
        val encoded = buildJsonObject {
            put("version", VERSION)
            put("id", changeSet.id.value)
            put("project", changeSet.projectId.value)
            put("chat", changeSet.chatId.value)
            put("run", changeSet.runId.value)
            put("changes", JsonArray(changeSet.changes.map(::change)))
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(encoded.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun change(value: PreparedChange): JsonElement = buildJsonObject {
        put("kind", value.target.kind.name)
        put("target", value.target.id)
        put("parent", value.target.parentId?.let(::JsonPrimitive) ?: JsonNull)
        put("operation", value.operation.name)
        put("base", value.baseFingerprint?.sha256?.let(::JsonPrimitive) ?: JsonNull)
        put("content", value.contentDigest.sha256)
    }
}
