package com.breakyuna.noveltranslator.nativecore.workspace

import com.breakyuna.noveltranslator.nativecore.domain.InvalidJsonPayload
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WorkspaceScriptOutputException(val reason: Reason) :
    IllegalArgumentException("Invalid workspace script output: $reason") {
    enum class Reason {
        MALFORMED_JSON,
        UNSUPPORTED_FIELD,
        INVALID_PATH,
        INVALID_OPERATION,
        INVALID_CONTENT,
        CONTENT_TOO_LARGE,
        INVALID_MEDIA_TYPE,
        UNKNOWN_MATERIAL,
        INVALID_EXIT_CODE,
    }

    /** No path or body is included in the exception text. */
    override fun toString(): String = "WorkspaceScriptOutputException($reason)"
}

/**
 * Strict decoder for values emitted through the `lg.emitChange` / `lg.emitArtifact` bridge.
 * The schema is intentionally smaller than the Kotlin object graph: unknown fields, duplicate
 * keys, malformed base64, wrong types and paths outside [PathPolicy] are rejected. Nothing is
 * repaired or silently dropped.
 *
 * Change object:
 * ```
 * {"path":"work/a.txt","operation":"CREATE","content":"text"}
 * {"path":"work/a.txt","operation":"UPDATE","baseSha256":"...","content":"text"}
 * {"path":"work/a.txt","operation":"UPDATE","baseSha256":"...","fromMaterial":"mat-1"}
 * {"path":"work/a.txt","operation":"DELETE","baseSha256":"..."}
 * ```
 *
 * Artifact object:
 * ```
 * {"path":"work/out.md","mediaType":"text/markdown","content":"text"}
 * {"path":"work/out.pdf","mediaType":"application/pdf","bytesBase64":"..."}
 * ```
 */
object WorkspaceScriptResultCodec {
    private val CHANGE_FIELDS = setOf(
        "path", "operation", "baseSha256", "content", "contentBase64", "fromMaterial",
    )
    private val ARTIFACT_FIELDS = setOf("path", "mediaType", "content", "bytesBase64")
    private const val MAX_JSON_DEPTH = 8

