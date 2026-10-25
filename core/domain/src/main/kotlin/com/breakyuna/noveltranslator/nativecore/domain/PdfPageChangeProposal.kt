package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.*

enum class PdfPageProposalState { PENDING, CONSUMED, COMMITTED, PARTIAL, CONFLICT, REJECTED, CANCELLED }
enum class PdfPageProposalRejection { NOT_SELECTED, STALE_REVISION, STALE_ASSET, STALE_PAGE, BATCH_CONFLICT }

/** One requested page decision. The before value is the effective decision at preparation time. */
class PdfPageChangeMember(val page: Int, val before: PdfPageMode, val after: PdfPageMode) {
    init { require(page >= 1 && before != after) }
    fun json(): JsonObject = buildJsonObject {
        put("page", page); put("before", before.name); put("after", after.name)
    }
    override fun toString() = "PdfPageChangeMember(page=$page, values=redacted)"
}

/** One strict page/decision pair decoded from a tool request. */
data class PdfPageChangeTarget(val page: Int, val decision: PdfPageMode)

/**
 * Frozen PDF page decision proposal. It binds an asset source identity, one project revision,
 * one Chat/Run/Command and the selected conflict policy. Page bodies are decision codes only;
 * PDF source text never enters the proposal or a list projection.
 */
class PdfPageChangeProposal(
    val id: ApprovalRequestId,
    val ref: LocalProjectRef,
    val chat: ChatId,
    val run: RunId,
    val command: CommandId,
    val revision: Long,
    val assetId: Long,
    val assetSha256: String,
    val pageCount: Int,
    val policy: ConflictPolicy = ConflictPolicy.REJECT_BATCH,
    members: List<PdfPageChangeMember>,
) {
    val members: List<PdfPageChangeMember> = frozenList(members.sortedBy { it.page })
    init {
        require(revision >= 0 && assetId > 0 && pageCount in 1..MAX_PAGES)
        require(assetSha256.length == 64 && assetSha256.all { it in "0123456789abcdef" })
        require(this.members.size in 1..pageCount)
        require(this.members.map { it.page }.toSet().size == this.members.size)
        require(this.members.all { it.page in 1..pageCount })
    }
    val payload: String = buildJsonObject {
        put("version", 1); put("id", id.value); put("project", ref.projectId.value)
        put("instance", ref.instanceId.value); put("generation", ref.generation.value)
        put("chat", chat.value); put("run", run.value); put("command", command.value)
        put("revision", revision); put("asset", assetId); put("sha256", assetSha256)
        put("page_count", pageCount); put("policy", policy.name)
        put("pages", JsonArray(this@PdfPageChangeProposal.members.map { it.json() }))
    }.toString().also { require(TextProjectFingerprint.utf8(it).size <= MAX_PAYLOAD_BYTES) }
    val digest: ObjectFingerprint = ObjectFingerprint(
        TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(payload)),
    )
    fun binding(selected: Set<Int>): ObjectFingerprint {
        require(selected.isNotEmpty() && selected.all { page -> members.any { it.page == page } })
        return ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(buildJsonObject {
            put("version", 1); put("proposal", digest.sha256)
            put("pages", JsonArray(selected.sorted().map { JsonPrimitive(it) }))
        }.toString())))
    }
    fun summary(state: PdfPageProposalState) = PdfPageProposalSummary(id, ref, chat, run, command, revision,
        assetId, assetSha256, pageCount, policy, digest, members.size, state)
    override fun toString() = "PdfPageChangeProposal(id=${id.value}, pages=${members.size})"

    companion object {
        const val MAX_PAGES = 5_000
        const val MAX_PAYLOAD_BYTES = 1_048_576
    }
}

/** Body-free list/UI projection; page decisions are not source material. */
class PdfPageProposalSummary(
    val id: ApprovalRequestId,
    val ref: LocalProjectRef,
    val chat: ChatId,
    val run: RunId,
    val command: CommandId,
    val revision: Long,
    val assetId: Long,
    val assetSha256: String,
    val pageCount: Int,
    val policy: ConflictPolicy,
    val digest: ObjectFingerprint,
    val count: Int,
    val state: PdfPageProposalState,
)

