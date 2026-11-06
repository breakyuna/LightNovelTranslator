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
    private val runtime: RuntimePort) : NativeAgentWorkspacePort, AgentPersistencePort, AgentConversationPort, AgentAutoContextPort, AgentLoopContextPort {
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
            if (current != null) require(!hasUnresolved(ref, current.id))
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
        db.agent().messagePreviews(ref.instanceId.value, ref.generation.value, chatId.value).asReversed()
            .map { AgentChatLine(it.id, it.role, it.preview) }
    }
    /** SQL projects only short previews; the complete payload is loaded by messageDetail. */
    suspend fun messagePage(ref: LocalProjectRef, chat: ChatId, cursor: AgentMessageCursor? = null): AgentMessagePage = io {
        projects.open(ref)
        require(cursor == null || cursor.ref == ref && cursor.chat == chat) { "Foreign history cursor" }
        val rows = db.agent().messagePage(ref.instanceId.value, ref.generation.value, chat.value,
            cursor?.beforeTime, cursor?.beforeId?.value)
        val visible = rows.take(30)
        AgentMessagePage(visible.asReversed().map { AgentChatLine(it.id, it.role, it.preview) },
            rows.takeIf { it.size > 30 }?.get(29)?.let { AgentMessageCursor(ref, chat, it.createdAt, MessageId(it.id)) })
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
            val previous = db.agent().lastMessageTime(ref.instanceId.value, ref.generation.value, chatId.value)
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
    /** Complete immutable trace for archive export; active-context limits do not truncate history. */
    suspend fun exportHistory(ref: LocalProjectRef, chat: ChatId): List<AgentMessage> = io {
        projects.open(ref)
        val result = ArrayList<AgentMessage>()
        val validator = AgentHistoryValidator()
        var time = -1L; var id = ""; var bytes = 0L; var count = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val rows = db.agent().archivePage(ref.instanceId.value, ref.generation.value, chat.value, time, id)
            if (rows.isEmpty()) break
            rows.forEach { row ->
                require(++count <= 100_000) { "Agent archive entry budget exceeded" }
                row.payload?.let { payload ->
                    bytes += TextProjectFingerprint.utf8(payload).size
                    require(bytes <= 64L * 1024 * 1024) { "Agent archive byte budget exceeded" }
                    val message = AgentMessageCodec.decode(payload)
                    validator.append(message); result.add(message)
                }
            }
            time = rows.last().createdAt; id = rows.last().id
        }
        validator.finish(allowPendingAtTail = true)
        result
    }

    suspend fun exportMessagePreviews(ref: LocalProjectRef, chat: ChatId): List<AgentChatLine> = io {
        projects.open(ref)
        val result = ArrayList<AgentChatLine>()
        var time = -1L; var id = ""; var chars = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val rows = db.agent().archivePage(ref.instanceId.value, ref.generation.value, chat.value, time, id)
            if (rows.isEmpty()) break
            rows.forEach { row ->
                val text = row.body.take(2000)
                chars += text.length
                require(result.size < 100_000 && chars <= 64L * 1024 * 1024) { "Agent archive budget exceeded" }
                result.add(AgentChatLine(row.id, row.role, text))
            }
            time = rows.last().createdAt; id = rows.last().id
        }
        result
    }

    private fun contextRows(ref: LocalProjectRef, chat: ChatId): Pair<NativeAgentContextRow?, List<NativeAgentMessageRow>> {
        val context = db.agent().context(ref.instanceId.value, ref.generation.value, chat.value)
        if (context != null) {
            require(context.summary.isNotBlank() && context.summary.length <= 16_384 && context.revision > 0)
            val boundary = requireNotNull(db.agent().message(context.boundaryId))
            require(boundary.instanceId == ref.instanceId.value && boundary.generation == ref.generation.value &&
                boundary.chatId == chat.value && boundary.createdAt == context.boundaryTime && boundary.payload != null)
        }
        val time = context?.boundaryTime ?: -1L
        val id = context?.boundaryId.orEmpty()
        if (db.agent().contextChars(ref.instanceId.value, ref.generation.value, chat.value, time, id) > 2L * 1024 * 1024)
            throw AgentModelFailure(ModelFailureKind.CONTEXT_OVERFLOW)
        val rows = db.agent().contextMessages(ref.instanceId.value, ref.generation.value, chat.value, time, id)
        if (rows.size > 256) throw AgentModelFailure(ModelFailureKind.CONTEXT_OVERFLOW)
        return context to rows
    }

    override suspend fun modelHistory(ref: LocalProjectRef, chat: ChatId): List<AgentMessage> = io {
        projects.open(ref)
        val (context, rows) = contextRows(ref, chat)
        val summary = context?.let { listOf(AgentMessage.System(MessageId("context-summary-" + it.revision),
            "User-maintained summary of earlier conversation; verify current project facts with tools.\n" + it.summary)) }.orEmpty()
        (summary + rows.map { AgentMessageCodec.decode(requireNotNull(it.payload)) }).also {
            AgentHistory.validate(it, allowPendingAtTail = true)
        }
    }

    override suspend fun saveLoopContext(lease: RunLease, ref: LocalProjectRef, chat: ChatId,
        expectedMessages: List<MessageId>, boundary: MessageId, summary: String): Unit = io {
        require(lease.identity.projectId == ref.projectId && lease.identity.chatId == chat)
        require(summary.isNotBlank() && summary.length <= 16_384)
        TextProjectFingerprint.utf8(summary)
        val write = runtime.beginRunMetadataWrite(lease)
        try {
            projects.open(ref)
            require(!hasUnresolved(ref, chat.value))
            transaction {
                val (old, rows) = contextRows(ref, chat)
                val ids = (old?.let { listOf(MessageId("context-summary-" + it.revision)) }.orEmpty() + rows.map { MessageId(it.id) })
                require(ids == expectedMessages) { "Agent context changed" }
                val index = rows.indexOfFirst { it.id == boundary.value }
                require(index >= 0 && index < rows.lastIndex)
                val archived = rows.take(index + 1).map { AgentMessageCodec.decode(requireNotNull(it.payload)) }
                val retained = rows.drop(index + 1).map { AgentMessageCodec.decode(requireNotNull(it.payload)) }
                AgentHistory.validate(archived)
                AgentHistory.validate(retained)
                val last = rows[index]
                db.agent().context(NativeAgentContextRow(ref.instanceId.value, ref.generation.value, chat.value,
                    last.id, last.createdAt, summary, contextFingerprint(old, rows).sha256,
                    Math.addExact(old?.revision ?: 0L, 1L)))
            }
        } finally { runtime.endProjectWrite(write) }
    }

    private fun contextFingerprint(context: NativeAgentContextRow?, rows: List<NativeAgentMessageRow>): ObjectFingerprint {
        val encoded = buildJsonObject {
            put("summary", context?.summary.orEmpty()); put("revision", context?.revision ?: 0)
            put("boundary", context?.boundaryId.orEmpty())
            put("messages", JsonArray(rows.map { JsonArray(listOf(JsonPrimitive(it.id), JsonPrimitive(it.createdAt), JsonPrimitive(it.payload))) }))
        }.toString()
        return ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(encoded)))
    }

    private fun contextPreview(ref: LocalProjectRef, chat: ChatId): Pair<AgentContextPreview, NativeAgentMessageRow> {
        val (context, rows) = contextRows(ref, chat)
        val cut = AgentContextCut.index(rows.map { AgentMessageCodec.decode(requireNotNull(it.payload)) })
        return AgentContextPreview(ref, chat, contextFingerprint(context, rows),
            context?.summary.orEmpty(), cut, rows.size - cut) to rows[cut - 1]
    }

    private fun autoContextPreview(ref: LocalProjectRef, chat: ChatId,
        retainUserTurns: Int): Pair<AgentAutoContextPreview, NativeAgentMessageRow> {
        require(retainUserTurns in 1..64)
        val (context, rows) = contextRows(ref, chat)
        val cut = requireNotNull(AgentCompactionPlanner.safeCut(
            rows.map { AgentMessageCodec.decode(requireNotNull(it.payload)) }, retainUserTurns)) { "No safe context cut" }
        val boundary = rows[cut.index - 1]
        require(boundary.id == cut.boundary.value) { "Context boundary mismatch" }
        val preview = AgentContextPreview(ref, chat, contextFingerprint(context, rows),
            context?.summary.orEmpty(), cut.archivedMessages, cut.retainedMessages)
        return AgentAutoContextPreview(preview, context?.revision ?: 0L, cut.boundary, retainUserTurns) to boundary
    }

    suspend fun prepareContext(ref: LocalProjectRef, chat: ChatId): AgentContextPreview = io {
        val write = runtime.beginProjectWrite()
        try { projects.open(ref); transaction { contextPreview(ref, chat).first } }
        finally { runtime.endProjectWrite(write) }
    }

    suspend fun compactContext(preview: AgentContextPreview, summary: String): Unit = io {
        require(summary.isNotBlank() && summary.length <= 16_384)
        TextProjectFingerprint.utf8(summary)
        val write = runtime.beginProjectWrite()
        try {
            projects.open(preview.ref)
            require(!hasUnresolved(preview.ref, preview.chat.value))
            transaction {
                val (current, boundary) = contextPreview(preview.ref, preview.chat)
                require(current == preview) { "Conversation changed" }
                val old = db.agent().context(preview.ref.instanceId.value, preview.ref.generation.value, preview.chat.value)
                db.agent().context(NativeAgentContextRow(preview.ref.instanceId.value, preview.ref.generation.value,
                    preview.chat.value, boundary.id, boundary.createdAt, summary, preview.fingerprint.sha256,
                    Math.addExact(old?.revision ?: 0, 1L)))
            }
        } finally { runtime.endProjectWrite(write) }
    }

    override suspend fun prepareAutoContext(ref: LocalProjectRef, chat: ChatId,
        retainUserTurns: Int): AgentAutoContextPreview = io {
        val write = runtime.beginProjectWrite()
        try { projects.open(ref); transaction { autoContextPreview(ref, chat, retainUserTurns).first } }
        finally { runtime.endProjectWrite(write) }
    }

    override suspend fun saveAutoContext(preview: AgentAutoContextPreview, summary: String): Unit = io {
        require(summary.isNotBlank() && summary.length <= 16_384)
        TextProjectFingerprint.utf8(summary)
        val ref = preview.context.ref
        val write = runtime.beginProjectWrite()
        try {
            projects.open(ref)
            require(!hasUnresolved(ref, preview.context.chat.value))
            transaction {
                val (current, boundary) = autoContextPreview(ref, preview.context.chat, preview.retainUserTurns)
                require(current == preview) { "Conversation changed" }
                val old = db.agent().context(ref.instanceId.value, ref.generation.value, preview.context.chat.value)
                require((old?.revision ?: 0L) == preview.revision) { "Context revision changed" }
                db.agent().context(NativeAgentContextRow(ref.instanceId.value, ref.generation.value,
                    preview.context.chat.value, boundary.id, boundary.createdAt, summary,
                    preview.context.fingerprint.sha256, Math.addExact(old?.revision ?: 0L, 1L)))
            }
        } finally { runtime.endProjectWrite(write) }
    }

    private class BranchSource(val target: NativeAgentMessageRow, val context: NativeAgentContextRow?)

    private fun visitBranch(ref: LocalProjectRef, chat: ChatId, target: NativeAgentMessageRow,
        checkActive: () -> Unit, visit: (NativeAgentMessageRow) -> Unit) {
        var time = -1L
        var id = ""
        var count = 0
        while (true) {
            checkActive()
            val rows = db.agent().branchPage(ref.instanceId.value, ref.generation.value, chat.value,
                time, id, target.createdAt, target.id)
            if (rows.isEmpty()) break
            rows.forEach { row ->
                checkActive()
                require(++count <= 10_000) { "Branch history exceeds message budget" }
                require(row.createdAt > time || row.createdAt == time && row.id > id)
                visit(row)
                time = row.createdAt; id = row.id
            }
        }
        require(id == target.id && time == target.createdAt)
    }

    private fun branchPreview(ref: LocalProjectRef, chat: ChatId, message: MessageId, checkActive: () -> Unit): Pair<AgentBranchPreview, BranchSource> {
        val target = requireNotNull(db.agent().message(message.value))
        require(target.instanceId == ref.instanceId.value && target.generation == ref.generation.value && target.chatId == chat.value)
        require(db.agent().branchChars(ref.instanceId.value, ref.generation.value, chat.value, target.createdAt, target.id) <= 32L * 1024 * 1024)
        val context = db.agent().context(ref.instanceId.value, ref.generation.value, chat.value)?.takeIf {
            it.boundaryTime < target.createdAt || it.boundaryTime == target.createdAt && it.boundaryId < target.id
        }
        context?.let { require(it.summary.isNotBlank() && it.summary.length <= 16_384 && it.revision > 0) }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun hash(text: String) {
            val bytes = TextProjectFingerprint.utf8(text)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        hash(ref.projectId.value); hash(ref.instanceId.value); hash(ref.generation.value.toString()); hash(chat.value)
        hash(db.agent().lastMessageTime(ref.instanceId.value, ref.generation.value, chat.value).toString())
        hash(context?.summary.orEmpty()); hash(context?.revision?.toString().orEmpty()); hash(context?.boundaryId.orEmpty())
        val validator = AgentHistoryValidator()
        val tailValidator = AgentHistoryValidator()
        var count = 0
        var archived = 0
        var activeBytes = 0L
        var selected: AgentMessage? = null
        visitBranch(ref, chat, target, checkActive) { row ->
            val payload = requireNotNull(row.payload)
            require(payload.length <= 512 * 1024)
            val decoded = AgentMessageCodec.decode(payload)
            validator.append(decoded)
            hash(row.id); hash(row.createdAt.toString()); hash(payload)
            count++
            if (context == null || archived > 0) {
                tailValidator.append(decoded)
                activeBytes += TextProjectFingerprint.utf8(payload).size
            }
            if (context != null && row.id == context.boundaryId) {
                require(row.createdAt == context.boundaryTime)
                validator.finish()
                archived = count
            }
            selected = decoded
        }
        validator.finish(); tailValidator.finish()
        require(context == null || archived > 0) { "Missing inherited context boundary" }
        require(count - archived <= 256 && activeBytes <= 2L * 1024 * 1024) { "Branch requires a bounded model context" }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return AgentBranchPreview(ref, chat, message, ObjectFingerprint(fingerprint), count,
            AgentBranchHistory.editable(requireNotNull(selected))) to BranchSource(target, context)
    }

    suspend fun prepareBranchForAgent(lease: RunLease, ref: LocalProjectRef, chat: ChatId,
        message: MessageId): AgentBranchPreview = io {
        require(lease.identity.projectId == ref.projectId && lease.identity.chatId == chat)
        val write = runtime.beginRunMetadataWrite(lease)
        val coroutine = currentCoroutineContext()
        try { projects.open(ref); transaction { branchPreview(ref, chat, message) { coroutine.ensureActive() }.first } }
        finally { runtime.endProjectWrite(write) }
    }

    suspend fun contextSummary(ref: LocalProjectRef, chat: ChatId): String = io {
        projects.open(ref)
        db.agent().context(ref.instanceId.value, ref.generation.value, chat.value)?.summary.orEmpty()
            .also { require(it.length <= 16_384) }
    }

    suspend fun saveReviewedContext(preview: AgentBranchPreview, summary: String): Unit = io {
        require(summary.isNotBlank() && summary.length <= 16_384)
        TextProjectFingerprint.utf8(summary)
        val ref = preview.ref
        val write = runtime.beginProjectWrite()
        val coroutine = currentCoroutineContext()
        try {
            projects.open(ref)
            require(!hasUnresolved(ref, preview.chat.value))
            transaction {
                val (current, source) = branchPreview(ref, preview.chat, preview.message) { coroutine.ensureActive() }
                require(current == preview) { "Archived prefix changed" }
                val old = db.agent().context(ref.instanceId.value, ref.generation.value, preview.chat.value)
                val boundary = source.target
                require(old == null || boundary.createdAt > old.boundaryTime ||
                    boundary.createdAt == old.boundaryTime && boundary.id > old.boundaryId)
                val rows = db.agent().contextMessages(ref.instanceId.value, ref.generation.value, preview.chat.value, boundary.createdAt, boundary.id)
                require(rows.isNotEmpty() && rows.size <= 256)
                AgentHistory.validate(rows.map { AgentMessageCodec.decode(requireNotNull(it.payload)) })
                require(db.agent().contextChars(ref.instanceId.value, ref.generation.value, preview.chat.value,
                    boundary.createdAt, boundary.id) <= 2L * 1024 * 1024)
                db.agent().context(NativeAgentContextRow(ref.instanceId.value, ref.generation.value, preview.chat.value,
                    boundary.id, boundary.createdAt, summary, preview.fingerprint.sha256, Math.addExact(old?.revision ?: 0L, 1L)))
            }
        } finally { runtime.endProjectWrite(write) }
    }

    suspend fun prepareBranch(ref: LocalProjectRef, chat: ChatId, message: MessageId): AgentBranchPreview = io {
        val write = runtime.beginProjectWrite()
        val coroutine = currentCoroutineContext()
        try { projects.open(ref); transaction { branchPreview(ref, chat, message) { coroutine.ensureActive() }.first } }
        finally { runtime.endProjectWrite(write) }
    }

    suspend fun createBranch(preview: AgentBranchPreview, command: CommandId, replacement: String?): ChatId = io {
        replacement?.let { require(it.isNotBlank() && it.length <= 8000); TextProjectFingerprint.utf8(it) }
        val digest = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(JsonArray(listOf(
            JsonPrimitive(preview.ref.projectId.value), JsonPrimitive(preview.ref.instanceId.value),
            JsonPrimitive(preview.ref.generation.value), JsonPrimitive(preview.chat.value), JsonPrimitive(preview.message.value),
            JsonPrimitive(preview.fingerprint.sha256), replacement?.let(::JsonPrimitive) ?: JsonNull)).toString()))
        val write = runtime.beginProjectWrite()
        val coroutine = currentCoroutineContext()
        try {
            projects.open(preview.ref)
            transaction {
                db.agent().branch(command.value)?.let { receipt ->
                    require(receipt.digest == digest && receipt.sourceChat == preview.chat.value && receipt.message == preview.message.value)
                    return@transaction ChatId(receipt.targetChat)
                }
                val (current, source) = branchPreview(preview.ref, preview.chat, preview.message) { coroutine.ensureActive() }
                require(current == preview) { "Conversation changed" }
                require(!hasUnresolved(preview.ref, preview.chat.value))
                db.agent().currentChat(preview.ref.instanceId.value, preview.ref.generation.value)?.let { selected ->
                    require(!hasUnresolved(preview.ref, selected.id))
                }
                require(replacement == null || preview.editable)
                val targetChat = createChat(preview.ref)
                val baseTime = now()
                var index = 0L
                var inheritedBoundary: Pair<String, Long>? = null
                visitBranch(preview.ref, preview.chat, source.target, { coroutine.ensureActive() }) { row ->
                    val message = AgentBranchHistory.copy(AgentMessageCodec.decode(requireNotNull(row.payload)), MessageId(uuid()),
                        replacement.takeIf { row.id == source.target.id })
                    val payload = AgentMessageCodec.encode(message)
                    require(payload.length <= 512 * 1024)
                    val body = if (row.id == source.target.id && replacement != null) replacement.take(2000) else row.body.take(2000)
                    val timestamp = Math.addExact(baseTime, index++)
                    db.agent().insertMessage(row.copy(id = message.id.value, chatId = targetChat.value,
                        createdAt = timestamp, body = body, payload = payload))
                    if (row.id == source.context?.boundaryId) inheritedBoundary = message.id.value to timestamp
                }
                source.context?.let { context ->
                    val boundary = requireNotNull(inheritedBoundary)
                    db.agent().context(NativeAgentContextRow(preview.ref.instanceId.value, preview.ref.generation.value,
                        targetChat.value, boundary.first, boundary.second, context.summary, preview.fingerprint.sha256, 1L))
                }
                db.agent().branch(NativeAgentBranchRow(command.value, preview.chat.value, targetChat.value,
                    preview.message.value, digest))
                targetChat
            }
        } finally { runtime.endProjectWrite(write) }
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
                val context = db.agent().context(ref.instanceId.value, ref.generation.value, chat.value)
                val time = context?.boundaryTime ?: -1L
                val boundary = context?.boundaryId.orEmpty()
                if (db.agent().contextCount(ref.instanceId.value, ref.generation.value, chat.value, time, boundary) >= 256)
                    throw AgentModelFailure(ModelFailureKind.CONTEXT_OVERFLOW)
                if (db.agent().contextChars(ref.instanceId.value, ref.generation.value, chat.value, time, boundary) + TextProjectFingerprint.utf8(payload).size > 2L * 1024 * 1024)
                    throw AgentModelFailure(ModelFailureKind.CONTEXT_OVERFLOW)
                val previous = db.agent().lastMessageTime(ref.instanceId.value, ref.generation.value, chat.value)
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
    override suspend fun hasPending(ref: LocalProjectRef, chatId: ChatId): Boolean = io {
        projects.open(ref)
        hasUnresolved(ref, chatId.value)
    }
    private fun hasUnresolved(ref: LocalProjectRef, chat: String): Boolean =
        db.agent().unresolved(ref.instanceId.value, ref.generation.value, chat) != null ||
            db.itemProposals().unresolved(ref.instanceId.value, ref.generation.value, chat).isNotEmpty() ||
            db.policyProposals().unresolved(ref.instanceId.value, ref.generation.value, chat).isNotEmpty() ||
            db.pdfPageProposals().unresolved(ref.instanceId.value, ref.generation.value, chat).isNotEmpty() ||
            db.assetImportProposals().unresolved(ref.instanceId.value, ref.generation.value, chat).isNotEmpty()
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
                check(!hasUnresolved(ref, chat.value)) { "Approval already pending" }
                db.agent().insertApproval(frozen)
            }
            verified(frozen, ref)
        } finally { runtime.endOperation(operation) }
    }
    override suspend fun cancelPending(ref: LocalProjectRef, runId: RunId): Unit = io {
        transaction {
            db.agent().cancelPending(ref.instanceId.value, ref.generation.value, runId.value)
            db.itemProposals().cancel(ref.instanceId.value, ref.generation.value, runId.value)
            db.policyProposals().cancel(ref.instanceId.value, ref.generation.value, runId.value)
            db.pdfPageProposals().cancel(ref.instanceId.value, ref.generation.value, runId.value)
            db.assetImportProposals().cancelUnclaimed(ref.instanceId.value, ref.generation.value, runId.value,
                System.currentTimeMillis().coerceAtLeast(0))
        }
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
                NativeUsageLedgerStore.reserveAgent(db, request)
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
        transaction {
            val dispatched = db.agent().dispatch(key.attemptId.value, requestKey(key)) == 1
            if (dispatched) NativeUsageLedgerStore.dispatchedAgent(db, key.attemptId.value)
            dispatched
        }
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
            NativeUsageLedgerStore.received(db, "agent", key.attemptId.value, response.usage, response.stopReason)
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
