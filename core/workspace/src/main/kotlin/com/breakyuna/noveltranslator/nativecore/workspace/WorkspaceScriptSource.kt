package com.breakyuna.noveltranslator.nativecore.workspace

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Absolute script-source ceiling. A script file above this is never loaded into the engine. */
object WorkspaceScriptSourceLimits {
    const val MAX_SOURCE_BYTES: Long = 512L * 1024
    const val MAX_SCRIPT_ID_CHARS: Int = 256
    const val MAX_ENTRY_POINT_CHARS: Int = 512
}

/**
 * Resolved, fingerprinted script source. It never appears in [WorkspaceScriptRequest], logs or
 * approval records; the engine receives it through [WorkspaceScriptSourcePort] at execution
 * time and passes it directly to the embedded interpreter.
 */
class WorkspaceScriptSource(
    val scriptId: String,
    val entryPoint: String,
    val text: String,
) {
    private val encoded: ByteArray = text.toByteArray(Charsets.UTF_8)
    val sizeBytes: Long = encoded.size.toLong()
    val sha256: String = sha256Hex(encoded)

    init {
        require(scriptId.isNotBlank() && scriptId.length <= WorkspaceScriptSourceLimits.MAX_SCRIPT_ID_CHARS)
        require(scriptId.none { it.isISOControl() }) { "Control character in script identity" }
        require(entryPoint.isNotBlank() && entryPoint.length <= WorkspaceScriptSourceLimits.MAX_ENTRY_POINT_CHARS)
        require(entryPoint.none { it.isISOControl() }) { "Control character in entry point" }
        requireWellFormedUnicode(text)
        require(sizeBytes <= WorkspaceScriptSourceLimits.MAX_SOURCE_BYTES) { "Script source too large" }
    }

    override fun toString(): String =
        "WorkspaceScriptSource(script=$scriptId, size=$sizeBytes, sha256=$sha256)"
}

/**
 * Resolves script identity to bounded UTF-8 source. Production code uses a private on-disk
 * catalog; tests may supply synthetic sources. Missing or invalid source must throw
 * [WorkspaceScriptSourceUnavailable]; returning empty source would silently run a no-op.
 */
interface WorkspaceScriptSourcePort {
    suspend fun resolve(scriptId: String, entryPoint: String): WorkspaceScriptSource
}

class WorkspaceScriptSourceUnavailable(val reason: Reason) : IllegalStateException("Script source unavailable: $reason") {
    enum class Reason {
        INVALID_SCRIPT_ID,
        INVALID_ENTRY_POINT,
        INVALID_PATH,
        ESCAPE,
        NOT_FOUND,
        TOO_LARGE,
        INVALID_ENCODING,
        READ_FAILED,
    }

    /** Paths and bodies stay out of exception text so a crash report cannot leak source. */
    override fun toString(): String = "WorkspaceScriptSourceUnavailable($reason)"
}

/**
 * File catalog for user scripts. Layout is `<root>/<scriptId>/<entryPoint>`; a script id is one
 * path segment and an entry point may use nested segments. Every resolution is canonicalized
 * against the private root, so `..`, absolute paths, backslashes, control characters and
 * symlinks that leave the root are refused. Source is decoded as strict UTF-8 and capped at
 * [WorkspaceScriptSourceLimits.MAX_SOURCE_BYTES].
 */
class FileWorkspaceScriptCatalog(
    root: File,
    private val policy: PathPolicy = PathPolicy.DEFAULT,
    canonicalizer: (File) -> String = { it.canonicalPath },
) : WorkspaceScriptSourcePort {
    private val root: WorkspaceRoot = WorkspaceRoot.open(root, canonicalizer)

    override suspend fun resolve(scriptId: String, entryPoint: String): WorkspaceScriptSource {
        val relative = validateIdentity(scriptId, entryPoint)
        val file = try {
            root.resolve(relative)
        } catch (failure: InvalidWorkspacePath) {
            val reason = if (failure.reason == InvalidWorkspacePath.Reason.SYMLINK_ESCAPE) {
                WorkspaceScriptSourceUnavailable.Reason.ESCAPE
            } else {
                WorkspaceScriptSourceUnavailable.Reason.INVALID_PATH
            }
            throw WorkspaceScriptSourceUnavailable(reason)
        }
        if (!file.isFile) throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.NOT_FOUND)
        val bytes = try {
            readBounded(file, WorkspaceScriptSourceLimits.MAX_SOURCE_BYTES)
        } catch (failure: WorkspaceScriptSourceUnavailable) {
            throw failure
        } catch (failure: IOException) {
            throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.READ_FAILED)
        } catch (failure: SecurityException) {
            throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.READ_FAILED)
        }
        val text = try {
            decodeUtf8(bytes)
        } catch (failure: CharacterCodingException) {
            throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.INVALID_ENCODING)
        }
        if (text.toByteArray(Charsets.UTF_8).size.toLong() > WorkspaceScriptSourceLimits.MAX_SOURCE_BYTES) {
            throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.TOO_LARGE)
        }
        return WorkspaceScriptSource(scriptId, entryPoint, text)
    }

    private fun validateIdentity(scriptId: String, entryPoint: String): WorkspacePath {
        if (scriptId.isBlank() || scriptId.length > WorkspaceScriptSourceLimits.MAX_SCRIPT_ID_CHARS) {
            throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.INVALID_SCRIPT_ID)
        }
        if (scriptId.any { it.isISOControl() } || '/' in scriptId || '\\' in scriptId) {
            throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.INVALID_SCRIPT_ID)
        }
        if (entryPoint.isBlank() || entryPoint.length > WorkspaceScriptSourceLimits.MAX_ENTRY_POINT_CHARS) {
            throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.INVALID_ENTRY_POINT)
        }
        if (entryPoint.any { it.isISOControl() }) {
            throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.INVALID_ENTRY_POINT)
        }
        validateSegment(scriptId, WorkspaceScriptSourceUnavailable.Reason.INVALID_SCRIPT_ID)
        validateSegment(entryPoint, WorkspaceScriptSourceUnavailable.Reason.INVALID_ENTRY_POINT)
        val combined = "$scriptId/$entryPoint"
        val parsed = try {
            policy.parse(combined)
        } catch (failure: InvalidWorkspacePath) {
            throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.INVALID_PATH)
        }
        // Reject a spelling the policy would silently rewrite; only the exact NFC form is
        // accepted so two files cannot share a logical identity.
        if (parsed.value != combined || parsed.value.split('/').firstOrNull() != scriptId) {
            throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.INVALID_PATH)
        }
        return parsed
    }

    private fun validateSegment(value: String, reason: WorkspaceScriptSourceUnavailable.Reason) {
        if (value == "." || value == "..") throw WorkspaceScriptSourceUnavailable(reason)
        val parsed = try {
            policy.parse(value)
        } catch (failure: InvalidWorkspacePath) {
            throw WorkspaceScriptSourceUnavailable(reason)
        }
        if (parsed.value != value) throw WorkspaceScriptSourceUnavailable(reason)
    }

    private fun readBounded(file: File, maxBytes: Long): ByteArray {
        val declared = file.length()
        if (declared > maxBytes) throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.TOO_LARGE)
        FileInputStream(file).use { input ->
            val output = ByteArrayOutputStream(minOf(declared, 64L * 1024).toInt().coerceAtLeast(256))
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count.toLong()
                if (total > maxBytes) {
                    throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.TOO_LARGE)
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }
}
