package com.breakyuna.noveltranslator.nativecore.workspace

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Shared canonical-filesystem primitives for the production workspace adapters.
 *
 * Every path handed to this object has already passed [PathPolicy]. These helpers still
 * canonicalize the actual file and reject any component that is a link, so a link created
 * between validation and IO cannot redirect a read or write outside the canonical root.
 * None of the helpers follow links while scanning; a link is treated as an unsafe entry.
 */
internal object WorkspaceStorageSupport {
    /** Opens (and creates when needed) the private workspace root. */
    fun openRoot(root: File): File {
        val canonical = try {
            root.canonicalFile
        } catch (failure: IOException) {
            throw IllegalArgumentException("Workspace root is unavailable", failure)
        }
        if (!canonical.exists()) {
            check(canonical.mkdirs()) { "Workspace root could not be created" }
        }
        require(canonical.isDirectory) { "Workspace root must be a directory" }
        return canonical
    }

    /**
     * Resolves [path] below [base] and rejects absolute escapes, `..` (already rejected by
     * [WorkspacePath]) and any existing link component. Missing parents are created only when
     * [createParents] is true, and are canonicalized again after creation.
     */
    fun resolve(base: File, path: WorkspacePath, createParents: Boolean = false): File {
        val target = File(base, path.value).absoluteFile
        if (createParents) {
            // Never call mkdirs() on an unchecked parent: a planted link could redirect that
            // creation outside the root. Walk and verify one segment at a time instead.
            var current = base
            path.value.split('/').dropLast(1).forEach { segment ->
                current = File(current, segment).absoluteFile
                if (!current.exists()) {
                    val parent = current.parentFile
                    require(parent != null && isWithin(base, parent)) {
                        "Workspace directory escaped its root"
                    }
                    check(current.mkdir()) { "Workspace directory could not be created" }
                }
                val currentCanonical = canonicalOf(current)
                require(isWithin(base, currentCanonical) && currentCanonical.path == current.path) {
                    "Workspace path contains a link"
                }
            }
        }
        val canonical = canonicalOf(target)
        require(isWithin(base, canonical)) { "Workspace path escaped its root" }
        require(canonical.path == target.path) { "Workspace path contains a link" }
        return target
    }

    private fun canonicalOf(file: File): File = try {
        file.canonicalFile
    } catch (failure: IOException) {
        throw IllegalArgumentException("Workspace path is unavailable", failure)
    }

    fun ensureDirectory(base: File, path: WorkspacePath): File {
        val directory = resolve(base, path, createParents = true)
        if (!directory.exists()) {
            check(directory.mkdir()) { "Workspace directory could not be created" }
        }
        require(directory.isDirectory) { "Workspace entry is not a directory" }
        val canonical = canonicalOf(directory)
        require(isWithin(base, canonical) && canonical.path == directory.path) {
            "Workspace path contains a link"
        }
        return directory
    }

    fun isWithin(base: File, file: File): Boolean =
        file.path == base.path || file.path.startsWith(base.path + File.separator)