    fun decodeChange(
        json: String,
        request: WorkspaceScriptRequest,
        limits: WorkspaceScriptLimits,
        policy: PathPolicy = PathPolicy.DEFAULT,
    ): WorkspaceChange {
        val text = requireJsonSize(json, limits.maxChangeBytes)
        val objectValue = parseObject(text)
        requireOnlyFields(objectValue, CHANGE_FIELDS)
        val path = decodePath(objectValue, policy)
        val operation = when (val raw = requireString(objectValue, "operation")) {
            "CREATE" -> WorkspaceChangeOperation.CREATE
            "UPDATE" -> WorkspaceChangeOperation.UPDATE
            "DELETE" -> WorkspaceChangeOperation.DELETE
            else -> throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_OPERATION)
        }
        val base = optionalString(objectValue, "baseSha256")?.let { value ->
            try {
                WorkspaceDigest(value)
            } catch (failure: IllegalArgumentException) {
                throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_CONTENT)
            }
        }
        val content = decodeChangeContent(objectValue, request, limits, base, operation)
        return try {
            WorkspaceChange(path = path, operation = operation, expectedFingerprint = base, content = content)
        } catch (failure: IllegalArgumentException) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_CONTENT)
        }
    }

    fun decodeArtifact(
        json: String,
        limits: WorkspaceScriptLimits,
        policy: PathPolicy = PathPolicy.DEFAULT,
    ): WorkspaceArtifactDraft {
        val text = requireJsonSize(json, limits.maxArtifactBytes)
        val objectValue = parseObject(text)
        requireOnlyFields(objectValue, ARTIFACT_FIELDS)
        val path = decodePath(objectValue, policy)
        val mediaType = requireString(objectValue, "mediaType")
        if (mediaType.isBlank() || mediaType.length > 128 || mediaType.any { it.isISOControl() }) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_MEDIA_TYPE)
        }
        val hasText = objectValue["content"] != null && objectValue["content"] !is JsonNull
        val hasBase64 = objectValue["bytesBase64"] != null && objectValue["bytesBase64"] !is JsonNull
        if (hasText == hasBase64) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_CONTENT)
        }
        val bytes = if (hasText) {
            decodeTextBytes(requireString(objectValue, "content"), limits.maxArtifactBytes)
        } else {
            decodeBase64Bytes(requireString(objectValue, "bytesBase64"), limits.maxArtifactBytes)
        }
        return try {
            WorkspaceArtifactDraft(path = path, mediaType = mediaType, bytes = bytes)
        } catch (failure: IllegalArgumentException) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_CONTENT)
        }
    }

    /**
     * The script's completion value must be an integral exit code in `0..255`; `null`/undefined
     * means zero. Booleans, strings and fractional numbers are invalid, not coerced.
     */
    fun decodeExitCode(value: Any?): Int {
        if (value == null) return 0
        if (value !is Number) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_EXIT_CODE)
        }
        val asDouble = value.toDouble()
        if (!asDouble.isFinite() || asDouble < 0.0 || asDouble > 255.0 || asDouble != Math.floor(asDouble)) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_EXIT_CODE)
        }
        return asDouble.toInt()
    }

    private fun decodeChangeContent(
        objectValue: JsonObject,
        request: WorkspaceScriptRequest,
        limits: WorkspaceScriptLimits,
        base: WorkspaceDigest?,
        operation: WorkspaceChangeOperation,
    ): WorkspaceChangeContent? {
        val fromMaterial = optionalString(objectValue, "fromMaterial")
        val hasText = objectValue["content"] != null && objectValue["content"] !is JsonNull
        val hasBase64 = objectValue["contentBase64"] != null && objectValue["contentBase64"] !is JsonNull
        val sources = listOf(hasText, hasBase64, fromMaterial != null).count { it }
        when (operation) {
            WorkspaceChangeOperation.DELETE -> {
                if (base == null || sources != 0) {
                    throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_CONTENT)
                }
                return null
            }
            WorkspaceChangeOperation.CREATE -> if (base != null) {
                throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_CONTENT)
            }
            WorkspaceChangeOperation.UPDATE -> if (base == null) {
                throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_CONTENT)
            }
        }
        if (sources != 1) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_CONTENT)
        }
        if (fromMaterial != null) {
            val id = try {
                WorkspaceMaterialId(fromMaterial)
            } catch (failure: IllegalArgumentException) {
                throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.UNKNOWN_MATERIAL)
            }
            val descriptor = request.materials.firstOrNull { it.id == id }
                ?: throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.UNKNOWN_MATERIAL)
            if (descriptor.sizeBytes > limits.maxChangeBytes) {
                throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.CONTENT_TOO_LARGE)
            }
            return WorkspaceChangeContent.FromMaterial(
                materialId = id,
                sha256 = WorkspaceDigest(descriptor.sha256),
                sizeBytes = descriptor.sizeBytes,
            )
        }
        val bytes = if (hasText) {
            decodeTextBytes(requireString(objectValue, "content"), limits.maxChangeBytes)
        } else {
            decodeBase64Bytes(requireString(objectValue, "contentBase64"), limits.maxChangeBytes)
        }
        return WorkspaceChangeContent.Inline(bytes)
    }

    private fun parseObject(text: String): JsonObject = try {
        JsonObjectValue.parse(text, JsonInputLimits(maxOf(text.length, 1), MAX_JSON_DEPTH)).json
    } catch (failure: InvalidJsonPayload) {
        throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.MALFORMED_JSON)
    } catch (failure: IllegalArgumentException) {
        throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.MALFORMED_JSON)
    }

    private fun requireOnlyFields(objectValue: JsonObject, allowed: Set<String>) {
        if (!allowed.containsAll(objectValue.keys)) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.UNSUPPORTED_FIELD)
        }
    }

    private fun decodePath(objectValue: JsonObject, policy: PathPolicy): WorkspacePath = try {
        policy.parse(requireString(objectValue, "path"))
    } catch (failure: InvalidWorkspacePath) {
        throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_PATH)
    }

    private fun requireString(objectValue: JsonObject, key: String): String {
        val element: JsonElement = objectValue[key]
            ?: throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_CONTENT)
        if (element !is JsonPrimitive || !element.isString) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_CONTENT)
        }
        return element.content
    }

    private fun optionalString(objectValue: JsonObject, key: String): String? {
        val element = objectValue[key] ?: return null
        if (element is JsonNull) return null
        if (element !is JsonPrimitive || !element.isString) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_CONTENT)
        }
        return element.content
    }

    private fun decodeTextBytes(text: String, maxBytes: Long): ByteArray {
        if (text.length.toLong() > maxBytes) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.CONTENT_TOO_LARGE)
        }
        try {
            requireWellFormedUnicode(text)
        } catch (failure: IllegalArgumentException) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_CONTENT)
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > maxBytes) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.CONTENT_TOO_LARGE)
        }
        return bytes
    }

    private fun decodeBase64Bytes(text: String, maxBytes: Long): ByteArray {
        if (text.length.toLong() > (maxBytes / 3 + 1) * 4 + 4) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.CONTENT_TOO_LARGE)
        }
        return try {
            WorkspaceBase64.decodeStrict(text, maxBytes)
        } catch (failure: WorkspaceScriptOutputException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.INVALID_CONTENT)
        }
    }

    private fun requireJsonSize(json: String, maxBytes: Long): String {
        val limit = maxJsonChars(maxBytes)
        if (json.length > limit) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.CONTENT_TOO_LARGE)
        }
        return json
    }

    /**
     * Base64 expands 3 bytes to 4 characters; doubling the byte allowance leaves room for the
     * array/object envelope while keeping the parser allocation bounded.
     */
    private fun maxJsonChars(maxBytes: Long): Int {
        val expanded = maxBytes.coerceAtLeast(1) * 2 + 65_536
        val capped = minOf(expanded, WorkspaceHardLimits.MAX_OUTPUT_BYTES * 2)
        return capped.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

/** Strict standard base64 (RFC 4648) without whitespace, URL-safe or unpadded variants. */
internal object WorkspaceBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decodeStrict(text: String, maxBytes: Long): ByteArray {
        if (text.length % 4 != 0) throw IllegalArgumentException("Invalid base64 length")
        if (text.isEmpty()) return ByteArray(0)
        val padding = when {
            text.endsWith("==") -> 2
            text.endsWith('=') -> 1
            else -> 0
        }
        val dataChars = text.length - padding
        if (dataChars <= 0) throw IllegalArgumentException("Invalid base64 padding")
        var index = 0
        while (index < dataChars) {
            if (text[index] == '=') throw IllegalArgumentException("Invalid base64 padding")
            index += 1
        }
        val remainder = dataChars % 4
        if (remainder == 1) throw IllegalArgumentException("Invalid base64 tail")
        if (padding == 0 && remainder != 0) throw IllegalArgumentException("Invalid base64 tail")
        if (padding == 1 && remainder != 3) throw IllegalArgumentException("Invalid base64 tail")
        if (padding == 2 && remainder != 2) throw IllegalArgumentException("Invalid base64 tail")
        val fullGroups = dataChars / 4
        val tailBytes = when (remainder) {
            0 -> 0
            2 -> 1
            3 -> 2
            else -> throw IllegalArgumentException("Invalid base64 tail")
        }
        val expected = fullGroups.toLong() * 3 + tailBytes
        if (expected > maxBytes) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.CONTENT_TOO_LARGE)
        }
        val output = ByteArrayOutputStream(expected.toInt())
        var cursor = 0
        while (cursor + 4 <= dataChars) {
            val a = valueOf(text[cursor])
            val b = valueOf(text[cursor + 1])
            val c = valueOf(text[cursor + 2])
            val d = valueOf(text[cursor + 3])
            output.write((a shl 2) or (b shr 4))
            output.write(((b and 0x0f) shl 4) or (c shr 2))
            output.write(((c and 0x03) shl 6) or d)
            cursor += 4
        }
        if (remainder == 2) {
            val a = valueOf(text[cursor])
            val b = valueOf(text[cursor + 1])
            if ((b and 0x0f) != 0) throw IllegalArgumentException("Non-zero base64 padding bits")
            output.write((a shl 2) or (b shr 4))
        } else if (remainder == 3) {
            val a = valueOf(text[cursor])
            val b = valueOf(text[cursor + 1])
            val c = valueOf(text[cursor + 2])
            if ((c and 0x03) != 0) throw IllegalArgumentException("Non-zero base64 padding bits")
            output.write((a shl 2) or (b shr 4))
            output.write(((b and 0x0f) shl 4) or (c shr 2))
        }
        val bytes = output.toByteArray()
        if (bytes.size.toLong() > maxBytes) {
            throw WorkspaceScriptOutputException(WorkspaceScriptOutputException.Reason.CONTENT_TOO_LARGE)
        }
        return bytes
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val builder = StringBuilder(((bytes.size + 2) / 3) * 4)
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xff
            val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xff else 0
            val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xff else 0
            builder.append(ALPHABET[b0 shr 2])
            builder.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
            if (index + 1 < bytes.size) {
                builder.append(ALPHABET[((b1 and 0x0f) shl 2) or (b2 shr 6)])
            } else {
                builder.append('=')
            }
            if (index + 2 < bytes.size) {
                builder.append(ALPHABET[b2 and 0x3f])
            } else {
                builder.append('=')
            }
            index += 3
        }
        return builder.toString()
    }

    private fun valueOf(character: Char): Int {
        return when (character) {
            in 'A'..'Z' -> character - 'A'
            in 'a'..'z' -> character - 'a' + 26
            in '0'..'9' -> character - '0' + 52
            '+' -> 62
            '/' -> 63
            else -> throw IllegalArgumentException("Invalid base64 character")
        }
    }
}

