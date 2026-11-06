package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Private, bounded file storage for Agent checkpoints. One AtomicFile per checkpoint under
 * instance/generation/chat/branch partitions. A missing partition means "no checkpoint"; an
 * unreadable, oversized, unknown-version or identity-mismatched file is an explicit error rather
 * than an empty history. This store is read-only evidence for recovery; it never replays work.
 */
class NativeAgentCheckpointStore(context: Context) : AgentCheckpointPort {
    private val root = File(context.applicationContext.filesDir, DIRECTORY)
    private val mutex = storeMutex

    override suspend fun save(envelope: CheckpointEnvelope): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ref = requireNotNull(envelope.projectRef) {
                "A durable Agent checkpoint requires a project instance/generation scope"
            }
            val chat = requireNotNull(envelope.run.chatId) { "Agent checkpoints belong to a Chat" }
            require(envelope.run.projectId == ref.projectId) { "Checkpoint scope belongs to another project" }
            val directory = checkedPartition(ref, chat, envelope.branchId)
            val bytes = TextProjectFingerprint.utf8(AgentCheckpointCodec.encode(envelope))
            if (bytes.size > MAX_FILE_BYTES) throw AgentCheckpointLimitException("Agent checkpoint exceeds the file size limit")
            require(root.isDirectory || root.mkdirs()) { "Agent checkpoint directory unavailable" }
            require(directory.isDirectory || directory.mkdirs()) { "Agent checkpoint partition unavailable" }
            val existing = checkpointFiles(directory)
            if (existing.size > MAX_CHECKPOINTS_PER_PARTITION) {
                throw AgentCheckpointLimitException("Agent checkpoint partition exceeds its file count limit")
            }
            if (existing.size == MAX_CHECKPOINTS_PER_PARTITION) {
                currentCoroutineContext().ensureActive()
                prune(directory, existing.drop(MAX_CHECKPOINTS_PER_PARTITION - 1))
            }
            currentCoroutineContext().ensureActive()
            val source = File(directory, checkpointName(nextStamp(existing)))
            val atomic = AtomicFile(source)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                currentCoroutineContext().ensureActive()
                atomic.finishWrite(output)
            } catch (failure: Throwable) {
                atomic.failWrite(output)
                throw failure
            }
            currentCoroutineContext().ensureActive()
            enforceGlobalRetention(source)
        }
    }

    override suspend fun latest(ref: LocalProjectRef, chat: ChatId, branch: BranchId): CheckpointEnvelope? =
        withContext(Dispatchers.IO) {
            mutex.withLock { readPartition(ref, chat, branch, 1).firstOrNull() }
        }

    override suspend fun list(ref: LocalProjectRef, chat: ChatId, branch: BranchId, limit: Int): List<CheckpointEnvelope> {
        require(limit in 1..MAX_LIST_LIMIT) { "Invalid Agent checkpoint page size" }
        return withContext(Dispatchers.IO) {
            mutex.withLock { readPartition(ref, chat, branch, limit) }
        }
    }

    internal fun partitionDirectory(ref: LocalProjectRef, chat: ChatId, branch: BranchId): File =
        File(root, listOf(
            "projects", digest(ref.projectId.value),
            "instances", digest(ref.instanceId.value),
            "generations", ref.generation.value.toString(),
            "chats", digest(chat.value),
            "branches", digest(branch.value),
        ).joinToString(File.separator))

    private suspend fun readPartition(ref: LocalProjectRef, chat: ChatId, branch: BranchId, limit: Int): List<CheckpointEnvelope> {
        val directory = checkedPartition(ref, chat, branch)
        if (!directory.exists()) return emptyList()
        if (!directory.isDirectory) throw AgentCheckpointCorruptException("Agent checkpoint partition is not a directory")
        return checkpointFiles(directory).take(limit).map { source ->
            currentCoroutineContext().ensureActive()
            decode(source, ref, chat, branch)
        }
    }

    private fun decode(source: File, ref: LocalProjectRef, chat: ChatId, branch: BranchId): CheckpointEnvelope {
        val bytes = try {
            AtomicFile(source).openRead().use { it.readBounded() }
        } catch (failure: AgentCheckpointException) {
            throw failure
        } catch (failure: Exception) {
            throw AgentCheckpointCorruptException("Unreadable Agent checkpoint file", failure)
        }
        if (bytes.size > MAX_FILE_BYTES) throw AgentCheckpointLimitException("Agent checkpoint exceeds the file size limit")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: Exception) {
            throw AgentCheckpointCorruptException("Agent checkpoint is not valid UTF-8", failure)
        }
        val envelope = try {
            AgentCheckpointCodec.decode(text)
        } catch (failure: AgentCheckpointException) {
            throw failure
        } catch (failure: Exception) {
            throw AgentCheckpointCorruptException("Invalid Agent checkpoint", failure)
        }
        if (envelope.projectRef != ref || envelope.run.projectId != ref.projectId ||
            envelope.run.chatId != chat || envelope.branchId != branch) {
            throw AgentCheckpointCorruptException("Agent checkpoint identity does not match its partition")
        }
        return envelope
    }

    private fun checkedPartition(ref: LocalProjectRef, chat: ChatId, branch: BranchId): File {
        val directory = partitionDirectory(ref, chat, branch)
        val rootPath = root.canonicalFile
        val path = directory.canonicalFile
        require(path.path.startsWith(rootPath.path + File.separator)) {
            "Agent checkpoint partition escapes its storage root"
        }
        return directory
    }

    /**
     * Base checkpoint names plus their AtomicFile backups. Newest first because the fixed-width
     * timestamp is part of the name. More files than the retention bound fails closed.
     */
    private fun checkpointFiles(directory: File): List<File> {
        if (!directory.exists()) return emptyList()
        if (!directory.isDirectory) throw AgentCheckpointCorruptException("Agent checkpoint partition is not a directory")
        val entries = directory.listFiles()
            ?: throw AgentCheckpointCorruptException("Unreadable Agent checkpoint partition")
        val grouped = entries.asSequence()
            .filter { it.isFile }
            .mapNotNull { file ->
                val name = file.name
                val base = when {
                    name.startsWith(PREFIX) && name.endsWith(".json.bak") -> name.removeSuffix(".bak")
                    name.startsWith(PREFIX) && name.endsWith(".json") -> name
                    else -> null
                }
                base
            }
            .distinct()
            .map { File(directory, it) }
            .sortedByDescending { it.name }
            .toList()
        if (grouped.size > MAX_CHECKPOINTS_PER_PARTITION) {
            throw AgentCheckpointLimitException("Agent checkpoint partition exceeds its file count limit")
        }
        return grouped
    }

    private fun prune(directory: File, files: List<File>) {
        for (source in files) {
            if (!deleteChecked(source) || !deleteChecked(File(source.path + ".bak")) ||
                !deleteChecked(File(source.path + ".new"))) {
                throw AgentCheckpointCorruptException("Unable to enforce the Agent checkpoint retention limit")
            }
        }
    }

    /**
     * Per-partition retention is bounded, but chats/branches accumulate. Sweep the oldest
     * checkpoint files across the whole store after each successful save, never deleting the
     * checkpoint just written. Deletion here is best effort: a file that cannot be removed is
     * retried by the next save and never turns a successful write into a failure.
     */
    private fun enforceGlobalRetention(keep: File) {
        if (!root.isDirectory) return
        val bases = try {
            root.walkTopDown()
                .filter { it.isFile && it.name.startsWith(PREFIX) }
                .mapNotNull { file ->
                    when {
                        file.name.endsWith(".json.bak") -> file.parentFile?.let { File(it, file.name.removeSuffix(".bak")) }
                        file.name.endsWith(".json") -> file
                        else -> null
                    }
                }
                .distinctBy { it.path }
                .sortedBy { it.lastModified() }
                .toList()
        } catch (_: Exception) {
            return
        }
        var totalBytes = bases.sumOf { it.length() }
        var excessCount = bases.size - MAX_TOTAL_CHECKPOINTS
        var excessBytes = totalBytes - MAX_TOTAL_BYTES
        if (excessCount <= 0 && excessBytes <= 0) return
        for (base in bases) {
            if (excessCount <= 0 && excessBytes <= 0) break
            if (base.absoluteFile == keep.absoluteFile) continue
            val size = base.length()
            if (deleteChecked(base) && deleteChecked(File(base.path + ".bak")) &&
                deleteChecked(File(base.path + ".new"))
            ) {
                excessCount -= 1
                excessBytes -= size
            }
        }
    }

    private fun deleteChecked(file: File): Boolean = !file.exists() || file.delete() || !file.exists()

    private fun nextStamp(newestFirst: List<File>): Long {
        val newest = newestFirst.firstOrNull()?.name?.let { name ->
            name.removePrefix(PREFIX).take(STAMP_DIGITS).toLongOrNull()
        } ?: 0L
        return maxOf(System.currentTimeMillis(), newest + 1)
    }

    private fun checkpointName(stamp: Long): String =
        PREFIX + stamp.toString().padStart(STAMP_DIGITS, '0') + "-" + UUID.randomUUID() + ".json"

    private fun digest(value: String): String =
        TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(value))

    private fun java.io.InputStream.readBounded(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > MAX_FILE_BYTES) {
                throw AgentCheckpointLimitException("Agent checkpoint exceeds the file size limit")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    internal companion object {
        const val MAX_FILE_BYTES = AgentCheckpointCodec.MAX_ENCODED_BYTES
        const val MAX_CHECKPOINTS_PER_PARTITION = 16
        const val MAX_LIST_LIMIT = 64
        private const val MAX_TOTAL_CHECKPOINTS = 256
        private const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
        private const val DIRECTORY = "native_agent_checkpoints"
        private const val PREFIX = "checkpoint-"
        private const val STAMP_DIGITS = 19
        private val storeMutex = Mutex()
    }
}
