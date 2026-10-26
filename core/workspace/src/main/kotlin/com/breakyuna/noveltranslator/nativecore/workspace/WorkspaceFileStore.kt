package com.breakyuna.noveltranslator.nativecore.workspace

import com.breakyuna.noveltranslator.nativecore.domain.RunId
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * File-backed production adapter for the pure workspace core. It owns one private root and
 * enforces path canonicalization on every read/write, so a symlink inside the root cannot
 * escape to project sources or arbitrary storage. It does not run scripts and does not decide
 * approvals; callers still use [WorkspaceRuntime] and a real approval/apply adapter.
 */
class WorkspaceFileStore(
    root: File,
    private val policy: PathPolicy = PathPolicy.DEFAULT,
) : WorkspaceMaterialReadPort, WorkspaceArtifactSink, WorkspaceSnapshotPort {
    private val base: File = root.canonicalFile.also { require(it.isDirectory || it.mkdirs()) { "Workspace root unavailable" } }

    override suspend fun list(kind: WorkspaceMaterialKind?): List<WorkspaceMaterialSummary> {
        val kinds = if (kind == null) WorkspaceMaterialKind.entries else listOf(kind)
        val result = ArrayList<WorkspaceMaterialSummary>()
        kinds.forEach { area ->
            val directory = area.directory()
            if (directory.isDirectory) listFiles(directory, area, result)
        }
        return result.sortedWith(compareBy<WorkspaceMaterialSummary> { it.kind.name }.thenBy { it.path.value })
    }

    override suspend fun summary(id: WorkspaceMaterialId): WorkspaceMaterialSummary? {
        val kind = kindOf(id) ?: return null
        return list(kind).firstOrNull { it.id == id }
    }

    override suspend fun read(id: WorkspaceMaterialId, limitBytes: Long): WorkspaceMaterialContent {
        require(limitBytes >= 0)
        val kind = kindOf(id) ?: throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.NOT_FOUND)
        // The identity binds one path to the fingerprint observed when it was listed. Resolve the
        // path component so a rewritten file is reported as a mismatch instead of new content.
        val prefix = id.value.substringBeforeLast('-')
        val expectedSha = id.value.substringAfterLast('-')
        val summary = list(kind).firstOrNull { it.id.value.substringBeforeLast('-') == prefix }
            ?: throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.NOT_FOUND)
        if (summary.sha256 != expectedSha) {
            throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.FINGERPRINT_MISMATCH)
        }
        if (summary.sizeBytes > limitBytes) {
            throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.TOO_LARGE)
        }
        val file = resolve(summary.path)
        if (!file.isFile) throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.NOT_FOUND)
        val bytes = try { readBounded(file, minOf(limitBytes, WorkspaceHardLimits.MAX_SINGLE_BYTES)) }
        catch (_: Exception) { throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.READ_FAILED) }
        if (sha256Hex(bytes) != summary.sha256) {
            throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.FINGERPRINT_MISMATCH)
        }
        return WorkspaceMaterialContent(summary.id, summary.sha256, bytes)
    }

    override suspend fun fingerprint(path: WorkspacePath): WorkspaceDigest? {
        val file = resolve(path)
        return if (file.isFile) WorkspaceDigest(sha256Hex(file)) else null
    }

    /**
     * Publishes one user-provided upload under `uploads/`. The path is already structurally
     * canonical; this adapter re-resolves it below the root, refuses read-only areas, writes with
     * temp+fsync+rename, and returns the verified material identity. No existing file is
     * overwritten.
     */
    suspend fun saveUpload(path: WorkspacePath, bytes: ByteArray): WorkspaceMaterialSummary {
        require(path.value.startsWith("uploads/")) { "Upload path must be under uploads/" }
        require(bytes.size.toLong() in 0..WorkspaceHardLimits.MAX_SINGLE_BYTES) { "Upload too large" }
        val target = WorkspaceStorageSupport.resolve(base, path, createParents = true)
        require(!target.exists()) { "Upload already exists" }
        WorkspaceStorageSupport.writeAtomically(target, bytes)
        return list(WorkspaceMaterialKind.UPLOAD).firstOrNull { it.path == path }
            ?: throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.NOT_FOUND)
    }

    override suspend fun save(runId: RunId, drafts: List<WorkspaceArtifactDraft>): List<WorkspaceArtifactSummary> {
        if (drafts.isEmpty()) return emptyList()
        require(drafts.size <= WorkspaceHardLimits.MAX_ARTIFACTS) { "Too many artifacts" }
        val directory = File(base, "artifacts/" + sha256Hex(runId.value.toByteArray(Charsets.UTF_8)).take(32))
        check(directory.isDirectory || directory.mkdirs()) { "Artifact directory unavailable" }
        require(drafts.sumOf { it.sizeBytes } <= WorkspaceHardLimits.MAX_TOTAL_BYTES) { "Artifact batch too large" }
        val summaries = ArrayList<WorkspaceArtifactSummary>(drafts.size)
        val seen = HashSet<String>(drafts.size * 2)
        for (draft in drafts) {
            val key = policy.identityKey(draft.path)
            require(seen.add(key)) { "Duplicate artifact path" }
            val target = File(directory, draft.path.value)
            val canonical = target.canonicalFile
            requireWithin(canonical)
            require(!canonical.exists()) { "Artifact already exists" }
            val parent = canonical.parentFile ?: throw IOException("Artifact parent unavailable")
            check(parent.isDirectory || parent.mkdirs()) { "Artifact parent unavailable" }
            val temp = File.createTempFile(".lnt-artifact-", ".tmp", parent)
            try {
                FileOutputStream(temp).use { output ->
                    output.write(draft.bytes)
                    output.flush()
                    output.fd.sync()
                }
                require(temp.length() == draft.sizeBytes)
                if (!temp.renameTo(canonical)) throw IOException("Artifact publication failed")
            } finally {
                if (temp.exists()) temp.delete()
            }
            summaries += WorkspaceArtifactSummary(
                WorkspaceArtifactId("artifact-" + sha256Hex((runId.value + ":" + draft.path.value).toByteArray()).take(40)),
                draft.path, draft.mediaType, draft.sizeBytes, draft.sha256,
            )
        }
        return summaries
    }

    private fun listFiles(directory: File, kind: WorkspaceMaterialKind, result: MutableList<WorkspaceMaterialSummary>) {
        val files = directory.walkTopDown()
            .maxDepth(policy.maxDepth)
            .filter { it.isFile }
            .toList()
            .sortedBy { it.path }
        require(files.size <= WorkspaceHardLimits.MAX_MATERIALS) { "Too many materials" }
        var ordinal = 0
        for (file in files) {
            val canonical = try { file.canonicalFile } catch (_: IOException) { continue }
            if (!isWithin(canonical)) continue
            val relative = canonical.relativeTo(base).path.replace(File.separatorChar, '/')
            val path = try { policy.parse(relative) } catch (_: InvalidWorkspacePath) { continue }
            if (path.value.substringBefore('/') != kind.directoryName()) continue
            val size = canonical.length()
            if (size !in 0..WorkspaceHardLimits.MAX_SINGLE_BYTES) continue
            val hash = try { sha256Hex(canonical) } catch (_: IOException) { continue }
            val pathHash = sha256Hex((kind.name + ":" + path.value).toByteArray()).take(40)
            result += WorkspaceMaterialSummary(
                WorkspaceMaterialId(kind.name + "-" + pathHash + "-" + hash),
                kind, path, hash, size, mediaType(path.value), ordinal++,
            )
        }
    }

    private fun resolve(path: WorkspacePath): File {
        val parsed = policy.parse(path.value)
        val file = File(base, parsed.value).canonicalFile
        requireWithin(file)
        return file
    }

    private fun requireWithin(file: File) {
        require(isWithin(file)) { "Workspace path escaped its root" }
    }

    private fun isWithin(file: File): Boolean =
        file == base || file.path.startsWith(base.path + File.separator)

    private fun kindOf(id: WorkspaceMaterialId): WorkspaceMaterialKind? {
        val name = id.value.substringBefore('-', "")
        return WorkspaceMaterialKind.entries.firstOrNull { it.name == name }
    }

    private fun WorkspaceMaterialKind.directoryName(): String = when (this) {
        WorkspaceMaterialKind.SOURCE -> "sources"
        WorkspaceMaterialKind.DATASET -> "datasets"
        WorkspaceMaterialKind.REFERENCE -> "reference"
        WorkspaceMaterialKind.UPLOAD -> "uploads"
        WorkspaceMaterialKind.WORK -> "work"
        WorkspaceMaterialKind.ARTIFACT -> "artifacts"
    }

    private fun WorkspaceMaterialKind.directory(): File = File(base, directoryName())

    private fun mediaType(path: String): String = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "txt", "md", "csv", "srt", "vtt", "lrc", "ass", "ssa", "rpy" -> "text/plain"
        "json", "jsonl" -> "application/json"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun readBounded(file: File, limit: Long): ByteArray {
        require(limit >= 0)
        FileInputStream(file).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total = Math.addExact(total, count.toLong())
                require(total <= limit)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