data class PdfPageChangeMemberSummary(val page: Int, val before: PdfPageMode, val after: PdfPageMode)

class PdfPageProposalReceipt(
    val command: CommandId,
    val binding: ObjectFingerprint,
    val revision: Long,
    accepted: List<Int>,
    rejected: Map<Int, PdfPageProposalRejection>,
    val committedAtMillis: Long = 0,
) {
    val accepted: List<Int> = frozenList(accepted.sorted())
    val rejected: Map<Int, PdfPageProposalRejection> =
        java.util.Collections.unmodifiableMap(LinkedHashMap(rejected.toSortedMap()))
    init {
        require(revision >= 0 && committedAtMillis >= 0)
        require(this.accepted.isNotEmpty() || this.rejected.isNotEmpty())
        require(this.accepted.toSet().size == this.accepted.size && this.accepted.none { it in this.rejected })
    }
    val state: PdfPageProposalState get() = when {
        accepted.isEmpty() -> PdfPageProposalState.CONFLICT
        rejected.isEmpty() -> PdfPageProposalState.COMMITTED
        else -> PdfPageProposalState.PARTIAL
    }
    override fun toString() = "PdfPageProposalReceipt(command=${command.value}, values=redacted)"
}

/** Strict decoder for frozen proposals, page requests, selections and receipts. */
object PdfPageChangeProposalCodec {
    private val modes = PdfPageMode.entries.associateBy { it.name }

    fun targets(encoded: String): List<PdfPageChangeTarget> {
        val root = JsonObjectValue.parse(encoded, JsonInputLimits(512 * 1024, 16)).json
        require(root.keys == setOf("pages"))
        val pages = root["pages"] as? JsonArray ?: throw IllegalArgumentException("Pages required")
        require(pages.size in 1..PdfPageChangeProposal.MAX_PAGES)
        val result = pages.map { raw ->
            val page = raw as? JsonObject ?: throw IllegalArgumentException("Page object required")
            require(page.keys == setOf("page", "decision"))
            val pageNumber = integer(page, "page", 1, PdfPageChangeProposal.MAX_PAGES.toLong()).toInt()
            val decision = text(page, "decision")
            PdfPageChangeTarget(pageNumber, modes[decision] ?: throw IllegalArgumentException("Page decision required"))
        }
        require(result.map { it.page }.toSet().size == result.size) { "Duplicate page" }
        return result.sortedBy { it.page }
    }

    fun proposal(encoded: String): PdfPageChangeProposal {
        val root = JsonObjectValue.parse(encoded, JsonInputLimits(PdfPageChangeProposal.MAX_PAYLOAD_BYTES, 16)).json
        require(root.keys == setOf("version", "id", "project", "instance", "generation", "chat", "run", "command",
            "revision", "asset", "sha256", "page_count", "policy", "pages"))
        require(root["version"] == JsonPrimitive(1))
        val pageCount = integer(root, "page_count", 1, PdfPageChangeProposal.MAX_PAGES.toLong()).toInt()
        val pages = root["pages"] as? JsonArray ?: throw IllegalArgumentException("Pages required")
        require(pages.size in 1..pageCount)
        return PdfPageChangeProposal(ApprovalRequestId(text(root, "id")),
            LocalProjectRef(ProjectId(text(root, "project")), ProjectInstanceId(text(root, "instance")),
                RecoveryGeneration(integer(root, "generation", 1, Long.MAX_VALUE))),
            ChatId(text(root, "chat")), RunId(text(root, "run")), CommandId(text(root, "command")),
            integer(root, "revision", 0, Long.MAX_VALUE), integer(root, "asset", 1, Long.MAX_VALUE),
            text(root, "sha256"), pageCount, ConflictPolicy.valueOf(text(root, "policy")),
            pages.map { member(it.toString()) })
    }

