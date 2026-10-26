package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRequestId
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceApplyStatus
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceChangeSet
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceChangeSetCodec
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceChangeSummary
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceDigest
import java.security.MessageDigest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One outstanding workspace file batch; the file body is only inside [changeSet]. */
enum class WorkspaceChangeProposalState { PENDING, APPLIED, PARTIAL, REJECTED, CONFLICT }

/** Bounded apply outcome; file bodies and paths are deliberately not persisted here. */
class WorkspaceChangeProposalReceipt(
    val status: WorkspaceApplyStatus,
    val committed: Int,
    val conflicts: Int,
    val revision: Long,
    val at: Long,
    /** Bounded path evidence for the durable receipt; never file bodies. */
    val committedPaths: List<String> = emptyList(),
    val conflictPaths: List<String> = emptyList(),
) {
    init {
        require(committed >= 0 && conflicts >= 0 && revision >= 0 && at >= 0) {
            "Invalid workspace proposal receipt"
        }
        require(committedPaths.size == committed || committedPaths.isEmpty())
        require(conflictPaths.size == conflicts || conflictPaths.isEmpty())
        require(committedPaths.distinct().size == committedPaths.size)
        require(conflictPaths.distinct().size == conflictPaths.size)
        when (status) {
            WorkspaceApplyStatus.COMMITTED -> require(committed > 0 && conflicts == 0)
            WorkspaceApplyStatus.PARTIAL -> require(committed > 0 && conflicts > 0)
            WorkspaceApplyStatus.REJECTED -> require(committed == 0 && conflicts > 0)
        }
    }

    override fun toString(): String =
        "WorkspaceChangeProposalReceipt(status=$status, committed=$committed, conflicts=$conflicts)"
}

/**
 * Body-free projection for the Agent status tool and the approval UI. It contains only
 * identities, counts and the durable outcome; change content is never part of this type.
 */
class WorkspaceChangeProposalSummary(
    val id: ApprovalRequestId,
    val ref: LocalProjectRef,
    val chat: ChatId,
    val run: RunId,
    val command: CommandId,
    val baseRevision: Long,
    val digest: WorkspaceDigest,
    val summary: WorkspaceChangeSummary,
    /** Bounded path projection; never contains file bodies. */
    val paths: List<String>,
    val state: WorkspaceChangeProposalState,
    val createdAt: Long,
    val receipt: WorkspaceChangeProposalReceipt?,
) {
    init {
        require(baseRevision >= 0 && createdAt >= 0) { "Invalid workspace proposal summary" }
    }

    val receiptStatus: WorkspaceApplyStatus? get() = receipt?.status

    override fun toString(): String =
        "WorkspaceChangeProposalSummary(id=${id.value}, chat=${chat.value}, state=$state, " +
            "changes=${summary.total}, bytes=${summary.totalBytes})"
}

/** Complete on-disk value. Its toString never includes encoded change content. */
internal data class WorkspaceChangeProposalRecord(
    val id: ApprovalRequestId,
    val ref: LocalProjectRef,
    val chat: ChatId,
    val run: RunId,
    val command: CommandId,
    val baseRevision: Long,
    val changeSet: WorkspaceChangeSet,
    val encodedChangeSet: String,
    val digest: WorkspaceDigest,
    val state: WorkspaceChangeProposalState,
    val createdAt: Long,
    val binding: String?,
    val claimedAt: Long?,
    val receipt: WorkspaceChangeProposalReceipt?,
) {
    fun summary(): WorkspaceChangeProposalSummary = WorkspaceChangeProposalSummary(
        id, ref, chat, run, command, baseRevision, digest, changeSet.summary,
        changeSet.changes.take(64).map { it.path.value }, state, createdAt, receipt,
    )

    override fun toString(): String =
        "WorkspaceChangeProposalRecord(id=${id.value}, state=$state, changes=${changeSet.changes.size})"
}

/**
 * Strict v1 envelope for one proposal. The encoded [WorkspaceChangeSet] is decoded again on
 * every read so its own digest is re-derived; a modified body/digest pair fails closed.
 */
internal object NativeWorkspaceChangeProposalCodec {
    const val VERSION = 1

