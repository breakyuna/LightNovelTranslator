package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.*

enum class ItemProposalState { PENDING, CONSUMED, COMMITTED, PARTIAL, CONFLICT, REJECTED, CANCELLED }
enum class ItemProposalRejection { NOT_SELECTED, STALE_REVISION, STALE_FINGERPRINT, UNKNOWN_ITEM, BATCH_CONFLICT }

/** Frozen values for one proposed item; metadata unrelated to these fields stays untouched. */
class ItemProposalValue(val translation: String, val status: TextItemStatus,
                        nameSource: List<String>?, nameTranslation: List<String>?) {
    val nameSource = nameSource?.let { frozenList(it) }
    val nameTranslation = nameTranslation?.let { frozenList(it) }
    init {
        require(translation.length <= 65_536)
        TextProjectFingerprint.utf8(translation)
        for (names in listOf(this.nameSource, this.nameTranslation)) {
            require(names == null || names.size <= 64)
            names?.forEach { require(it.length <= 4_096 && '\u0000' !in it); TextProjectFingerprint.utf8(it) }
            require(namesJson(names).toString().length <= 32 * 4_096)
        }
    }
    fun json(): JsonObject = buildJsonObject {
        put("translation", translation); put("status", status.name)
        put("name_source", namesJson(nameSource)); put("name_translation", namesJson(nameTranslation))
    }
    override fun toString() = "ItemProposalValue(redacted)"
}

class ItemProposalMember(val id: ItemId, val row: Int, val assetId: Long,
                         val base: ObjectFingerprint, val before: ItemProposalValue, val after: ItemProposalValue) {
    init { require(row >= 0 && assetId > 0); require(before.json() != after.json()) { "Empty proposal member" } }
    fun json(): JsonObject = buildJsonObject {
        put("id", id.value); put("row", row); put("asset", assetId); put("base", base.sha256)
        put("before", before.json()); put("after", after.json())
    }
    override fun toString() = "ItemProposalMember(id=${id.value})"
}

/** Body bound to a local project instance, one Chat and one immutable submission command. */
class ItemChangeProposal(val id: ApprovalRequestId, val ref: LocalProjectRef, val chat: ChatId,
                         val run: RunId, val command: CommandId, val revision: Long,
                         val policy: ConflictPolicy, members: List<ItemProposalMember>) {
    val members = frozenList(members)
    init {
        require(revision >= 0 && this.members.size in 1..MAX_MEMBERS)
        require(this.members.map { it.id }.toSet().size == this.members.size)
    }
    val payload: String = buildJsonObject {
        put("version", 1); put("id", id.value); put("project", ref.projectId.value)
        put("instance", ref.instanceId.value); put("generation", ref.generation.value)
        put("chat", chat.value); put("run", run.value); put("command", command.value)
        put("revision", revision); put("policy", policy.name)
        put("members", JsonArray(this@ItemChangeProposal.members.map { it.json() }))
    }.toString().also { require(TextProjectFingerprint.utf8(it).size <= MAX_PAYLOAD_BYTES) }
    val digest: ObjectFingerprint = ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(payload)))
    fun binding(selected: Set<ItemId>): ObjectFingerprint {
        require(selected.isNotEmpty() && selected.all { id -> members.any { it.id == id } })
        return ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(buildJsonObject {
            put("version", 1); put("proposal", digest.sha256)
            put("selected", JsonArray(selected.sortedBy { it.value }.map { JsonPrimitive(it.value) }))
        }.toString())))
    }
    override fun toString() = "ItemChangeProposal(id=${id.value}, members=${members.size})"
    companion object { const val MAX_MEMBERS = 1_024; const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024 }
}

/** Body-free list/UI projection; individual immutable differences load only on demand. */
class ItemProposalSummary(val id: ApprovalRequestId, val ref: LocalProjectRef, val chat: ChatId, val run: RunId,
                          val command: CommandId, val revision: Long, val policy: ConflictPolicy,
                          val digest: ObjectFingerprint, val count: Int, val state: ItemProposalState)
data class ItemProposalMemberSummary(val id: ItemId, val row: Int, val assetId: Long,
                                     val textChanged: Boolean, val namesChanged: Boolean, val statusChanged: Boolean)
