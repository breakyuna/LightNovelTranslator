package com.breakyuna.noveltranslator.nativecore.workspace

import android.util.Base64
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Versioned, deterministic persistence for one approval-bound workspace file batch.
 *
 * Only immutable inline bodies are serialized. A proposal adapter materializes a
 * [WorkspaceChangeContent.FromMaterial] before persisting it; otherwise a temporary analysis
 * material could disappear between approval and apply. The digest is re-derived on decode and
 * must equal the declared digest, so an edited file cannot silently become a new approval.
 */
object WorkspaceChangeSetCodec {
    private const val VERSION = 1
    private val ROOT_KEYS = setOf("version", "id", "project", "chat", "run", "case_policy", "digest", "changes")
    private val CHANGE_KEYS = setOf("path", "operation", "expected", "content", "size", "sha256")
    private val CONTENT_KEYS = setOf("encoding", "base64")
    // Workspace proposals are capped at 32 MiB of raw changes (4 MiB each); base64 expansion
    // still fits comfortably, while an arbitrary 128 MiB JSON tree is not allocated on device.
    private val MAX_JSON_BYTES = 64 * 1024 * 1024

    fun encode(changeSet: WorkspaceChangeSet): String {
        val text = buildJsonObject {
            put("version", VERSION)
            put("id", changeSet.id.value)
            put("project", changeSet.projectId.value)
            put("chat", changeSet.chatId.value)
            put("run", changeSet.runId.value)
            put("case_policy", changeSet.policy.casePolicy.name)
            put("digest", changeSet.digest.sha256)
            put("changes", JsonArray(changeSet.changes.map { change ->
                buildJsonObject {
                    put("path", change.path.value)
                    put("operation", change.operation.name)
                    put("expected", change.expectedFingerprint?.sha256 ?: "")
                    when (val content = change.content) {
                        null -> Unit
                        is WorkspaceChangeContent.Inline -> {
                            put("size", content.sizeBytes)
                            put("sha256", content.sha256.sha256)
                            put("content", buildJsonObject {
                                put("encoding", "base64")
                                put("base64", Base64.encodeToString(content.bytes, Base64.NO_WRAP))
                            })
                        }
                        is WorkspaceChangeContent.FromMaterial ->
                            throw IllegalArgumentException("Material content must be materialized before persistence")
                    }
                }
            }))
        }.toString()
        require(TextFingerprint.utf8(text).size <= MAX_JSON_BYTES) { "Workspace change set exceeds its persistence limit" }
        return text
    }

    fun decode(text: String): WorkspaceChangeSet {
        require(TextFingerprint.utf8(text).size <= MAX_JSON_BYTES) { "Workspace change set exceeds its persistence limit" }
        val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_JSON_BYTES, 16)).json
        require(root.keys == ROOT_KEYS) { "Invalid workspace change set fields" }
        require(root.getValue("version").jsonPrimitive.content == VERSION.toString()) {
            "Unsupported workspace change set version"
        }
        val policy = PathPolicy(casePolicy = WorkspaceCasePolicy.valueOf(root.getValue("case_policy").jsonPrimitive.content))
        val changes = (root.getValue("changes") as? JsonArray ?: error("Invalid workspace changes"))
            .map { element ->
                val row = element as? JsonObject ?: error("Invalid workspace change")
                require(row.keys == CHANGE_KEYS || row.keys == CHANGE_KEYS - setOf("content", "size", "sha256")) {
                    "Invalid workspace change fields"
                }
                val operation = WorkspaceChangeOperation.valueOf(row.getValue("operation").jsonPrimitive.content)
                val expected = row.getValue("expected").jsonPrimitive.content.takeIf { it.isNotEmpty() }
                    ?.let(::WorkspaceDigest)
                val path = WorkspacePath(row.getValue("path").jsonPrimitive.content)
                val content = if (operation == WorkspaceChangeOperation.DELETE) {
                    null
                } else {
                    val body = row.getValue("content") as? JsonObject ?: error("Missing workspace change content")
                    require(body.keys == CONTENT_KEYS) { "Invalid workspace change content fields" }
                    require(body.getValue("encoding").jsonPrimitive.content == "base64") {
                        "Unsupported workspace change encoding"
                    }
                    val bytes = Base64.decode(body.getValue("base64").jsonPrimitive.content, Base64.NO_WRAP)
                    require(row.getValue("size").jsonPrimitive.content == bytes.size.toString()) {
                        "Workspace change size mismatch"
                    }
                    val inline = WorkspaceChangeContent.Inline(bytes)
                    require(row.getValue("sha256").jsonPrimitive.content == inline.sha256.sha256) {
                        "Workspace change digest mismatch"
                    }
                    inline
                }
                WorkspaceChange(path, operation, expected, content)
            }
        val set = WorkspaceChangeSet(
            WorkspaceChangeSetId(root.getValue("id").jsonPrimitive.content),
            ProjectId(root.getValue("project").jsonPrimitive.content),
            ChatId(root.getValue("chat").jsonPrimitive.content),
            RunId(root.getValue("run").jsonPrimitive.content),
            changes,
            policy,
        )
        require(root.getValue("digest").jsonPrimitive.content == set.digest.sha256) {
            "Workspace change set digest mismatch"
        }
        return set
    }

    private object TextFingerprint {
        fun utf8(value: String): ByteArray = com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint.utf8(value)
    }
}
