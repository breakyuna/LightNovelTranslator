package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.breakyuna.noveltranslator.nativecore.domain.AgentAttachmentData
import com.breakyuna.noveltranslator.nativecore.domain.AgentAttachmentException
import com.breakyuna.noveltranslator.nativecore.domain.AgentAttachmentLimits
import com.breakyuna.noveltranslator.nativecore.domain.AgentAttachmentMime
import com.breakyuna.noveltranslator.nativecore.domain.AgentAttachmentStorePort
import com.breakyuna.noveltranslator.nativecore.domain.AttachmentId
import com.breakyuna.noveltranslator.nativecore.domain.FrozenImageRef
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Private, bounded image storage for one project instance/generation. The layout is
 * `files/native_agent_attachments/<instance digest>/<generation>/<attachment id>.lnta`.
 * Writes use a same-directory temporary file, file fsync, rename and a best-effort directory
 * fsync. Each envelope carries its identity, MIME, SHA-256 and display name; reads verify all of
 * them and return an explicit failure for missing, corrupt or foreign files. No Room table, log
 * line or cache holds image bytes.
 *
 * Attachment IDs embed the instance digest and generation, so the engine-side resolver can find
 * the exact partition from a [FrozenImageRef] without a project handle. Project ID remains in the
 * envelope and is checked whenever a project-scoped resolve is requested.
 */