    fun member(encoded: String): PdfPageChangeMember {
        val json = JsonObjectValue.parse(encoded, JsonInputLimits(4_096, 4)).json
        require(json.keys == setOf("page", "before", "after"))
        return PdfPageChangeMember(integer(json, "page", 1, PdfPageChangeProposal.MAX_PAGES.toLong()).toInt(),
            PdfPageMode.valueOf(text(json, "before")), PdfPageMode.valueOf(text(json, "after")))
    }

    fun selection(pages: Set<Int>): String = buildJsonObject {
        put("pages", JsonArray(pages.sorted().map { JsonPrimitive(it) }))
    }.toString()

    fun selection(encoded: String): Set<Int> {
        val root = JsonObjectValue.parse(encoded, JsonInputLimits(64 * 1024, 4)).json
        require(root.keys == setOf("pages"))
        val values = root["pages"] as? JsonArray ?: throw IllegalArgumentException("Selection required")
        require(values.size in 1..PdfPageChangeProposal.MAX_PAGES)
        val pages = values.map { raw ->
            val page = raw as? JsonPrimitive ?: throw IllegalArgumentException("Page required")
            require(!page.isString)
            ToolArguments.int(ToolScalar.NumberToken(page.content), "page", 1, PdfPageChangeProposal.MAX_PAGES)
        }
        require(pages.toSet().size == pages.size)
        return pages.toSet()
    }

    fun receipt(receipt: PdfPageProposalReceipt): String = buildJsonObject {
        put("version", 1); put("command", receipt.command.value); put("binding", receipt.binding.sha256)
        put("revision", receipt.revision); put("committed_at", receipt.committedAtMillis)
        put("accepted", JsonArray(receipt.accepted.map { JsonPrimitive(it) }))
        put("rejected", JsonArray(receipt.rejected.map { (page, reason) -> buildJsonObject {
            put("page", page); put("reason", reason.name)
        } }))
    }.toString()

    fun receipt(encoded: String): PdfPageProposalReceipt {
        val root = JsonObjectValue.parse(encoded, JsonInputLimits(512 * 1024, 8)).json
        require(root.keys == setOf("version", "command", "binding", "revision", "committed_at", "accepted", "rejected"))
        require(root["version"] == JsonPrimitive(1))
        val accepted = (root["accepted"] as? JsonArray ?: throw IllegalArgumentException("Accepted list required")).map { raw ->
            val page = raw as? JsonPrimitive ?: throw IllegalArgumentException("Page required")
            require(!page.isString)
            ToolArguments.int(ToolScalar.NumberToken(page.content), "page", 1, PdfPageChangeProposal.MAX_PAGES)
        }
        val rejected = LinkedHashMap<Int, PdfPageProposalRejection>()
        for (raw in root["rejected"] as? JsonArray ?: throw IllegalArgumentException("Rejected list required")) {
            val item = raw as? JsonObject ?: throw IllegalArgumentException("Rejected page required")
            require(item.keys == setOf("page", "reason"))
            require(rejected.put(integer(item, "page", 1, PdfPageChangeProposal.MAX_PAGES.toLong()).toInt(),
                PdfPageProposalRejection.valueOf(text(item, "reason"))) == null)
        }
        return PdfPageProposalReceipt(CommandId(text(root, "command")), ObjectFingerprint(text(root, "binding")),
            integer(root, "revision", 0, Long.MAX_VALUE), accepted, rejected,
            integer(root, "committed_at", 0, Long.MAX_VALUE))
    }

    private fun integer(json: JsonObject, key: String, minimum: Long, maximum: Long): Long {
        val raw = json[key] as? JsonPrimitive ?: throw IllegalArgumentException("Integer required")
        require(!raw.isString)
        return ToolArguments.long(ToolScalar.NumberToken(raw.content), key, minimum, maximum)
    }

    private fun text(json: JsonObject, key: String): String {
        val raw = json[key] as? JsonPrimitive ?: throw IllegalArgumentException("String required")
        require(raw.isString)
        return raw.content
    }
}
