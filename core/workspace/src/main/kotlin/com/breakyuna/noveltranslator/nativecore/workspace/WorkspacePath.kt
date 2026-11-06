package com.breakyuna.noveltranslator.nativecore.workspace

import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * How workspace path identity treats letter case. The stored spelling is always the
 * NFC-normalized original; [PathPolicy.identityKey] decides which spellings collide.
 */
enum class WorkspaceCasePolicy {
    /** Android private storage: `Report.txt` and `report.txt` are different files. */
    EXACT,

    /** Case-insensitive volumes (FAT/exFAT, Windows shares): the two spellings are one file. */
    FOLDED,
}

class InvalidWorkspacePath(val reason: Reason, val subject: String? = null) :
    IllegalArgumentException("Invalid workspace path: $reason") {
    enum class Reason {
        EMPTY,
        ABSOLUTE,
        BACKSLASH,
        DRIVE_PREFIX,
        CONTROL_CHARACTER,
        UNPAIRED_SURROGATE,
        NOT_NFC,
        EMPTY_SEGMENT,
        DOT_SEGMENT,
        TRAILING_DOT_OR_SPACE,
        RESERVED_NAME,
        SEGMENT_TOO_LONG,
        PATH_TOO_LONG,
        TOO_DEEP,
        SYMLINK_ESCAPE,
    }

    /** Deliberately omits [subject] from `toString` so a path never leaks into a crash report. */
    override fun toString(): String = "InvalidWorkspacePath($reason)"
}

/**
 * Canonical logical path inside one workspace. This is a pure string identity: it only
 * accepts `/`-separated relative paths that are already NFC-normalized and structurally
 * unambiguous. Filesystem adapters must still call [WorkspaceRoot.resolve] before any IO;
 * this type alone cannot detect symlinks.
 *
 * Rejected here regardless of policy: absolute paths, backslashes, Windows drive prefixes,
 * ISO control characters (including NUL), empty/`.`/`..` segments, non-NFC spellings and
 * unpaired surrogates. Length, depth and Windows reserved names are policy limits.
 */
@JvmInline
value class WorkspacePath(val value: String) {
    init {
        if (value.isEmpty()) throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.EMPTY)
        if (value.startsWith('/')) throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.ABSOLUTE, value)
        if ('\\' in value) throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.BACKSLASH, value)
        if (value.length >= 2 && value[0].isLetter() && value[1] == ':') {
            throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.DRIVE_PREFIX, value)
        }
        if (value.any { it.isISOControl() }) {
            throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.CONTROL_CHARACTER, value)
        }
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character.isHighSurrogate()) {
                if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                    throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.UNPAIRED_SURROGATE, value)
                }
                index += 2
            } else if (character.isLowSurrogate()) {
                throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.UNPAIRED_SURROGATE, value)
            } else {
                index += 1
            }
        }
        if (Normalizer.normalize(value, Normalizer.Form.NFC) != value) {
            throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.NOT_NFC, value)
        }
        if (value.endsWith('/') || "//" in value) {
            throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.EMPTY_SEGMENT, value)
        }
        value.split('/').forEach { segment ->
            if (segment.isEmpty()) throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.EMPTY_SEGMENT, value)
            if (segment == "." || segment == "..") {
                throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.DOT_SEGMENT, value)
            }
        }
    }

    override fun toString(): String = "WorkspacePath($value)"
}

/**
 * Explicit limits and identity rules for workspace paths. Apply [parse] to every path that
 * enters the workspace from a script, ZIP entry, SAF name or user edit; constructing
 * [WorkspacePath] directly only proves the structural invariants above.
 */