    /** Matches the change-set codec's 64 MiB limit plus room for the bounded envelope. */
    const val MAX_BYTES: Long = 66L * 1024 * 1024

    private val ROOT_KEYS = setOf(
        "version", "id", "project", "instance", "generation", "chat", "run", "command",
        "base_revision", "change_set", "digest", "state", "created_at", "binding", "claimed_at", "receipt",
    )
    private val RECEIPT_KEYS = setOf("status", "committed", "conflicts", "revision", "at",
        "committed_paths", "conflict_paths")
    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    fun encode(record: WorkspaceChangeProposalRecord): String = buildJsonObject {
        put("version", VERSION)
        put("id", record.id.value)
        put("project", record.ref.projectId.value)
        put("instance", record.ref.instanceId.value)
        put("generation", record.ref.generation.value)
        put("chat", record.chat.value)
        put("run", record.run.value)
        put("command", record.command.value)
        put("base_revision", record.baseRevision)
        put("change_set", record.encodedChangeSet)
        put("digest", record.digest.sha256)
        put("state", record.state.name)
        put("created_at", record.createdAt)
        if (record.binding == null) put("binding", JsonNull) else put("binding", record.binding)
        if (record.claimedAt == null) put("claimed_at", JsonNull) else put("claimed_at", record.claimedAt)
        val receipt = record.receipt
        if (receipt == null) {
            put("receipt", JsonNull)
        } else {
            put("receipt", buildJsonObject {
                put("status", receipt.status.name)
                put("committed", receipt.committed)
                put("conflicts", receipt.conflicts)
                put("revision", receipt.revision)
                put("at", receipt.at)
                put("committed_paths", kotlinx.serialization.json.JsonArray(
                    receipt.committedPaths.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("conflict_paths", kotlinx.serialization.json.JsonArray(
                    receipt.conflictPaths.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            })
        }
    }.toString().also {
        require(it.toByteArray(Charsets.UTF_8).size.toLong() <= MAX_BYTES) {
            "Workspace change proposal exceeds its persistence limit"
        }
    }

    fun decode(text: String): WorkspaceChangeProposalRecord {
        require(text.toByteArray(Charsets.UTF_8).size.toLong() <= MAX_BYTES) {
            "Workspace change proposal exceeds its persistence limit"
        }
        val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_BYTES.toInt(), 16)).json
        require(root.keys == ROOT_KEYS) { "Invalid workspace proposal fields" }
        require(root.getValue("version").jsonPrimitive.content == VERSION.toString()) {
            "Unsupported workspace proposal version"
        }
        val ref = LocalProjectRef(
            ProjectId(text(root, "project")),
            ProjectInstanceId(text(root, "instance")),
            RecoveryGeneration(number(root, "generation", 1, Long.MAX_VALUE)),
        )
        val encoded = text(root, "change_set")
        val changeSet = WorkspaceChangeSetCodec.decode(encoded)
        require(changeSet.projectId == ref.projectId &&
            changeSet.chatId.value == text(root, "chat") &&
            changeSet.runId.value == text(root, "run")) {
            "Workspace proposal identity changed"
        }
        val digest = WorkspaceDigest(text(root, "digest"))
        require(changeSet.digest == digest) { "Workspace proposal digest changed" }
        val state = WorkspaceChangeProposalState.valueOf(text(root, "state"))
        val binding = optionalText(root, "binding")
        val claimedAt = optionalNumber(root, "claimed_at")
        val receipt = when (val element = root.getValue("receipt")) {
            is JsonNull -> null
            is JsonObject -> decodeReceipt(element)
            else -> throw IllegalArgumentException("Invalid workspace proposal receipt")
        }
        val record = WorkspaceChangeProposalRecord(
            ApprovalRequestId(text(root, "id")),
            ref,
            ChatId(text(root, "chat")),
            RunId(text(root, "run")),
            CommandId(text(root, "command")),
            number(root, "base_revision", 0, Long.MAX_VALUE),
            changeSet,
            encoded,
            digest,
            state,
            number(root, "created_at", 0, Long.MAX_VALUE),
            binding,
            claimedAt,
            receipt,
        )
        validate(record)
        return record
    }

    fun binding(digest: WorkspaceDigest, command: CommandId): String = sha256Hex(
        ("lnt-workspace-proposal-binding-v1\n" + digest.sha256 + "\u0000" + command.value)
            .toByteArray(Charsets.UTF_8),
    )

    private fun decodeReceipt(root: JsonObject): WorkspaceChangeProposalReceipt {
        require(root.keys == RECEIPT_KEYS) { "Invalid workspace proposal receipt fields" }
        return WorkspaceChangeProposalReceipt(
            WorkspaceApplyStatus.valueOf(text(root, "status")),
            number(root, "committed", 0, Int.MAX_VALUE.toLong()).toInt(),
            number(root, "conflicts", 0, Int.MAX_VALUE.toLong()).toInt(),
            number(root, "revision", 0, Long.MAX_VALUE),
            number(root, "at", 0, Long.MAX_VALUE),
            strings(root, "committed_paths"),
            strings(root, "conflict_paths"),
        )
    }

    private fun strings(root: JsonObject, key: String): List<String> {
        val value = root.getValue(key) as? kotlinx.serialization.json.JsonArray
            ?: throw IllegalArgumentException("Invalid path list")
        return value.map { element ->
            val text = element as? JsonPrimitive ?: throw IllegalArgumentException("Invalid path")
            require(text.isString && text.content.length <= 512) { "Invalid path" }
            text.content
        }
    }

    private fun validate(record: WorkspaceChangeProposalRecord) {
        require(record.changeSet.summary.total > 0) { "Empty workspace proposal" }
        require((record.binding == null) == (record.claimedAt == null)) {
            "Invalid workspace proposal claim"
        }
        if (record.binding != null) {
            require(SHA256_HEX.matches(record.binding)) { "Invalid workspace proposal binding" }
            require(record.binding == binding(record.digest, record.command)) {
                "Workspace proposal binding changed"
            }
        }
        when (record.state) {
            WorkspaceChangeProposalState.PENDING -> require(record.receipt == null)
            WorkspaceChangeProposalState.APPLIED ->
                require(record.binding != null && record.receipt?.status == WorkspaceApplyStatus.COMMITTED)
            WorkspaceChangeProposalState.PARTIAL ->
                require(record.binding != null && record.receipt?.status == WorkspaceApplyStatus.PARTIAL)
            WorkspaceChangeProposalState.REJECTED -> {
                if (record.receipt == null) {
                    require(record.binding == null)
                } else {
                    require(record.binding != null && record.receipt.status == WorkspaceApplyStatus.REJECTED)
                }
            }
            WorkspaceChangeProposalState.CONFLICT ->
                require(record.binding != null && record.receipt == null)
        }
    }

    private fun text(root: JsonObject, key: String): String {
        val value = root.getValue(key) as? JsonPrimitive ?: throw IllegalArgumentException("Missing text")
        require(value.isString) { "Invalid text" }
        return value.content
    }

    private fun optionalText(root: JsonObject, key: String): String? {
        return when (val element: JsonElement = root.getValue(key)) {
            is JsonNull -> null
            is JsonPrimitive -> {
                require(element.isString) { "Invalid optional text" }
                element.content
            }
            else -> throw IllegalArgumentException("Invalid optional text")
        }
    }

    private fun number(root: JsonObject, key: String, minimum: Long, maximum: Long): Long {
        val value = root.getValue(key).jsonPrimitive
        require(!value.isString) { "Invalid number" }
        val number = value.content.toLongOrNull() ?: throw IllegalArgumentException("Invalid number")
        require(number in minimum..maximum) { "Invalid number range" }
        return number
    }

    private fun optionalNumber(root: JsonObject, key: String): Long? {
        return when (val element: JsonElement = root.getValue(key)) {
            is JsonNull -> null
            is JsonPrimitive -> {
                require(!element.isString) { "Invalid optional number" }
                val number = element.content.toLongOrNull()
                    ?: throw IllegalArgumentException("Invalid optional number")
                require(number >= 0) { "Invalid optional number range" }
                number
            }
            else -> throw IllegalArgumentException("Invalid optional number")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
