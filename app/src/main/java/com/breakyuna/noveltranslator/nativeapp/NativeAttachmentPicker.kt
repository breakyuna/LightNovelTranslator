package com.breakyuna.noveltranslator.nativeapp

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.breakyuna.noveltranslator.nativecore.domain.AgentAttachmentLimits
import com.breakyuna.noveltranslator.nativecore.domain.AgentAttachmentMime
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** One bounded read from a SAF URI; no unbounded stream is ever buffered. */
internal fun readBoundedAttachment(
    input: InputStream,
    maxBytes: Int = AgentAttachmentLimits.MAX_IMAGE_BYTES,
): ByteArray {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw NativeAttachmentReadException(
            NativeAgentAttachmentFailureReason.TOO_LARGE, "image")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun resolvePickedAttachmentMime(mimeHint: String?, bytes: ByteArray): String {
    val hint = AgentAttachmentMime.normalize(mimeHint)
    val detected = AgentAttachmentMime.detect(bytes)
        ?: throw NativeAttachmentReadException(
            NativeAgentAttachmentFailureReason.UNSUPPORTED_TYPE, "image")
    if (hint != null && hint != detected) throw NativeAttachmentReadException(
        NativeAgentAttachmentFailureReason.UNSUPPORTED_TYPE, "image")
    return detected
}

internal fun attachmentDisplayName(raw: String?): String {
    val cleaned = raw.orEmpty()
        .filterNot { it.isISOControl() }
        .trim()
    val bounded = cleaned.take(120)
    val safe = if (bounded.lastOrNull()?.isHighSurrogate() == true) bounded.dropLast(1) else bounded
    return safe.ifBlank { "image" }
}

internal data class PickedAgentAttachment(
    val displayName: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    val sizeBytes: Int get() = bytes.size
}

/** Bounded provider metadata lookup used to label rejected items without reading the body. */
internal fun pickedAttachmentName(resolver: ContentResolver, uri: Uri): String =
    queryDisplayName(resolver, uri)

internal class NativeAttachmentReadException(
    val reason: NativeAgentAttachmentFailureReason,
    val displayName: String,
) : Exception(reason.name)

/**
 * Reads one SAF document on a caller-owned IO dispatcher. The declared size is only an early
 * rejection; the bounded stream read remains authoritative.
 */
internal fun readPickedAttachment(resolver: ContentResolver, uri: Uri): PickedAgentAttachment {
    val name = queryDisplayName(resolver, uri)
    val declaredSize = querySize(resolver, uri)
    if (declaredSize != null && declaredSize > AgentAttachmentLimits.MAX_IMAGE_BYTES) {
        throw NativeAttachmentReadException(NativeAgentAttachmentFailureReason.TOO_LARGE, name)
    }
    val mimeHint = try {
        resolver.getType(uri)
    } catch (_: Exception) {
        null
    }
    val bytes = try {
        resolver.openInputStream(uri)?.use { input -> readBoundedAttachment(input) }
            ?: throw NativeAttachmentReadException(
                NativeAgentAttachmentFailureReason.UNREADABLE, name)
    } catch (failure: NativeAttachmentReadException) {
        throw NativeAttachmentReadException(failure.reason, name)
    } catch (_: Exception) {
        throw NativeAttachmentReadException(NativeAgentAttachmentFailureReason.UNREADABLE, name)
    }
    if (bytes.isEmpty()) throw NativeAttachmentReadException(
        NativeAgentAttachmentFailureReason.UNSUPPORTED_TYPE, name)
    val mime = try {
        resolvePickedAttachmentMime(mimeHint, bytes)
    } catch (failure: NativeAttachmentReadException) {
        throw NativeAttachmentReadException(failure.reason, name)
    }
    return PickedAgentAttachment(name, mime, bytes)
}

private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String {
    val raw = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
    return attachmentDisplayName(raw)
}

private fun querySize(resolver: ContentResolver, uri: Uri): Long? = try {
    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
    }
} catch (_: Exception) {
    null
}