    /**
     * Publishes [bytes] with a temporary file, fsync and rename. The optional read-only bit is
     * applied before publication; replacing an older workspace revision remains possible
     * because the containing directory stays writable.
     */
    fun writeAtomically(target: File, bytes: ByteArray, readOnly: Boolean = false) {
        val parent = target.parentFile ?: throw IOException("Workspace target has no parent")
        require(parent.isDirectory) { "Workspace target parent is unavailable" }
        require(isSafeRegularTarget(target)) { "Workspace target is not a regular file" }
        val temporary = File.createTempFile(".lnt-workspace-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            require(temporary.length() == bytes.size.toLong()) { "Workspace write was incomplete" }
            if (readOnly) temporary.setReadOnly()
            if (!temporary.renameTo(target)) {
                // rename(2) replaces an existing entry on Android/Linux. Keep a narrow fallback
                // for filesystems that reject that, but never delete a link or directory.
                if (target.exists()) {
                    require(isSafeRegularTarget(target)) { "Workspace target changed during publication" }
                    require(target.delete()) { "Workspace target could not be replaced" }
                }
                if (!temporary.renameTo(target)) throw IOException("Workspace publication failed")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** Reads at most [limit] bytes and fails instead of allocating beyond that bound. */
    fun readBounded(file: File, limit: Long): ByteArray {
        require(limit >= 0) { "Negative read limit" }
        FileInputStream(file).use { input ->
            val output = ByteArrayOutputStream(minOf(limit, 64L * 1024).toInt())
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total = Math.addExact(total, count.toLong())
                require(total <= limit) { "Workspace read exceeded its limit" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /**
     * Lists regular files below [directory] without following links. Entries that are links are
     * skipped, not traversed; callers that require a strict directory can verify the result
     * against their manifest.
     */
    fun listRegularFiles(directory: File, maxDepth: Int, maxFiles: Int): List<File> {
        require(directory.isDirectory) { "Workspace area is unavailable" }
        val result = ArrayList<File>()
        fun visit(current: File, depth: Int) {
            val children = current.listFiles() ?: return
            children.sortedBy { it.name }.forEach { child ->
                val absolute = child.absoluteFile
                val canonical = try {
                    child.canonicalFile
                } catch (_: IOException) {
                    return@forEach
                }
                if (canonical.path != absolute.path) return@forEach
                if (child.isDirectory) {
                    if (depth < maxDepth) visit(child, depth + 1)
                } else if (child.isFile) {
                    result += absolute
                    require(result.size <= maxFiles) { "Too many workspace files" }
                }
            }
        }
        visit(directory, 0)
        return result
    }

    fun deleteRegularFile(base: File, file: File) {
        val absolute = file.absoluteFile
        require(isWithin(base, absolute)) { "Workspace delete escaped its root" }
        if (!absolute.exists()) return
        require(isSafeRegularTarget(absolute)) { "Workspace delete target is not a regular file" }
        require(absolute.delete()) { "Workspace file could not be deleted" }
    }

    /**
     * Deletes one adapter-owned directory tree. Links are removed as links, never followed, so
     * retention cannot delete project sources through a planted link.
     */
    fun deleteTree(base: File, directory: File): Boolean {
        val absolute = directory.absoluteFile
        require(isWithin(base, absolute)) { "Workspace delete escaped its root" }
        if (!absolute.exists()) return true
        val canonical = try {
            absolute.canonicalFile
        } catch (_: IOException) {
            return false
        }
        if (canonical.path != absolute.path) return absolute.delete()
        val children = absolute.listFiles()
        if (children != null) {
            children.forEach { child ->
                if (child.isDirectory) {
                    deleteTree(base, child)
                } else {
                    child.delete()
                }
            }
        }
        return absolute.delete()
    }

    fun isSafeRegularTarget(target: File): Boolean {
        if (!target.exists()) return true
        val absolute = target.absoluteFile
        val canonical = try {
            target.canonicalFile
        } catch (_: IOException) {
            return false
        }
        return canonical.path == absolute.path && absolute.isFile
    }

    fun materialId(kind: WorkspaceMaterialKind, path: WorkspacePath, sha256: String): WorkspaceMaterialId {
        val pathHash = sha256Hex((kind.name + ":" + path.value).toByteArray(Charsets.UTF_8)).take(40)
        return WorkspaceMaterialId(kind.name + "-" + pathHash + "-" + sha256)
    }

    fun kindOf(id: WorkspaceMaterialId): WorkspaceMaterialKind? {
        val name = id.value.substringBefore('-', "")
        return WorkspaceMaterialKind.entries.firstOrNull { it.name == name }
    }

    fun directoryName(kind: WorkspaceMaterialKind): String = when (kind) {
        WorkspaceMaterialKind.SOURCE -> "sources"
        WorkspaceMaterialKind.DATASET -> "datasets"
        WorkspaceMaterialKind.REFERENCE -> "reference"
        WorkspaceMaterialKind.UPLOAD -> "uploads"
        WorkspaceMaterialKind.WORK -> "work"
        WorkspaceMaterialKind.ARTIFACT -> "artifacts"
    }

    fun areaOf(path: WorkspacePath): WorkspaceMaterialKind? {
        val area = path.value.substringBefore('/')
        return WorkspaceMaterialKind.entries.firstOrNull { directoryName(it) == area }
    }

    fun mediaType(path: String): String =
        when (path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
            "txt", "srt", "vtt", "lrc", "ass", "ssa", "rpy" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "json", "jsonl" -> "application/json"
            "csv" -> "text/csv"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "zip", "epub" -> "application/zip"
            else -> "application/octet-stream"
        }
}