class NativeAgentAttachmentStore(root: File) : AgentAttachmentStorePort {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, DIRECTORY))

    private val root = root
    private val mutex = Mutex()

    override suspend fun save(
        bytes: ByteArray,
        mimeType: String,
        project: LocalProjectRef,
        displayName: String?,
    ): AttachmentId = withContext(Dispatchers.IO) {
        val payload = bytes.copyOf()
        val mime = AgentAttachmentMime.normalize(mimeType)
            ?: throw AgentAttachmentException.InvalidMime()
        if (payload.isEmpty()) throw AgentAttachmentException.Empty()
        if (payload.size > AgentAttachmentLimits.MAX_IMAGE_BYTES) throw AgentAttachmentException.TooLarge()
        if (!AgentAttachmentMime.matches(mime, payload)) throw AgentAttachmentException.InvalidMime()
        val digest = TextProjectFingerprint.bytes(payload)
        val name = sanitizeName(displayName)
        val instanceDigest = digestIdentity(project.instanceId.value)
        val id = AttachmentId(
            "${instanceDigest}_${project.generation.value}_" +
                UUID.randomUUID().toString().replace("-", "").lowercase(),
        )
        val parsed = parseId(id)
        val header = try {
            buildJsonObject {
                put("version", ENVELOPE_VERSION)
                put("id", id.value)
                put("project", project.projectId.value)
                put("instance", project.instanceId.value)
                put("generation", project.generation.value)
                put("mime", mime)
                put("sha256", digest)
                put("name", name)
                put("size", payload.size)
            }.toString()
        } catch (_: IllegalArgumentException) {
            throw AgentAttachmentException.InvalidId()
        }
        val headerBytes = TextProjectFingerprint.utf8(header)
        if (headerBytes.size > MAX_HEADER_BYTES) throw AgentAttachmentException.Limit()

        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val directory = requirePartition(parsed, create = true)
            val target = File(directory, id.value + FILE_SUFFIX)
            requireWithin(directory, target)
            if (target.exists()) throw AgentAttachmentException.Unavailable()
            val temporary = File(directory, ".tmp-" + UUID.randomUUID().toString() + ".part")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(MAGIC)
                    output.write(ByteBuffer.allocate(4).putInt(headerBytes.size).array())
                    output.write(headerBytes)
                    output.write(payload)
                    output.flush()
                    output.fd.sync()
                }
                currentCoroutineContext().ensureActive()
                if (!temporary.renameTo(target)) throw AgentAttachmentException.Unavailable()
                fsyncDirectory(directory)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AgentAttachmentException) {
                throw failure
            } catch (_: Exception) {
                throw AgentAttachmentException.Unavailable()
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
        id
    }

    override suspend fun resolve(project: LocalProjectRef, id: AttachmentId): AgentAttachmentData =
        withContext(Dispatchers.IO) {
            val parsed = parseId(id)
            if (parsed.instanceDigest != digestIdentity(project.instanceId.value) ||
                parsed.generation != project.generation.value) {
                throw AgentAttachmentException.IdentityMismatch()
            }
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                val directory = requirePartition(parsed, create = false)
                load(directory, parsed, id, project)
            }
        }

    /**
     * Engine resolver path. The frozen ID must still point at the exact stored envelope and the
     * MIME/SHA pair must match; nothing is returned when either check fails.
     */
    suspend fun resolveImage(image: FrozenImageRef): ByteArray = withContext(Dispatchers.IO) {
        val mime = AgentAttachmentMime.normalize(image.mimeType)
            ?: throw AgentAttachmentException.InvalidMime()
        val parsed = parseId(image.attachmentId)
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val directory = requirePartition(parsed, create = false)
            val data = load(directory, parsed, image.attachmentId, null)
            if (data.mimeType != mime || data.sha256 != image.sha256) {
                throw AgentAttachmentException.IdentityMismatch()
            }
            data.bytes()
        }
    }

    private data class ParsedId(val instanceDigest: String, val generation: Long)

    private fun parseId(id: AttachmentId): ParsedId {
        val match = ID_PATTERN.matchEntire(id.value) ?: throw AgentAttachmentException.InvalidId()
        val generation = match.groupValues[2].toLongOrNull()
        if (generation == null || generation <= 0) throw AgentAttachmentException.InvalidId()
        return ParsedId(match.groupValues[1], generation)
    }

    private fun requirePartition(parsed: ParsedId, create: Boolean): File {
        val directory = File(root, parsed.instanceDigest + File.separator + parsed.generation)
        val rootPath = root.canonicalFile
        val path = directory.canonicalFile
        if (!path.path.startsWith(rootPath.path + File.separator)) {
            throw AgentAttachmentException.InvalidId()
        }
        if (create) {
            if (!root.isDirectory && !root.mkdirs()) throw AgentAttachmentException.Unavailable()
            if (!directory.isDirectory && !directory.mkdirs()) throw AgentAttachmentException.Unavailable()
        } else if (!directory.exists()) {
            throw AgentAttachmentException.Unavailable()
        } else if (!directory.isDirectory) {
            throw AgentAttachmentException.Corrupt()
        }
        return directory
    }

    private fun load(
        directory: File,
        parsed: ParsedId,
        id: AttachmentId,
        expectedProject: LocalProjectRef?,
    ): AgentAttachmentData {
        val source = File(directory, id.value + FILE_SUFFIX)
        requireWithin(directory, source)
        if (!source.isFile) throw AgentAttachmentException.Unavailable()
        if (source.length() > MAX_ENVELOPE_BYTES.toLong()) throw AgentAttachmentException.Corrupt()
        val envelope = try {
            readBounded(source, MAX_ENVELOPE_BYTES)
        } catch (failure: AgentAttachmentException) {
            throw failure
        } catch (_: Exception) {
            throw AgentAttachmentException.Corrupt()
        }
        if (envelope.size < MAGIC.size + 4) throw AgentAttachmentException.Corrupt()
        for (index in MAGIC.indices) {
            if (envelope[index] != MAGIC[index]) throw AgentAttachmentException.Corrupt()
        }
        val headerLength = ByteBuffer.wrap(envelope, MAGIC.size, 4).int
        if (headerLength !in 1..MAX_HEADER_BYTES) throw AgentAttachmentException.Corrupt()
        val payloadStart = MAGIC.size + 4 + headerLength
        if (payloadStart >= envelope.size) throw AgentAttachmentException.Corrupt()
        val payloadSize = envelope.size - payloadStart
        if (payloadSize > AgentAttachmentLimits.MAX_IMAGE_BYTES) throw AgentAttachmentException.Corrupt()
        val header = try {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(envelope, MAGIC.size + 4, headerLength))
                .toString()
            Json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            throw AgentAttachmentException.Corrupt()
        }
        val storedVersion = try {
            header["version"]?.jsonPrimitive?.intOrNull
        } catch (_: Exception) {
            null
        }
        if (storedVersion != ENVELOPE_VERSION) throw AgentAttachmentException.Corrupt()
        fun stringField(name: String): String? = try {
            header[name]?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
        fun longField(name: String): Long? = try {
            header[name]?.jsonPrimitive?.longOrNull
        } catch (_: Exception) {
            null
        }
        fun intField(name: String): Int? = try {
            header[name]?.jsonPrimitive?.intOrNull
        } catch (_: Exception) {
            null
        }
        val storedId = stringField("id") ?: throw AgentAttachmentException.Corrupt()
        val storedInstance = stringField("instance") ?: throw AgentAttachmentException.Corrupt()
        val storedGeneration = longField("generation") ?: throw AgentAttachmentException.Corrupt()
        val storedMime = stringField("mime") ?: throw AgentAttachmentException.Corrupt()
        val storedDigest = stringField("sha256") ?: throw AgentAttachmentException.Corrupt()
        val storedName = stringField("name") ?: throw AgentAttachmentException.Corrupt()
        val storedSize = intField("size") ?: throw AgentAttachmentException.Corrupt()
        val storedInstanceDigest = try {
            digestIdentity(storedInstance)
        } catch (_: Exception) {
            throw AgentAttachmentException.Corrupt()
        }
        if (storedId != id.value ||
            storedInstanceDigest != parsed.instanceDigest ||
            storedGeneration != parsed.generation) {
            throw AgentAttachmentException.Corrupt()
        }
        if (storedSize != payloadSize || storedDigest.length != 64 ||
            storedDigest.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            throw AgentAttachmentException.Corrupt()
        }
        val mime = AgentAttachmentMime.normalize(storedMime) ?: throw AgentAttachmentException.Corrupt()
        if (mime != storedMime) throw AgentAttachmentException.Corrupt()
        val payload = envelope.copyOfRange(payloadStart, envelope.size)
        if (!AgentAttachmentMime.matches(mime, payload)) throw AgentAttachmentException.Corrupt()
        if (TextProjectFingerprint.bytes(payload) != storedDigest) throw AgentAttachmentException.Corrupt()
        if (storedName.isBlank() || storedName.length > 120 ||
            storedName.any { it.isISOControl() }) throw AgentAttachmentException.Corrupt()
        if (expectedProject != null) {
            val storedProject = stringField("project") ?: throw AgentAttachmentException.Corrupt()
            if (storedProject != expectedProject.projectId.value ||
                storedInstance != expectedProject.instanceId.value ||
                storedGeneration != expectedProject.generation.value) {
                throw AgentAttachmentException.IdentityMismatch()
            }
        }
        return try {
            AgentAttachmentData(id, mime, storedDigest, payload, storedName)
        } catch (_: IllegalArgumentException) {
            throw AgentAttachmentException.Corrupt()
        }
    }

    private fun readBounded(source: File, limit: Int): ByteArray {
        val initial = minOf(source.length(), 32L * 1024).toInt().coerceAtLeast(32)
        val output = ByteArrayOutputStream(initial)
        source.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw AgentAttachmentException.Corrupt()
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun requireWithin(directory: File, file: File) {
        val directoryPath = directory.canonicalFile.path
        val filePath = file.canonicalFile.path
        if (!filePath.startsWith(directoryPath + File.separator)) {
            throw AgentAttachmentException.InvalidId()
        }
    }

    private fun sanitizeName(raw: String?): String {
        val cleaned = raw.orEmpty()
            .filterNot { it.isISOControl() }
            .trim()
        val bounded = cleaned.take(120)
        val safe = if (bounded.lastOrNull()?.isHighSurrogate() == true) bounded.dropLast(1) else bounded
        return safe.ifBlank { "image" }
    }

    private fun digestIdentity(value: String): String = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(value))

    private fun fsyncDirectory(directory: File) {
        var descriptor: FileDescriptor? = null
        try {
            val opened = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            descriptor = opened
            Os.fsync(opened)
        } catch (_: Throwable) {
            // The file itself was fsynced before rename. Directory fsync is best effort on
            // providers that do not expose directory descriptors; it is never a data-path log.
        } finally {
            descriptor?.let { runCatching { Os.close(it) } }
        }
    }

    private companion object {
        const val DIRECTORY = "native_agent_attachments"
        const val FILE_SUFFIX = ".lnta"
        const val ENVELOPE_VERSION = 1
        const val MAX_HEADER_BYTES = 4096
        const val MAX_ENVELOPE_BYTES = AgentAttachmentLimits.MAX_IMAGE_BYTES + MAX_HEADER_BYTES + 64

        /** `<instance sha256>_<generation>_<128-bit random>`, all path-safe lowercase hex. */
        val ID_PATTERN = Regex("^([0-9a-f]{64})_([1-9][0-9]{0,18})_([0-9a-f]{32})$")
        val MAGIC = byteArrayOf('L'.code.toByte(), 'N'.code.toByte(), 'T'.code.toByte(), 'A'.code.toByte(), 1)
    }
}