/** JSON encoding for the search/fetch bridge responses; values are already bounded. */
internal object WorkspaceBridgeJson {
    fun search(hits: List<WorkspaceSearchHit>, truncated: Boolean): String {
        val elements = hits.map { hit ->
            buildJsonObject {
                put("title", JsonPrimitive(hit.title))
                put("url", JsonPrimitive(hit.url))
                put("snippet", JsonPrimitive(hit.snippet))
            }
        }
        return buildJsonObject {
            put("truncated", JsonPrimitive(truncated))
            put("hits", JsonArray(elements))
        }.toString()
    }

    fun fetched(document: WorkspaceFetchedDocument): String {
        val bytes = document.bytes
        val text = try {
            decodeUtf8Strict(bytes)
        } catch (failure: Exception) {
            null
        }
        return buildJsonObject {
            put("url", JsonPrimitive(document.finalUrl))
            put("mediaType", JsonPrimitive(document.mediaType))
            put("sizeBytes", JsonPrimitive(document.sizeBytes))
            put("sha256", JsonPrimitive(document.sha256))
            put("truncated", JsonPrimitive(document.truncated))
            if (text != null) {
                put("text", JsonPrimitive(text))
            } else {
                put("bytesBase64", JsonPrimitive(WorkspaceBase64.encode(bytes)))
            }
        }.toString()
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
}
