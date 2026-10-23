package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ItemId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ObjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import java.util.Collections
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Durable policy checkpoint for one translation run. It records only coordination facts:
 * run/project identity, mode, frozen scope, revision, cursor, a bounded tail of attempted item
 * identities, the committed count and the plan fingerprint. Source and translation bodies are
 * never stored here, so a checkpoint can be logged or exported without leaking novel text.
 *
 * [cursor] is the highest project item identity already considered; resumption starts strictly
 * after it. [attemptedItemIds] is a bounded, ascending tail and may be shorter than
 * [committedItems] on very long runs. An explicit item scope is capped at [MAX_SCOPE_ITEMS] so
 * the encoding stays bounded; a wider command scope cannot be checkpointed.
 */
class TranslationCheckpoint(
    val runId: RunId,
    val ref: LocalProjectRef,
    val mode: TranslationRunMode,
    val scope: TranslationCommandScope,
    val revision: Long,
    val cursor: Long,
    attemptedItemIds: List<ItemId>,
    val committedItems: Int,
    val planFingerprint: ObjectFingerprint,
) {
    val attemptedItemIds: List<ItemId> = Collections.unmodifiableList(ArrayList(attemptedItemIds))

    init {
        require(runId.value.length <= MAX_IDENTITY_LENGTH) { "Run identity is too long" }
        require(ref.projectId.value.length <= MAX_IDENTITY_LENGTH) { "Project identity is too long" }
        require(ref.instanceId.value.length <= MAX_IDENTITY_LENGTH) { "Instance identity is too long" }
        require(revision >= 0 && cursor >= 0) { "Checkpoint revisions must not be negative" }
        require(committedItems >= 0) { "Committed count must not be negative" }
        require(scope.itemIds.size <= MAX_SCOPE_ITEMS) { "Checkpoint scope exceeds the encoding bound" }
        require(this.attemptedItemIds.size <= MAX_ATTEMPTED_ITEMS) {
            "Attempted identities exceed the checkpoint bound"
        }
        require(this.attemptedItemIds.zipWithNext().all { (left, right) -> left.value < right.value }) {
            "Attempted identities must be ascending and duplicate-free"
        }
        require(this.attemptedItemIds.all { it.value <= cursor }) {
            "An attempted identity lies beyond the checkpoint cursor"
        }
    }

    fun withAttemptedItemIds(values: List<ItemId>, cursor: Long, committedItems: Int): TranslationCheckpoint {
        val tail = (attemptedItemIds + values).distinct().sortedBy { it.value }.takeLast(MAX_ATTEMPTED_ITEMS)
        return TranslationCheckpoint(runId, ref, mode, scope, revision, cursor, tail, committedItems, planFingerprint)
    }

    override fun equals(other: Any?): Boolean = other is TranslationCheckpoint &&
        runId == other.runId && ref == other.ref && mode == other.mode && scope == other.scope &&
        revision == other.revision && cursor == other.cursor && attemptedItemIds == other.attemptedItemIds &&
        committedItems == other.committedItems && planFingerprint == other.planFingerprint

    override fun hashCode(): Int = java.util.Objects.hash(runId, ref, mode, scope, revision, cursor,
        attemptedItemIds, committedItems, planFingerprint)

    override fun toString(): String =
        "TranslationCheckpoint(run=${runId.value}, mode=$mode, revision=$revision, cursor=$cursor, " +
            "attempted=${attemptedItemIds.size}, committed=$committedItems)"

    companion object {
        const val MAX_ATTEMPTED_ITEMS = 200
        const val MAX_SCOPE_ITEMS = 1_000
        const val MAX_IDENTITY_LENGTH = 2_048
    }
}

/**
 * Strict canonical codec. Decoding rejects unknown/missing keys, duplicate JSON keys, non-integer
 * numbers, non-positive identities, unsorted attempt tails and values beyond the cursor. The
 * decoder never falls back to a partial checkpoint.
 */
object TranslationCheckpointCodec {
    const val FORMAT = "lnt-translation-checkpoint"
    const val VERSION = 1
    const val MAX_CHARS = 65_536

    private val ROOT_KEYS = setOf("format", "version", "run_id", "project", "mode", "scope",
        "revision", "cursor", "attempted_item_ids", "committed_items", "plan_fingerprint")
    private val PROJECT_KEYS = setOf("id", "instance", "generation")