class PathPolicy(
    val casePolicy: WorkspaceCasePolicy = WorkspaceCasePolicy.EXACT,
    val maxPathChars: Int = 512,
    val maxSegmentBytes: Int = 255,
    val maxDepth: Int = 32,
    val rejectWindowsReservedNames: Boolean = true,
) {
    init {
        require(maxPathChars in 8..4_096) { "Invalid maximum path length" }
        require(maxSegmentBytes in 8..4_096) { "Invalid maximum segment size" }
        require(maxDepth in 1..128) { "Invalid maximum path depth" }
    }

    /** Normalizes to NFC first; callers that cannot accept a rewritten name must compare it. */
    fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFC)

    fun parse(raw: String): WorkspacePath {
        val path = WorkspacePath(normalize(raw))
        if (path.value.length > maxPathChars) {
            throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.PATH_TOO_LONG, raw)
        }
        val segments = path.value.split('/')
        if (segments.size > maxDepth) {
            throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.TOO_DEEP, raw)
        }
        segments.forEach { segment ->
            if (segment.toByteArray(Charsets.UTF_8).size > maxSegmentBytes) {
                throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.SEGMENT_TOO_LONG, raw)
            }
            if (segment.last() == '.' || segment.last() == ' ') {
                throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.TRAILING_DOT_OR_SPACE, raw)
            }
            if (rejectWindowsReservedNames && isWindowsReservedName(segment)) {
                throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.RESERVED_NAME, raw)
            }
        }
        return path
    }

    /** Identity used to detect two spellings that would write the same file. */
    fun identityKey(path: WorkspacePath): String = when (casePolicy) {
        WorkspaceCasePolicy.EXACT -> path.value
        WorkspaceCasePolicy.FOLDED -> path.value.lowercase(Locale.ROOT)
    }

    fun collides(left: WorkspacePath, right: WorkspacePath): Boolean =
        identityKey(left) == identityKey(right)

    private fun isWindowsReservedName(segment: String): Boolean {
        val base = segment.substringBefore('.').trimEnd(' ', '.').uppercase(Locale.ROOT)
        return base in WINDOWS_RESERVED_NAMES
    }

    companion object {
        val DEFAULT = PathPolicy()

        private val WINDOWS_RESERVED_NAMES: Set<String> = buildSet {
            add("CON")
            add("PRN")
            add("AUX")
            add("NUL")
            for (index in 1..9) {
                add("COM$index")
                add("LPT$index")
            }
        }
    }
}

/**
 * Filesystem boundary for a workspace directory. [resolve] canonicalizes the candidate and
 * refuses anything whose canonical path is not inside the canonical root, which is how a
 * symlinked child is prevented from escaping. The canonicalizer is injectable so tests can
 * exercise the escape decision without creating real symlinks; production must pass the
 * platform-canonical implementation ([open] default).
 *
 * This class performs no writes and creates no directories.
 */
class WorkspaceRoot private constructor(
    val directory: File,
    private val canonicalRoot: String,
    private val canonicalizer: (File) -> String,
) {
    /** Returns the uncanonicalized child for IO only after the canonical boundary check. */
    fun resolve(path: WorkspacePath): File {
        val candidate = File(directory, path.value)
        val canonical = try {
            canonicalizer(candidate).replace('\\', '/')
        } catch (failure: Exception) {
            throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.SYMLINK_ESCAPE, path.value)
        }
        val root = canonicalRoot.replace('\\', '/')
        val prefix = if (root.endsWith('/')) root else "$root/"
        if (canonical != root && !canonical.startsWith(prefix)) {
            throw InvalidWorkspacePath(InvalidWorkspacePath.Reason.SYMLINK_ESCAPE, path.value)
        }
        return candidate
    }

    companion object {
        fun open(directory: File, canonicalizer: (File) -> String = { it.canonicalPath }): WorkspaceRoot {
            require(directory.isDirectory) { "Workspace root must be an existing directory" }
            val normalized = canonicalizer(directory).replace('\\', '/')
            val canonical = normalized.trimEnd('/').ifEmpty { "/" }
            require(canonical.isNotEmpty()) { "Invalid workspace root" }
            return WorkspaceRoot(directory, canonical, canonicalizer)
        }
    }
}
