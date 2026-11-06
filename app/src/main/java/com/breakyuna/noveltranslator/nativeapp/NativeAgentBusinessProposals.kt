package com.breakyuna.noveltranslator.nativeapp

import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Exact, private approval payload. APPLYING is never replayed after an interruption. */
internal data class NativeAgentBusinessProposal(val document: JsonObject) {
    val id get() = document.getValue("id").jsonPrimitive.content
    val digest = TextProjectFingerprint.bytes(document.toString().toByteArray(Charsets.UTF_8))
    val kind get() = document.getValue("kind").jsonPrimitive.content
    val payload get() = document.getValue("payload").jsonObject
    val status get() = document.getValue("status").jsonPrimitive.content
    fun boundTo(ref: LocalProjectRef, chat: ChatId): Boolean {
        val scope = document["binding"] as? JsonObject ?: return false
        return scope["project"] == JsonPrimitive(ref.projectId.value) && scope["instance"] == JsonPrimitive(ref.instanceId.value) &&
            scope["generation"] == JsonPrimitive(ref.generation.value) && scope["chat"] == JsonPrimitive(chat.value)
    }
}

internal class NativeAgentBusinessProposals(root: File) {
    private val mutex = Mutex()
    private val directory = root
    private fun binding(ref: LocalProjectRef, chat: ChatId) = buildJsonObject {
        put("project", ref.projectId.value); put("instance", ref.instanceId.value)
        put("generation", ref.generation.value); put("chat", chat.value)
    }
    private fun file(ref: LocalProjectRef, chat: ChatId): AtomicFile {
        val key = TextProjectFingerprint.bytes(binding(ref, chat).toString().toByteArray(Charsets.UTF_8))
        return AtomicFile(File(directory, "$key.json"))
    }
    private fun read(target: AtomicFile): NativeAgentBusinessProposal? {
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return null
        val bytes = target.openRead().use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 1024 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size <= 1024 * 1024)
        val proposal = NativeAgentBusinessProposal(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
        require(runCatching { UUID.fromString(proposal.id).toString() == proposal.id }.getOrDefault(false))
        val audit = AtomicFile(File(directory, proposal.id + ".json"))
        if (audit.baseFile != target.baseFile && (audit.baseFile.exists() || File(audit.baseFile.path + ".bak").exists())) {
            return read(audit)
        }
        return proposal
    }
    private fun write(target: AtomicFile, value: NativeAgentBusinessProposal) {
        require(directory.isDirectory || directory.mkdirs())
        val bytes = value.document.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1024 * 1024)
        val audit = AtomicFile(File(directory, value.id + ".json"))
        val auditStream = audit.startWrite()
        try { auditStream.write(bytes); audit.finishWrite(auditStream) }
        catch (failure: Throwable) { audit.failWrite(auditStream); throw failure }
        val stream = target.startWrite()
        try { stream.write(bytes); target.finishWrite(stream) }
        catch (failure: Throwable) { target.failWrite(stream); throw failure }
    }
    suspend fun pending(ref: LocalProjectRef, chat: ChatId): NativeAgentBusinessProposal? = withContext(Dispatchers.IO) {
        mutex.withLock { read(file(ref, chat))?.takeIf { it.status in setOf("PENDING", "APPLYING") } }
    }
    suspend fun prepare(ref: LocalProjectRef, run: RunIdentity, revision: Long, kind: String,
                        payload: JsonObject): NativeAgentBusinessProposal = withContext(Dispatchers.IO) {
        mutex.withLock {
            val chat = requireNotNull(run.chatId)
            require(run.projectId == ref.projectId)
            val target = file(ref, chat)
            require(read(target)?.status !in setOf("PENDING", "APPLYING"))
            NativeAgentBusinessProposal(buildJsonObject {
                put("id", UUID.randomUUID().toString()); put("binding", binding(ref, chat))
                put("run", run.runId.value); put("command", run.commandId.value)
                put("created_at", System.currentTimeMillis()); put("revision", revision); put("kind", kind); put("payload", payload); put("status", "PENDING")
            }).also { write(target, it) }
        }
    }
    suspend fun translationAuthorized(ref: LocalProjectRef, command: CommandId): Boolean = withContext(Dispatchers.IO) {
        val prefix = when {
            command.value.startsWith("agent-enqueue-") -> "agent-enqueue-"
            command.value.startsWith("agent-decision-") -> "agent-decision-"
            else -> return@withContext true
        }
        val id = command.value.removePrefix(prefix)
        require(UUID.fromString(id).toString() == id)
        mutex.withLock {
            val proposal = read(AtomicFile(File(directory, "$id.json"))) ?: return@withLock false
            val scope = proposal.document.getValue("binding").jsonObject
            proposal.kind == (if (prefix == "agent-enqueue-") "enqueue_translation" else "translation_failure_decision") &&
                proposal.status == "COMMITTED" &&
                scope["project"] == JsonPrimitive(ref.projectId.value) &&
                scope["instance"] == JsonPrimitive(ref.instanceId.value) &&
                scope["generation"] == JsonPrimitive(ref.generation.value)
        }
    }
    suspend fun recent(ref: LocalProjectRef, chat: ChatId): List<NativeAgentBusinessProposal> = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory.listFiles().orEmpty().filter { file ->
                file.name.endsWith(".json") && runCatching {
                    UUID.fromString(file.name.removeSuffix(".json")).toString() == file.name.removeSuffix(".json")
                }.getOrDefault(false)
            }.sortedByDescending { it.lastModified() }.take(1000).mapNotNull { source ->
                read(AtomicFile(source))?.takeIf { it.document["binding"] == binding(ref, chat) }
            }
        }
    }
    suspend fun status(ref: LocalProjectRef, chat: ChatId, id: String): NativeAgentBusinessProposal? = withContext(Dispatchers.IO) {
        require(UUID.fromString(id).toString() == id)
        mutex.withLock {
            read(AtomicFile(File(directory, "$id.json")))?.takeIf { it.document["binding"] == binding(ref, chat) }
        }
    }
    suspend fun transition(ref: LocalProjectRef, chat: ChatId, expected: NativeAgentBusinessProposal,
                           status: String, result: JsonObject? = null): NativeAgentBusinessProposal = withContext(Dispatchers.IO) {
        mutex.withLock {
            val target = file(ref, chat)
            val current = requireNotNull(read(target))
            require(current.digest == expected.digest && current.document["binding"] == binding(ref, chat))
            require(when (current.status) {
                "PENDING" -> status in setOf("APPLYING", "REJECTED")
                "APPLYING" -> status in setOf("COMMITTED", "NEEDS_REVIEW")
                else -> false
            })
            NativeAgentBusinessProposal(JsonObject(current.document + ("status" to JsonPrimitive(status)) +
                (result?.let { mapOf("result" to it) } ?: emptyMap())))
                .also { write(target, it) }
        }
    }
}
