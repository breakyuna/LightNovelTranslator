package com.breakyuna.noveltranslator.nativecore.domain

/** Shared bounds for one Agent user message with images. */
object AgentAttachmentLimits {
    const val MAX_IMAGE_BYTES: Int = 1_048_576
    const val MAX_PER_MESSAGE: Int = 4

    /** Keeps Base64 image data inside the engine's 2 MiB request budget. */
    const val MAX_TOTAL_MESSAGE_BYTES: Int = 1_400_000
}

/**
 * Exact bytes and frozen identity returned by an attachment store. The payload is copied on
 * construction and on every read, so callers cannot mutate durable evidence in place. No
 * `toString` or exception message includes the image body.
 */
class AgentAttachmentData(
    val id: AttachmentId,
    mimeType: String,
    sha256: String,
    bytes: ByteArray,
    displayName: String,
) {
    val mimeType: String = mimeType
    val sha256: String = sha256
    val displayName: String = displayName
    val sizeBytes: Int
    private val content: ByteArray

    init {
        require(this.mimeType in AgentAttachmentMime.SUPPORTED)
        require(this.sha256.length == 64 && this.sha256.all { it in '0'..'9' || it in 'a'..'f' })
        require(bytes.isNotEmpty() && bytes.size <= AgentAttachmentLimits.MAX_IMAGE_BYTES) {
            "Attachment bytes out of bounds"
        }
        require(this.displayName.isNotBlank() && this.displayName.length <= 120 &&
            this.displayName.none { it.isISOControl() }) { "Invalid attachment display name" }
        content = bytes.copyOf()
        sizeBytes = content.size
    }

    fun bytes(): ByteArray = content.copyOf()

    override fun toString(): String =
        "AgentAttachmentData(id=${id.value}, mimeType=$mimeType, size=$sizeBytes, sha256=$sha256)"
}

/** The only image formats accepted by both storage and model protocols. */
object AgentAttachmentMime {
    val SUPPORTED: Set<String> = setOf("image/png", "image/jpeg", "image/webp")

    /** Normalizes a trusted hint; wildcards and unrelated types are rejected. */
    fun normalize(candidate: String?): String? {
        val value = candidate?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return when (value) {
            "image/jpg", "image/pjpeg" -> "image/jpeg"
            in SUPPORTED -> value
            else -> null
        }
    }

    /** Content sniffing never trusts a SAF or stored MIME hint on its own. */
    fun detect(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG) -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals(RIFF) &&
            bytes.copyOfRange(8, 12).contentEquals(WEBP) -> "image/webp"
        else -> null
    }

    fun matches(mimeType: String, bytes: ByteArray): Boolean =
        normalize(mimeType) == detect(bytes)

    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    private val RIFF = byteArrayOf(0x52, 0x49, 0x46, 0x46)
    private val WEBP = byteArrayOf(0x57, 0x45, 0x42, 0x50)
}

/**
 * Typed local attachment failures. Messages never contain paths, bytes or provider data; the
 * engine converts them to a CONTENT failure before any paid request is built.
 */
sealed class AgentAttachmentException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unavailable : AgentAttachmentException("Attachment is not available")
    class InvalidId : AgentAttachmentException("Attachment identity is invalid")
    class InvalidMime : AgentAttachmentException("Attachment MIME type is not supported")
    class Empty : AgentAttachmentException("Attachment is empty")
    class TooLarge : AgentAttachmentException("Attachment exceeds the size limit")
    class Limit : AgentAttachmentException("Attachment count or total size exceeds the limit")
    class Corrupt : AgentAttachmentException("Attachment is corrupt")
    class IdentityMismatch : AgentAttachmentException("Attachment identity does not match")
}

/** File-backed attachment boundary. Implementations must not log bodies or cache payloads. */
interface AgentAttachmentStorePort {
    suspend fun save(
        bytes: ByteArray,
        mimeType: String,
        project: LocalProjectRef,
        displayName: String? = null,
    ): AttachmentId

    suspend fun resolve(project: LocalProjectRef, id: AttachmentId): AgentAttachmentData
}