class ItemProposalReceipt(val command: CommandId, val binding: ObjectFingerprint, val revision: Long,
                          accepted: List<ItemId>, rejected: Map<ItemId, ItemProposalRejection>,
                          val committedAtMillis: Long = 0) {
    val accepted = frozenList(accepted)
    val rejected = java.util.Collections.unmodifiableMap(LinkedHashMap(rejected))
    init { require(revision >= 0 && committedAtMillis >= 0); require(this.accepted.isNotEmpty() || this.rejected.isNotEmpty())
        require(this.accepted.toSet().size == this.accepted.size && this.accepted.none { it in this.rejected }) }
    val state: ItemProposalState get() = when {
        accepted.isEmpty() -> ItemProposalState.CONFLICT
        rejected.isEmpty() -> ItemProposalState.COMMITTED
        else -> ItemProposalState.PARTIAL
    }
}

internal fun namesJson(names: List<String>?): JsonElement = names?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull

/** Strict scalar/array patch decoder; no numeric coercion or silent unknown fields. */
object ItemProposalCodec {
    fun proposal(encoded: String): ItemChangeProposal {
        val root = JsonObjectValue.parse(encoded, JsonInputLimits(ItemChangeProposal.MAX_PAYLOAD_BYTES, 16)).json
        require(root.keys == setOf("version", "id", "project", "instance", "generation", "chat", "run", "command", "revision", "policy", "members"))
        require(root["version"] == JsonPrimitive(1))
        fun number(key: String, minimum: Long): Long {
            val raw = root[key] as? JsonPrimitive ?: error("Integer required"); require(!raw.isString)
            return ToolArguments.long(ToolScalar.NumberToken(raw.content), key, minimum, Long.MAX_VALUE)
        }
        val members = root["members"] as? JsonArray ?: error("Members required")
        require(members.size in 1..ItemChangeProposal.MAX_MEMBERS)
        return ItemChangeProposal(ApprovalRequestId(string(root, "id")), LocalProjectRef(ProjectId(string(root, "project")),
            ProjectInstanceId(string(root, "instance")), RecoveryGeneration(number("generation", 1))),
            ChatId(string(root, "chat")), RunId(string(root, "run")), CommandId(string(root, "command")),
            number("revision", 0), ConflictPolicy.valueOf(string(root, "policy")), members.map { member(it.toString()) })
    }
    fun selection(ids: Set<ItemId>): String = buildJsonObject {
        put("items", JsonArray(ids.sortedBy { it.value }.map { JsonPrimitive(it.value) }))
    }.toString()
    fun selection(encoded: String): Set<ItemId> {
        val root = JsonObjectValue.parse(encoded, JsonInputLimits(64 * 1024, 4)).json
        require(root.keys == setOf("items"))
        val values = root["items"] as? JsonArray ?: error("Selection required")
        require(values.size in 1..ItemChangeProposal.MAX_MEMBERS)
        val ids = values.map { raw -> id(buildJsonObject { put("item_id", raw) }) }
        require(ids.toSet().size == ids.size)
        return ids.toSet()
    }
    fun receipt(receipt: ItemProposalReceipt): String = buildJsonObject {
        put("version", 1); put("command", receipt.command.value); put("binding", receipt.binding.sha256)
        put("revision", receipt.revision)
        put("committed_at", receipt.committedAtMillis)
        put("accepted", JsonArray(receipt.accepted.map { JsonPrimitive(it.value) }))
        put("rejected", JsonArray(receipt.rejected.map { (id, reason) -> buildJsonObject {
            put("item_id", id.value); put("reason", reason.name)
        } }))
    }.toString()
    fun receipt(encoded: String): ItemProposalReceipt {
        val root = JsonObjectValue.parse(encoded, JsonInputLimits(512 * 1024, 8)).json
        require(root.keys == setOf("version", "command", "binding", "revision", "committed_at", "accepted", "rejected"))
        require(root["version"] == JsonPrimitive(1))
        val revision = root["revision"] as? JsonPrimitive ?: error("Revision required")
        require(!revision.isString)
        val committedAt = root["committed_at"] as? JsonPrimitive ?: error("Commit time required")
        require(!committedAt.isString)
        val accepted = (root["accepted"] as? JsonArray ?: error("Accepted list required")).map { raw ->
            id(buildJsonObject { put("item_id", raw) })
        }
        val rejected = LinkedHashMap<ItemId, ItemProposalRejection>()
        for (raw in root["rejected"] as? JsonArray ?: error("Rejected list required")) {
            val item = raw as? JsonObject ?: error("Rejected item required")
            require(item.keys == setOf("item_id", "reason"))
            require(rejected.put(id(item), ItemProposalRejection.valueOf(string(item, "reason"))) == null)
        }
        return ItemProposalReceipt(CommandId(string(root, "command")), ObjectFingerprint(string(root, "binding")),
            ToolArguments.long(ToolScalar.NumberToken(revision.content), "revision", 0, Long.MAX_VALUE), accepted, rejected,
            ToolArguments.long(ToolScalar.NumberToken(committedAt.content), "committed_at", 0, Long.MAX_VALUE))
    }
    fun patches(json: String): List<JsonObject> {
        val root = JsonObjectValue.parse(json, JsonInputLimits(4 * 1024 * 1024, 16)).json
        require(root.keys == setOf("items"))
        val items = root["items"] as? JsonArray ?: error("Items array required")
        require(items.size in 1..ItemChangeProposal.MAX_MEMBERS)
        return items.map { value ->
            val patch = value as? JsonObject ?: error("Item object required")
            require(setOf("item_id", "fingerprint", "translation", "status", "name_source", "name_translation").containsAll(patch.keys))
            id(patch); ObjectFingerprint(string(patch, "fingerprint"))
            require(patch.keys.any { it in setOf("translation", "status", "name_source", "name_translation") })
            patch
        }.also { require(it.map(::id).toSet().size == it.size) }
    }
    fun id(patch: JsonObject): ItemId {
        val value = patch["item_id"] as? JsonPrimitive ?: error("Item identity required")
        require(!value.isString)
        return ItemId(ToolArguments.long(ToolScalar.NumberToken(value.content), "item_id", 1, Long.MAX_VALUE))
    }
    fun string(objectValue: JsonObject, key: String): String {
        val value = objectValue[key] as? JsonPrimitive ?: error("String required")
        require(value.isString); return value.content
    }
    fun names(element: JsonElement): List<String>? {
        if (element is JsonNull) return null
        val values = element as? JsonArray ?: error("Names array required")
        require(values.size <= 64)
        return values.map { value -> require(value is JsonPrimitive && value.isString); value.content }
    }
    fun value(json: JsonObject): ItemProposalValue {
        require(json.keys == setOf("translation", "status", "name_source", "name_translation"))
        return ItemProposalValue(string(json, "translation"), TextItemStatus.valueOf(string(json, "status")),
            names(requireNotNull(json["name_source"])), names(requireNotNull(json["name_translation"])))
    }
    fun patch(item: TextProjectItem, patch: JsonObject): ItemProposalMember {
        require(id(patch) == item.id && string(patch, "fingerprint") == item.fingerprint.sha256)
        val before = ItemProposalValue(item.translation, item.status, item.metadata.nameSource, item.metadata.nameTranslation)
        val status = if ("status" in patch) TextItemStatus.valueOf(string(patch, "status")).also { require(it.manual) }
            else if ("translation" in patch) TextItemStatus.PROCESSED else item.status
        return ItemProposalMember(item.id, item.row, item.assetId, item.fingerprint, before,
            ItemProposalValue(if ("translation" in patch) string(patch, "translation") else item.translation,
                status, if ("name_source" in patch) names(requireNotNull(patch["name_source"])) else item.metadata.nameSource,
                if ("name_translation" in patch) names(requireNotNull(patch["name_translation"])) else item.metadata.nameTranslation))
    }
    fun member(encoded: String): ItemProposalMember {
        val json = JsonObjectValue.parse(encoded, JsonInputLimits(ItemChangeProposal.MAX_PAYLOAD_BYTES, 16)).json
        require(json.keys == setOf("id", "row", "asset", "base", "before", "after"))
        fun number(key: String, min: Long, max: Long): Long {
            val raw = json[key] as? JsonPrimitive ?: error("Integer required"); require(!raw.isString)
            return ToolArguments.long(ToolScalar.NumberToken(raw.content), key, min, max)
        }
        return ItemProposalMember(ItemId(number("id", 1, Long.MAX_VALUE)), number("row", 0, Int.MAX_VALUE.toLong()).toInt(),
            number("asset", 1, Long.MAX_VALUE), ObjectFingerprint(string(json, "base")),
            value(json["before"] as? JsonObject ?: error("Before required")),
            value(json["after"] as? JsonObject ?: error("After required")))
    }
}
