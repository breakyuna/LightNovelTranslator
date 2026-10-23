package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import java.util.concurrent.Callable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Device-local Room authority. No imported .lnt file can supply these execution records. */
class NativeAgentStore(context: Context, private val projects: NativeTextProjectStore,
    private val runtime: RuntimePort) : NativeAgentWorkspacePort, AgentPersistencePort, AgentConversationPort {
    private val db = NativeAppDatabase.open(context)
    private val mutex = Mutex()
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { currentCoroutineContext().ensureActive(); block() }
    }
    private fun <T> transaction(block: () -> T): T = db.runInTransaction(Callable { block() })
    private fun now() = System.currentTimeMillis().coerceAtLeast(0)
    private fun uuid() = UUID.randomUUID().toString()

    override suspend fun chats(ref: LocalProjectRef): List<ChatId> = io {
        projects.open(ref)
        db.agent().chats(ref.instanceId.value, ref.generation.value).map { ChatId(it.id) }
    }
    override suspend fun selectChat(ref: LocalProjectRef, chat: ChatId): Unit = io {
        val write = runtime.beginProjectWrite()
        try {
            projects.open(ref)
            transaction {
                val target = requireNotNull(db.agent().chat(chat.value))
                require(target.instanceId == ref.instanceId.value && target.generation == ref.generation.value)
                db.agent().deselectChats(ref.instanceId.value, ref.generation.value)
                check(db.agent().selectChat(chat.value) == 1)
            }
        } finally { runtime.endProjectWrite(write) }
    }
    override suspend fun currentChat(ref: LocalProjectRef): ChatId = io {
        projects.open(ref)
        transaction {
            db.agent().currentChat(ref.instanceId.value, ref.generation.value)?.let { ChatId(it.id) } ?: createChat(ref)
        }
    }
    override suspend fun newChat(ref: LocalProjectRef): ChatId = io {
        val write = runtime.beginProjectWrite()
        try {
            projects.open(ref)
            val current = db.agent().currentChat(ref.instanceId.value, ref.generation.value)
            if (current != null) require(db.agent().unresolved(ref.instanceId.value, ref.generation.value, current.id) == null)
            createChat(ref)
        } finally { runtime.endProjectWrite(write) }
    }
    private fun createChat(ref: LocalProjectRef): ChatId = transaction {
        require(db.agent().chatCount(ref.instanceId.value, ref.generation.value) < 50) { "Chat budget exceeded" }
        val previous = db.agent().chats(ref.instanceId.value, ref.generation.value).firstOrNull()?.createdAt ?: 0
        val row = NativeAgentChatRow(uuid(), ref.instanceId.value, ref.generation.value, maxOf(now(), Math.addExact(previous, 1)), true)
        db.agent().deselectChats(ref.instanceId.value, ref.generation.value)
        db.agent().insertChat(row); ChatId(row.id)
    }

    override suspend fun results(ref: LocalProjectRef, chatId: ChatId): List<AgentApprovalResult> = io {
        projects.open(ref)
        db.agent().results(ref.instanceId.value, ref.generation.value, chatId.value)
            .map { AgentApprovalResult(it.id, it.rowIndex, TextApprovalState.valueOf(it.state)) }
    }
    override suspend fun messages(ref: LocalProjectRef, chatId: ChatId): List<AgentChatLine> = io {
        projects.open(ref)
        db.agent().messages(ref.instanceId.value, ref.generation.value, chatId.value).asReversed()
            .map { AgentChatLine(it.id, it.role, it.body.take(2000)) }
    }
    suspend fun messageDetail(ref: LocalProjectRef, chat: ChatId, id: String): AgentMessageDetail = io {
        projects.open(ref)
        val row = requireNotNull(db.agent().message(id))
        require(row.instanceId == ref.instanceId.value && row.generation == ref.generation.value && row.chatId == chat.value)
        val payload = row.payload ?: return@io AgentMessageDetail(row.id, row.role, row.body)
        require(payload.length <= 512 * 1024)
        val pretty = Json { prettyPrint = true }
        fun format(value: JsonObjectValue) = pretty.encodeToString(JsonElement.serializer(), value.json)
        when (val message = AgentMessageCodec.decode(payload)) {
            is AgentMessage.User -> AgentMessageDetail(row.id, row.role, message.parts.filterIsInstance<UserPart.Text>().joinToString("\n") { it.text })
            is AgentMessage.Assistant -> AgentMessageDetail(row.id, row.role,
                message.parts.filterIsInstance<AssistantPart.Text>().joinToString("\n") { it.text },
                message.parts.filterIsInstance<AssistantPart.Thinking>().mapNotNull { it.text }.joinToString("\n"),
                message.parts.filterIsInstance<AssistantPart.ToolCall>().joinToString("\n\n") { it.call.name + "\n" + format(it.call.arguments) })
            is AgentMessage.ToolResult -> AgentMessageDetail(row.id, row.role, message.toolName + "\n" + format(message.result))
            is AgentMessage.System -> AgentMessageDetail(row.id, row.role, message.text)
        }
    }
    override suspend fun append(ref: LocalProjectRef, chatId: ChatId, role: String, text: String): Unit = io {
        require(role in setOf("USER", "ASSISTANT", "SYSTEM") && text.length <= 65_536)
        TextProjectFingerprint.utf8(text); projects.open(ref)
        transaction {
            val previous = db.agent().messages(ref.instanceId.value, ref.generation.value, chatId.value).firstOrNull()?.createdAt ?: 0
            db.agent().insertMessage(NativeAgentMessageRow(uuid(), ref.instanceId.value, ref.generation.value,
                chatId.value, role, text, maxOf(now(), Math.addExact(previous, 1))))
        }
    }
    override suspend fun history(ref: LocalProjectRef, chat: ChatId): List<AgentMessage> = io {
        projects.open(ref)
        if (db.agent().historyChars(ref.instanceId.value, ref.generation.value, chat.value) > 2L * 1024 * 1024)
            throw AgentModelFailure(ModelFailureKind.CONTEXT_OVERFLOW)
        val rows = db.agent().history(ref.instanceId.value, ref.generation.value, chat.value)
        if (rows.size > 256) throw AgentModelFailure(ModelFailureKind.CONTEXT_OVERFLOW)
        if (rows.sumOf { it.payload!!.length.toLong() } > 2L * 1024 * 1024)
            throw AgentModelFailure(ModelFailureKind.CONTEXT_OVERFLOW)
        rows.map { AgentMessageCodec.decode(requireNotNull(it.payload)) }.also { AgentHistory.validate(it, allowPendingAtTail = true) }
    }
    override suspend fun appendMessage(ref: LocalProjectRef, chat: ChatId, message: AgentMessage): Unit = io {
        projects.open(ref)
        require(message !is AgentMessage.System) { "System context is owned by the host" }
        val payload = AgentMessageCodec.encode(message)
        if (payload.length > 512 * 1024) throw AgentModelFailure(ModelFailureKind.CONTEXT_OVERFLOW)
        val preview = when (message) {
            is AgentMessage.User -> message.parts.filterIsInstance<UserPart.Text>().joinToString("\n") { it.text }
            is AgentMessage.Assistant -> message.parts.filterIsInstance<AssistantPart.Text>().joinToString("\n") { it.text }
            is AgentMessage.ToolResult -> message.toolName + if (message.isError) " : error" else " : complete"
            is AgentMessage.System -> error("System context cannot be appended")
        }.take(2000)
        transaction {
            val existing = db.agent().message(message.id.value)
            if (existing != null) {
                require(existing.instanceId == ref.instanceId.value && existing.generation == ref.generation.value &&
                    existing.chatId == chat.value && existing.payload == payload)
            } else {
                if (db.agent().historyCount(ref.instanceId.value, ref.generation.value, chat.value) >= 256)
                    throw AgentModelFailure(ModelFailureKind.CONTEXT_OVERFLOW)
                if (db.agent().historyChars(ref.instanceId.value, ref.generation.value, chat.value) + TextProjectFingerprint.utf8(payload).size > 2L * 1024 * 1024)
                    throw AgentModelFailure(ModelFailureKind.CONTEXT_OVERFLOW)
                val previous = db.agent().messages(ref.instanceId.value, ref.generation.value, chat.value).firstOrNull()?.createdAt ?: 0
                db.agent().insertMessage(NativeAgentMessageRow(message.id.value, ref.instanceId.value, ref.generation.value,
                    chat.value, when (message) { is AgentMessage.User -> "USER"; is AgentMessage.Assistant -> "ASSISTANT"; else -> "TOOL" },
                    preview, maxOf(now(), Math.addExact(previous, 1)), payload))
            }
        }
    }

    override suspend fun pending(ref: LocalProjectRef, chatId: ChatId): TextApproval? = io {
        projects.open(ref)
        db.agent().unresolved(ref.instanceId.value, ref.generation.value, chatId.value)?.let { verified(it, ref) }
    }
    override suspend fun prepare(lease: RunLease, ref: LocalProjectRef, itemId: ItemId, translation: String): TextApproval = io {
        require(translation.length <= 65_536); TextProjectFingerprint.utf8(translation)
        val chat = requireNotNull(lease.identity.chatId)
        require(lease.identity.projectId == ref.projectId && runtime.snapshot().activeRun?.owner == RuntimeOwner.AGENT)
        val operation = runtime.beginOperation(lease, OperationKind.TOOL)
        try {
            val item = projects.item(ref, itemId) ?: throw TextProjectException(TextProjectException.Reason.UNKNOWN_PROJECT)
            val row = NativeAgentApprovalRow(uuid(), ref.instanceId.value, ref.generation.value, ref.projectId.value,
                chat.value, lease.identity.runId.value, uuid(), "", itemId.value, item.revision, item.item.fingerprint.sha256,
                item.item.row, item.item.status.name, item.item.translation, translation, "PENDING", now())
            val frozen = row.copy(digest = digest(row))
            currentCoroutineContext().ensureActive()
            transaction {
                check(db.agent().unresolved(ref.instanceId.value, ref.generation.value, chat.value) == null) { "Approval already pending" }
                db.agent().insertApproval(frozen)
            }
            verified(frozen, ref)
        } finally { runtime.endOperation(operation) }
    }
    override suspend fun cancelPending(ref: LocalProjectRef, runId: RunId): Unit = io {
        db.agent().cancelPending(ref.instanceId.value, ref.generation.value, runId.value)
        Unit
    }
    override suspend fun reject(ref: LocalProjectRef, id: ApprovalRequestId, digest: String): Unit = io {
        val row = requireNotNull(db.agent().approval(id.value)); val approval = verified(row, ref)
        require(approval.digest == digest)
        transaction {
            if (row.state == "PENDING") check(db.agent().settle(row.id, "PENDING", "REJECTED") == 1)
            else require(row.state == "REJECTED")
        }
    }
    override suspend fun apply(lease: RunLease, ref: LocalProjectRef, id: ApprovalRequestId, digest: String): TextApprovalState = io {
        var row = requireNotNull(db.agent().approval(id.value))
        val approval = verified(row, ref)
        require(approval.digest == digest && lease.identity.projectId == ref.projectId &&
            lease.identity.chatId == approval.chatId && lease.identity.runId == approval.runId &&
            lease.identity.commandId == approval.commandId && runtime.snapshot().activeRun?.owner == RuntimeOwner.AGENT)
        // A real opaque handle is required even for receipt replay or approval consumption.
        val operation = runtime.beginOperation(lease, OperationKind.TOOL)
        try {
            projects.open(ref)
            if (row.state in setOf("COMMITTED", "CONFLICT", "REJECTED", "CANCELLED")) return@io TextApprovalState.valueOf(row.state)
            currentCoroutineContext().ensureActive()
            val newlyConsumed = row.state == "PENDING"
            if (newlyConsumed) {
                // Approval and its immutable command/body binding are in this same Room row/transaction.
                transaction { check(db.agent().settle(row.id, "PENDING", "CONSUMED") == 1) }
                row = row.copy(state = "CONSUMED")
            }
            // A prior process may have committed the project before Room could record completion.
            val existing = projects.approvedReceipt(intent(approval))
            if (!newlyConsumed && existing == null) {
                transaction { db.agent().settle(row.id, "CONSUMED", "CANCELLED") }
                return@io TextApprovalState.CANCELLED
            }
            val receipt = existing ?: projects.approvedEdit(lease, ManualTextEdit(approval.commandId, ref,
                approval.itemId, approval.revision, approval.base, approval.after))
            val terminal = if (receipt.status == ManualEditStatus.COMMITTED) "COMMITTED" else "CONFLICT"
            withContext(NonCancellable) { transaction { db.agent().settle(row.id, "CONSUMED", terminal) } }
            TextApprovalState.valueOf(terminal)
        } catch (failure: Exception) {
            // No automatic replay after Stop or a failed executor. A known commit remains authoritative.
            withContext(NonCancellable) {
                try {
                    val receipt = projects.approvedReceipt(intent(approval))
                    val terminal = if (receipt?.status == ManualEditStatus.COMMITTED) "COMMITTED"
                        else if (receipt != null) "CONFLICT" else "CANCELLED"
                    transaction { db.agent().settle(row.id, "CONSUMED", terminal) }
                } catch (_: Exception) { /* Keep CONSUMED as unresolved if receipt cannot be read. */ }
            }
            throw failure
        } finally { runtime.endOperation(operation) }
    }
    override suspend fun reconcile(ref: LocalProjectRef, chatId: ChatId): Unit = io {
        // A real reservation prevents another executor starting between receipt check and settlement.
        if (runtime.snapshot().activeRun != null) return@io
        val write = runtime.beginProjectWrite()
        try {
            val row = db.agent().unresolved(ref.instanceId.value, ref.generation.value, chatId.value) ?: return@io
            val approval = verified(row, ref)
            if (approval.state == TextApprovalState.CONSUMED) {
                val receipt = projects.approvedReceipt(intent(approval))
                val terminal = if (receipt?.status == ManualEditStatus.COMMITTED) "COMMITTED"
                    else if (receipt != null) "CONFLICT" else "CANCELLED"
                transaction { db.agent().settle(row.id, "CONSUMED", terminal) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not settle or replay when the immutable approval or receipt cannot be verified.
            throw AgentApprovalUnavailable()
        } finally { runtime.endProjectWrite(write) }
    }
    suspend fun close(): Unit = io { db.close() }
    private fun intent(approval: TextApproval) = ManualTextEdit(approval.commandId, approval.ref,
        approval.itemId, approval.revision, approval.base, approval.after)
    private fun verified(row: NativeAgentApprovalRow, ref: LocalProjectRef): TextApproval {
        require(row.instanceId == ref.instanceId.value && row.generation == ref.generation.value && row.projectId == ref.projectId.value)
        require(row.digest == digest(row)) { "Approval payload mismatch" }
        return TextApproval(ApprovalRequestId(row.id), ref, ChatId(row.chatId), RunId(row.runId), CommandId(row.commandId),
            row.digest, ItemId(row.itemId), row.revision, ObjectFingerprint(row.base), row.rowIndex, TextItemStatus.valueOf(row.beforeStatus), row.beforeText, row.afterText, TextApprovalState.valueOf(row.state))
    }
    private fun digest(row: NativeAgentApprovalRow): String = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(buildJsonObject {
        put("version", 1); put("approval", row.id); put("instance", row.instanceId); put("generation", row.generation)
        put("project", row.projectId); put("chat", row.chatId); put("run", row.runId); put("command", row.commandId)
        put("item", row.itemId); put("revision", row.revision); put("base", row.base)
        put("row", row.rowIndex); put("before_status", row.beforeStatus); put("after_status", "PROCESSED")
        put("before", row.beforeText); put("after", row.afterText); put("policy", "REJECT_BATCH")
    }.toString()))

    override suspend fun reserveRequest(request: AgentModelRequest): RequestReservation = io {
        val digest = AgentRequestFingerprint.sha256(request)
        transaction {
            val row = db.agent().request(request.attemptId.value)
            if (row == null) {
                db.agent().insertRequest(NativeAgentRequestRow(request.attemptId.value, requestKey(request.key()), digest, "PREPARED", null, null, null))
                RequestReservation.Prepared
            } else if (row.digest != digest || row.requestKey != requestKey(request.key())) RequestReservation.Conflict
            else when (row.state) {
                "PREPARED" -> RequestReservation.Prepared
                "COMPLETED" -> RequestReservation.Completed(decodeResponse(row))
                else -> RequestReservation.Uncertain
            }
        }
    }
    override suspend fun markDispatched(key: AgentRequestKey): Boolean = io {
        db.agent().dispatch(key.attemptId.value, requestKey(key)) == 1
    }
    override suspend fun completeRequest(key: AgentRequestKey, response: AgentModelResponse): Unit = io {
        val text = buildJsonObject {
            put("version", 1); put("stop", response.stopReason.name)
            put("message", Json.parseToJsonElement(AgentMessageCodec.encode(response.message)))
        }.toString()
        require(text.length <= 2 * 1024 * 1024)
        val usage = encodeUsage(response.usage)
        transaction {
            val row = requireNotNull(db.agent().request(key.attemptId.value))
            require(row.requestKey == requestKey(key))
            if (row.state == "COMPLETED") {
                require(row.responseText == text && row.responseId == response.message.id.value && row.usage == usage)
            } else check(db.agent().complete(key.attemptId.value, requestKey(key), text, response.message.id.value, usage) == 1)
        }
    }
    private fun requestKey(key: AgentRequestKey): String = AgentRequestFingerprint.key(key)
    private fun encodeUsage(usage: RequestUsage) = JsonArray(listOf(usage.input, usage.cacheRead, usage.cacheWrite, usage.thinking, usage.output).map {
        buildJsonObject { put("tokens", it.tokens?.let(::JsonPrimitive) ?: JsonNull); put("source", it.source.name) }
    }).toString()
    private fun decodeResponse(row: NativeAgentRequestRow): AgentModelResponse {
        val values = Json.parseToJsonElement(requireNotNull(row.usage)).jsonArray.map {
            TokenMeasurement(it.jsonObject.getValue("tokens").jsonPrimitive.longOrNull,
                UsageSource.valueOf(it.jsonObject.getValue("source").jsonPrimitive.content))
        }
        require(values.size == 5)
        val envelope = JsonObjectValue.parse(requireNotNull(row.responseText), JsonInputLimits(2 * 1024 * 1024, 64)).json
        require(envelope.getValue("version").jsonPrimitive.content == "1")
        val message = AgentMessageCodec.decode(envelope.getValue("message").toString()) as AgentMessage.Assistant
        require(message.id.value == row.responseId)
        return AgentModelResponse(message, RequestUsage(values[0], values[1], values[2], values[3], values[4]),
            ModelStopReason.valueOf(envelope.getValue("stop").jsonPrimitive.content))
    }
}