    fun encode(value: TranslationCheckpoint): String {
        val text = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("run_id", value.runId.value)
            put("project", buildJsonObject {
                put("id", value.ref.projectId.value)
                put("instance", value.ref.instanceId.value)
                put("generation", value.ref.generation.value)
            })
            put("mode", value.mode.name)
            put("scope", buildJsonObject {
                put("kind", value.scope.kind.name)
                if (value.scope.kind == TranslationCommandScopeKind.ITEMS) {
                    put("item_ids", JsonArray(value.scope.itemIds.map { JsonPrimitive(it.value) }))
                }
            })
            put("revision", value.revision)
            put("cursor", value.cursor)
            put("attempted_item_ids", JsonArray(value.attemptedItemIds.map { JsonPrimitive(it.value) }))
            put("committed_items", value.committedItems)
            put("plan_fingerprint", value.planFingerprint.sha256)
        }.toString()
        require(text.length <= MAX_CHARS) { "Checkpoint encoding exceeds the size limit" }
        return text
    }

    fun decode(text: String): TranslationCheckpoint {
        val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_CHARS, 8)).json
        require(root.keys == ROOT_KEYS) { "Unexpected checkpoint fields" }
        require(string(root, "format") == FORMAT) { "Unsupported checkpoint format" }
        require(long(root, "version") == VERSION.toLong()) { "Unsupported checkpoint version" }

        val runId = RunId(string(root, "run_id"))
        val project = objectValue(root, "project")
        require(project.keys == PROJECT_KEYS) { "Unexpected checkpoint project fields" }
        val ref = LocalProjectRef(
            ProjectId(string(project, "id")),
            ProjectInstanceId(string(project, "instance")),
            RecoveryGeneration(positiveLong(project, "generation")),
        )
        val mode = TranslationRunMode.entries.firstOrNull { it.name == string(root, "mode") }
            ?: throw IllegalArgumentException("Unknown checkpoint mode")
        val scope = decodeScope(root.getValue("scope"))
        val revision = nonNegativeLong(root, "revision")
        val cursor = nonNegativeLong(root, "cursor")
        val attempted = decodeAttempts(root.getValue("attempted_item_ids"))
        val committed = boundedCount(root, "committed_items")
        val fingerprint = ObjectFingerprint(string(root, "plan_fingerprint"))
        return TranslationCheckpoint(runId, ref, mode, scope, revision, cursor, attempted, committed, fingerprint)
    }

    private fun decodeScope(element: JsonElement): TranslationCommandScope {
        val value = element as? JsonObject ?: throw IllegalArgumentException("Checkpoint scope must be an object")
        require("kind" in value) { "Checkpoint scope is missing its kind" }
        val kind = string(value, "kind")
        return when (kind) {
            "ALL" -> {
                require(value.keys == setOf("kind")) { "Unexpected ALL scope fields" }
                TranslationCommandScope.all()
            }

            "ITEMS" -> {
                require(value.keys == setOf("kind", "item_ids")) { "Unexpected ITEMS scope fields" }
                val array = value.getValue("item_ids") as? JsonArray
                    ?: throw IllegalArgumentException("Scope item_ids must be an array")
                require(array.size in 1..TranslationCheckpoint.MAX_SCOPE_ITEMS) { "Scope item count is out of range" }
                val ids = array.map { ItemId(positiveNumber(it)) }
                require(ids.zipWithNext().all { (left, right) -> left.value < right.value }) {
                    "Scope identities must be ascending and duplicate-free"
                }
                TranslationCommandScope.items(ids)
            }

            else -> throw IllegalArgumentException("Unknown checkpoint scope kind")
        }
    }

    private fun decodeAttempts(element: JsonElement): List<ItemId> {
        val array = element as? JsonArray ?: throw IllegalArgumentException("Attempted identities must be an array")
        require(array.size <= TranslationCheckpoint.MAX_ATTEMPTED_ITEMS) { "Attempted identity list is too long" }
        val ids = array.map { ItemId(positiveNumber(it)) }
        require(ids.zipWithNext().all { (left, right) -> left.value < right.value }) {
            "Attempted identities must be ascending and duplicate-free"
        }
        return ids
    }

    private fun objectValue(value: JsonObject, key: String): JsonObject {
        val element = value.getValue(key)
        return element as? JsonObject ?: throw IllegalArgumentException("Checkpoint field $key must be an object")
    }

    private fun primitive(value: JsonObject, key: String): JsonPrimitive {
        val element = value.getValue(key)
        return element as? JsonPrimitive ?: throw IllegalArgumentException("Checkpoint field $key must be scalar")
    }

    private fun string(value: JsonObject, key: String): String {
        val scalar = primitive(value, key)
        require(scalar != JsonNull && scalar.isString) { "Checkpoint field $key must be a string" }
        return scalar.content
    }

    private fun long(value: JsonObject, key: String): Long {
        val scalar = primitive(value, key)
        require(scalar != JsonNull && !scalar.isString) { "Checkpoint field $key must be a number" }
        return requireNotNull(scalar.longOrNull) { "Checkpoint field $key must be an integer" }
    }

    private fun nonNegativeLong(value: JsonObject, key: String): Long =
        long(value, key).also { require(it >= 0) { "Checkpoint field $key must not be negative" } }

    private fun positiveLong(value: JsonObject, key: String): Long =
        long(value, key).also { require(it > 0) { "Checkpoint field $key must be positive" } }

    private fun boundedCount(value: JsonObject, key: String): Int {
        val parsed = long(value, key)
        require(parsed in 0L..Int.MAX_VALUE.toLong()) { "Checkpoint field $key is out of range" }
        return parsed.toInt()
    }

    private fun positiveNumber(element: JsonElement): Long {
        val scalar = element as? JsonPrimitive ?: throw IllegalArgumentException("Identity must be scalar")
        require(scalar != JsonNull && !scalar.isString) { "Identity must be a number" }
        val parsed = requireNotNull(scalar.longOrNull) { "Identity must be an integer" }
        require(parsed > 0) { "Identity must be positive" }
        return parsed
    }
}
