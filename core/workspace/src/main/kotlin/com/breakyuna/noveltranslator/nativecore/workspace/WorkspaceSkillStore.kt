package com.breakyuna.noveltranslator.nativecore.workspace

import com.breakyuna.noveltranslator.nativecore.domain.InvalidJsonPayload
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

class WorkspaceSkillStoreException(
    val reason: Reason,
    val skillId: WorkspaceSkillId? = null,
) : IllegalStateException("Workspace skill store: $reason") {
    enum class Reason {
        NOT_FOUND,
        ALREADY_EXISTS,
        STALE_FINGERPRINT,
        READ_ONLY,
        INVALID_ID,
        INVALID_METADATA,
        TOO_LARGE,
        DUPLICATE_NAME,
        IO_FAILED,
        PATH_ESCAPE,
    }

    /** No filesystem path is included; the skill id is an opaque logical identity. */
    override fun toString(): String =
        "WorkspaceSkillStoreException($reason" + (skillId?.let { ", id=" + it.value } ?: "") + ")"
}

/** Metadata plus content fingerprint. The digest changes on any metadata/content mutation. */
class WorkspaceSkillRecord(
    val summary: WorkspaceSkillSummary,
    val fingerprint: WorkspaceDigest,
) {
    override fun toString(): String =
        "WorkspaceSkillRecord(id=${summary.id.value}, source=${summary.source}, fingerprint=${fingerprint.sha256})"
}

/** Bounded input for create/update. Name, description, order, tags and policy are validated by the store. */
class WorkspaceSkillDraft(
    val id: WorkspaceSkillId,
    val name: String,
    val content: String,
    val description: String = "",
    val enabled: Boolean = true,
    val order: Int = 0,
    languages: Set<String> = emptySet(),
    tags: Set<String> = emptySet(),
    val policy: WorkspaceSkillPolicy = WorkspaceSkillPolicy.DEFAULT,
) {
    val languages: Set<String> = workspaceFrozenSet(normalize(languages))
    val tags: Set<String> = workspaceFrozenSet(normalize(tags))

    init {
        require(order >= 0) { "Invalid skill order" }
        require(this.languages.size <= WorkspaceSkillLimits.MAX_LANGUAGES) { "Too many skill languages" }
        require(this.tags.size <= WorkspaceSkillLimits.MAX_TAGS) { "Too many skill tags" }
        require('\u0000' !in content) { "NUL in skill content" }
        requireWellFormedUnicode(content)
        require(content.length <= WorkspaceSkillLimits.MAX_CONTENT_BYTES) { "Skill content too large" }
        require(content.toByteArray(Charsets.UTF_8).size <= WorkspaceSkillLimits.MAX_CONTENT_BYTES) {
            "Skill content too large"
        }
    }

    private companion object {
        fun normalize(values: Set<String>): Set<String> {
            val result = LinkedHashSet<String>(values.size)
            values.forEach { value ->
                val normalized = value.trim().lowercase(Locale.ROOT)
                require(normalized.isNotEmpty()) { "Empty skill label" }
                result += normalized
            }
            return result
        }
    }
}

/**
 * Mutable skill catalog backed by a private user root plus an optional read-only built-in source.
 * Every mutation carries a CAS fingerprint taken from [WorkspaceSkillRecord.fingerprint]; a
 * stale fingerprint fails instead of overwriting a concurrent edit. User skills override a
 * built-in with the same id or display name; built-ins themselves are never modified.
 */
interface WorkspaceSkillCatalog {
    suspend fun list(): List<WorkspaceSkillRecord>

    suspend fun record(id: WorkspaceSkillId): WorkspaceSkillRecord?

    suspend fun readContent(id: WorkspaceSkillId, maxChars: Int = WorkspaceSkillLimits.MAX_CONTENT_BYTES): String?

    suspend fun create(draft: WorkspaceSkillDraft): WorkspaceSkillRecord

    suspend fun update(
        id: WorkspaceSkillId,
        draft: WorkspaceSkillDraft,
        expectedFingerprint: WorkspaceDigest,
    ): WorkspaceSkillRecord

    suspend fun delete(id: WorkspaceSkillId, expectedFingerprint: WorkspaceDigest)

    suspend fun setEnabled(
        id: WorkspaceSkillId,
        enabled: Boolean,
        expectedFingerprint: WorkspaceDigest,
    ): WorkspaceSkillRecord

    suspend fun setOrder(
        id: WorkspaceSkillId,
        order: Int,
        expectedFingerprint: WorkspaceDigest,
    ): WorkspaceSkillRecord

    suspend fun rename(
        id: WorkspaceSkillId,
        name: String,
        expectedFingerprint: WorkspaceDigest,
    ): WorkspaceSkillRecord
}

/**
 * File-backed [WorkspaceSkillCatalog] and [WorkspaceSkillDocumentPort].
 *
 * Layout:
 * ```
 * <userRoot>/<id>/skill.json
 * <userRoot>/<id>/SKILL.md
 * ```
 *
 * Writes use a temporary file in the skill directory followed by publication at the final path.
 * Metadata is written last and records the content SHA-256, so a partial write is detected on
 * the next read instead of being served as valid content. Directories, ids and files are
 * canonicalized under the private root; unknown files can be removed by [delete] but are never
 * interpreted as skills.
 */
class WorkspaceSkillStore(
    userRoot: File,
    private val builtIns: WorkspaceSkillResourceSource? = null,
    private val policy: PathPolicy = PathPolicy.DEFAULT,
    canonicalizer: (File) -> String = { it.canonicalPath },
) : WorkspaceSkillCatalog, WorkspaceSkillDocumentPort, WorkspaceSkillFilePort {
    constructor(
        userRoot: File,
        builtInRoot: File,
        policy: PathPolicy = PathPolicy.DEFAULT,
    ) : this(
        userRoot = userRoot,
        builtIns = FileWorkspaceSkillResourceSource(builtInRoot, policy),
        policy = policy,
    )

    private val lock = Any()
    private val root: WorkspaceRoot = WorkspaceRoot.open(
        userRoot.also { require(it.isDirectory || it.mkdirs()) { "Skill root unavailable" } },
        canonicalizer,
    )
    private val baseDirectory: File = root.directory.canonicalFile

    override suspend fun list(): List<WorkspaceSkillRecord> {
        val users = userRecords()
        ensureUniqueUserNames(users)
        val merged = LinkedHashMap<String, WorkspaceSkillRecord>()
        builtInRecords().forEach { merged[it.summary.id.value] = it }
        users.forEach { user ->
            merged[user.summary.id.value] = user
            val shadowed = merged.values.filter { candidate ->
                candidate.summary.source == WorkspaceSkillSource.BUILT_IN &&
                    candidate.summary.conflictNameKey == user.summary.conflictNameKey &&
                    candidate.summary.id != user.summary.id
            }
            shadowed.forEach { merged.remove(it.summary.id.value) }
        }
        return workspaceFrozenList(merged.values.sortedWith(SKILL_COMPARATOR))
    }

    override suspend fun record(id: WorkspaceSkillId): WorkspaceSkillRecord? {
        validateSkillPath(id, policy)
        return list().firstOrNull { it.summary.id == id }
    }

    override suspend fun readContent(id: WorkspaceSkillId, maxChars: Int): String? {
        require(maxChars > 0) { "Invalid skill read limit" }
        validateSkillPath(id, policy)
        val record = record(id) ?: return null
        val bytes = when (record.summary.source) {
            WorkspaceSkillSource.USER -> readUserBytes(id, CONTENT_FILE, WorkspaceSkillLimits.MAX_CONTENT_BYTES)
            WorkspaceSkillSource.BUILT_IN -> readBuiltInContent(id)
        } ?: return null
        if (sha256Hex(bytes) != record.summary.contentSha256) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.STALE_FINGERPRINT, id)
        }
        val text = try {
            decodeUtf8(bytes)
        } catch (failure: CharacterCodingException) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        return truncateChars(text, maxChars)
    }

    override suspend fun read(id: WorkspaceSkillId, maxChars: Int): String? = readContent(id, maxChars)

    override suspend fun files(id: WorkspaceSkillId): List<WorkspaceSkillFile> {
        validateSkillPath(id, policy)
        val record = record(id) ?: throw WorkspaceSkillStoreException(
            WorkspaceSkillStoreException.Reason.NOT_FOUND, id)
        return when (record.summary.source) {
            WorkspaceSkillSource.BUILT_IN -> builtIns?.files(id).orEmpty()
            WorkspaceSkillSource.USER -> synchronized(lock) {
                val directory = resolveUserDirectory(id.value)
                if (!directory.isDirectory) throw WorkspaceSkillStoreException(
                    WorkspaceSkillStoreException.Reason.NOT_FOUND, id)
                val result = ArrayList<WorkspaceSkillFile>()
                collectFiles(id, directory, "", result, 0L)
                workspaceFrozenList(result.sortedBy { it.path.value })
            }
        }
    }

    override suspend fun readFile(id: WorkspaceSkillId, relativePath: String,
        maxBytes: Int): ByteArray? {
        require(maxBytes in 1..WorkspaceSkillLimits.MAX_CONTENT_BYTES) { "Invalid skill file limit" }
        validateSkillPath(id, policy)
        val record = record(id) ?: return null
        val logical = "${id.value}/$relativePath"
        if (relativePath.isBlank() || relativePath.startsWith("/") || '\\' in relativePath ||
            relativePath.any(Char::isISOControl) || relativePath == METADATA_FILE) return null
        val parsed = try { policy.parse(logical) } catch (_: InvalidWorkspacePath) { return null }
        if (parsed.value != logical || pathSegments(parsed.value).any { it == ".." || it == "." }) return null
        return when (record.summary.source) {
            WorkspaceSkillSource.BUILT_IN -> builtIns?.readFile(id, relativePath, maxBytes)
            WorkspaceSkillSource.USER -> {
                val file = try { resolveUserFile(id.value, relativePath) }
                catch (_: WorkspaceSkillStoreException) { return null }
                if (!file.isFile || !isWithin(file.canonicalFile) ||
                    !file.canonicalPath.startsWith(resolveUserDirectory(id.value).canonicalPath + File.separator)) return null
                if (file.length() > maxBytes) throw WorkspaceSkillStoreException(
                    WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
                try {
                    FileInputStream(file).use { readSkillBytesBounded(it, maxBytes, id) }
                } catch (failure: WorkspaceSkillStoreException) { throw failure }
                catch (_: Exception) { throw WorkspaceSkillStoreException(
                    WorkspaceSkillStoreException.Reason.IO_FAILED, id) }
            }
        }
    }

    /** Resource CAS is separate from SKILL.md/metadata CAS. Execution callers hold the runtime
     * skill-write lease, so a running script cannot observe half of a package edit. */
    suspend fun writeResource(id: WorkspaceSkillId, path: String, text: String,
        expectedSkill: WorkspaceDigest, expectedSha256: String?): WorkspaceSkillFile = synchronized(lock) {
        val existing = userRecord(validatedId(id)) ?: throw WorkspaceSkillStoreException(
            WorkspaceSkillStoreException.Reason.READ_ONLY, id)
        ensureFingerprint(existing, expectedSkill, id)
        val target = resourceTarget(id, path)
        checkResourceVersion(id, target, expectedSha256)
        val bytes = text.toByteArray(Charsets.UTF_8)
        // Reject malformed surrogate input instead of silently writing replacement characters.
        require(decodeUtf8(bytes) == text) { "Invalid resource text" }
        if (bytes.size > WorkspaceSkillLimits.MAX_CONTENT_BYTES) throw WorkspaceSkillStoreException(
            WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
        val listed = ArrayList<WorkspaceSkillFile>()
        val total = collectFiles(id, resolveUserDirectory(id.value), "", listed, 0L)
        if ((expectedSha256 == null && listed.size >= MAX_FILES_PER_SKILL) ||
            total - (if (target.isFile) target.length() else 0L) + bytes.size > WorkspaceSkillLimits.MAX_TOTAL_BYTES)
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
        val parent = requireNotNull(target.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw WorkspaceSkillStoreException(
            WorkspaceSkillStoreException.Reason.IO_FAILED, id)
        // Resolve again after directory creation, preserving canonical root checks.
        writeFileAtomic(resourceTarget(id, path), bytes, id)
        WorkspaceSkillFile(WorkspacePath(id.value + "/" + path), bytes.size.toLong(), sha256Hex(bytes), true)
    }

    suspend fun deleteResource(id: WorkspaceSkillId, path: String, expectedSkill: WorkspaceDigest,
        expectedSha256: String) = synchronized(lock) {
        val existing = userRecord(validatedId(id)) ?: throw WorkspaceSkillStoreException(
            WorkspaceSkillStoreException.Reason.READ_ONLY, id)
        ensureFingerprint(existing, expectedSkill, id)
        val target = resourceTarget(id, path)
        checkResourceVersion(id, target, expectedSha256)
        if (!target.delete()) throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED, id)
    }

    suspend fun renameResource(id: WorkspaceSkillId, path: String, destination: String,
        expectedSkill: WorkspaceDigest, expectedSha256: String) = synchronized(lock) {
        val existing = userRecord(validatedId(id)) ?: throw WorkspaceSkillStoreException(
            WorkspaceSkillStoreException.Reason.READ_ONLY, id)
        ensureFingerprint(existing, expectedSkill, id)
        val source = resourceTarget(id, path)
        val target = resourceTarget(id, destination)
        checkResourceVersion(id, source, expectedSha256)
        checkResourceVersion(id, target, null)
        val parent = requireNotNull(target.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw WorkspaceSkillStoreException(
            WorkspaceSkillStoreException.Reason.IO_FAILED, id)
        if (!source.renameTo(resourceTarget(id, destination))) throw WorkspaceSkillStoreException(
            WorkspaceSkillStoreException.Reason.IO_FAILED, id)
    }

    private fun resourceTarget(id: WorkspaceSkillId, path: String): File {
        val parsed = try { policy.parse(id.value + "/" + path) }
        catch (_: InvalidWorkspacePath) { throw WorkspaceSkillStoreException(
            WorkspaceSkillStoreException.Reason.PATH_ESCAPE, id) }
        if (path.isBlank() || path.startsWith("/") || '\\' in path || path.any(Char::isISOControl) ||
            parsed.value != id.value + "/" + path || pathSegments(path).any { it in setOf(".", "..", METADATA_FILE) } ||
            path == CONTENT_FILE || !isTextFile(path)) throw WorkspaceSkillStoreException(
                WorkspaceSkillStoreException.Reason.PATH_ESCAPE, id)
        val target = resolveUserFile(id.value, path)
        val directory = resolveUserDirectory(id.value)
        if (!target.canonicalPath.startsWith(directory.canonicalPath + File.separator))
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.PATH_ESCAPE, id)
        return target
    }

    private fun checkResourceVersion(id: WorkspaceSkillId, file: File, expected: String?) {
        if (expected == null) {
            if (file.exists()) throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.ALREADY_EXISTS, id)
        } else {
            requireSha256Hex(expected)
            if (!file.isFile) throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.NOT_FOUND, id)
            val bytes = FileInputStream(file).use { readSkillBytesBounded(it, WorkspaceSkillLimits.MAX_CONTENT_BYTES, id) }
            if (sha256Hex(bytes) != expected) throw WorkspaceSkillStoreException(
                WorkspaceSkillStoreException.Reason.STALE_FINGERPRINT, id)
        }
    }

    private fun collectFiles(id: WorkspaceSkillId, directory: File, prefix: String,
        result: MutableList<WorkspaceSkillFile>, total: Long, depth: Int = 0,
        visited: MutableSet<String> = HashSet()): Long {
        if (depth > 16 || visited.size >= 256 || !visited.add(directory.canonicalPath))
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.PATH_ESCAPE, id)
        if (result.size >= MAX_FILES_PER_SKILL) throw WorkspaceSkillStoreException(
            WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
        var bytes = total
        val children = directory.listFiles() ?: return bytes
        for (child in children.sortedBy { it.name }) {
            if (child.name == METADATA_FILE) continue
            if (!isWithin(child.canonicalFile) ||
                !child.canonicalPath.startsWith(resolveUserDirectory(id.value).canonicalPath + File.separator))
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.PATH_ESCAPE, id)
            if (child.isDirectory) {
                bytes = collectFiles(id, child, "$prefix${child.name}/", result, bytes, depth + 1, visited)
                if (bytes > WorkspaceSkillLimits.MAX_TOTAL_BYTES) throw WorkspaceSkillStoreException(
                    WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
                continue
            }
            if (!child.isFile) continue
            if (result.size >= MAX_FILES_PER_SKILL) throw WorkspaceSkillStoreException(
                WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
            if (child.length() > WorkspaceSkillLimits.MAX_CONTENT_BYTES) throw WorkspaceSkillStoreException(
                WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
            bytes += child.length()
            if (bytes > WorkspaceSkillLimits.MAX_TOTAL_BYTES) throw WorkspaceSkillStoreException(
                WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
            val content = FileInputStream(child).use { readSkillBytesBounded(it,
                WorkspaceSkillLimits.MAX_CONTENT_BYTES, id) }
            val path = prefix + child.name
            result += WorkspaceSkillFile(WorkspacePath(id.value + "/" + path), content.size.toLong(),
                sha256Hex(content), isTextFile(path))
        }
        return bytes
    }

    private fun isTextFile(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

    private fun pathSegments(value: String): List<String> = value.split('/')

    override suspend fun create(draft: WorkspaceSkillDraft): WorkspaceSkillRecord = install(draft, emptyMap())

    /** Publish metadata last; an interrupted package is never visible as an installed skill. */
    suspend fun install(draft: WorkspaceSkillDraft, resources: Map<String, String>): WorkspaceSkillRecord = synchronized(lock) {
        require(resources.size <= 64)
        val resourceBytes = resources.mapValues { (path, text) ->
            resourceTarget(draft.id, path)
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(decodeUtf8(bytes) == text && bytes.size <= WorkspaceSkillLimits.MAX_CONTENT_BYTES)
            bytes
        }
        require(resourceBytes.values.sumOf { it.size.toLong() } + draft.content.toByteArray(Charsets.UTF_8).size <= WorkspaceSkillLimits.MAX_TOTAL_BYTES)
        val rawId = validatedId(draft.id)
        val directory = resolveUserDirectory(rawId)
        if (directory.exists()) throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.ALREADY_EXISTS, draft.id)
        val users = userRecords()
        if (users.size >= WorkspaceSkillLimits.MAX_SKILLS) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE, draft.id)
        }
        val contentBytes = draft.content.toByteArray(Charsets.UTF_8)
        val summary = buildSummary(draft, rawId, WorkspaceSkillSource.USER, sha256Hex(contentBytes))
        ensureNameAvailable(users, summary)
        if (!directory.mkdir()) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED, draft.id)
        }
        try {
            writeFileAtomic(resolveUserFile(rawId, CONTENT_FILE), contentBytes, draft.id)
            resourceBytes.forEach { (path, bytes) ->
                val target = resourceTarget(draft.id, path)
                require(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                writeFileAtomic(resourceTarget(draft.id, path), bytes, draft.id)
            }
            writeFileAtomic(resolveUserFile(rawId, METADATA_FILE), WorkspaceSkillMetadataCodec.encode(summary), draft.id)
            recordOf(summary)
        } catch (failure: Throwable) {
            deleteTree(directory)
            if (failure is WorkspaceSkillStoreException) throw failure
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED, draft.id)
        }
    }

    override suspend fun update(
        id: WorkspaceSkillId,
        draft: WorkspaceSkillDraft,
        expectedFingerprint: WorkspaceDigest,
    ): WorkspaceSkillRecord {
        require(draft.id == id) { "Draft identity does not match" }
        val builtIn = builtInRecord(id)
        return synchronized(lock) {
            val rawId = validatedId(id)
            val existing = requireUserRecord(id, rawId, builtIn)
            ensureFingerprint(existing, expectedFingerprint, id)
            val contentBytes = draft.content.toByteArray(Charsets.UTF_8)
            val summary = buildSummary(draft, rawId, WorkspaceSkillSource.USER, sha256Hex(contentBytes))
            ensureNameAvailable(userRecords().filter { it.summary.id != id }, summary)
            val oldContent = readUserBytes(id, CONTENT_FILE, WorkspaceSkillLimits.MAX_CONTENT_BYTES)
            val oldMetadata = readUserBytes(id, METADATA_FILE, WorkspaceSkillLimits.MAX_METADATA_BYTES)
            try {
                writeFileAtomic(resolveUserFile(rawId, CONTENT_FILE), contentBytes, id)
                writeFileAtomic(
                    resolveUserFile(rawId, METADATA_FILE),
                    WorkspaceSkillMetadataCodec.encode(summary),
                    id,
                )
                recordOf(summary)
            } catch (failure: Throwable) {
                runCatching { writeFileAtomic(resolveUserFile(rawId, CONTENT_FILE), oldContent, id) }
                runCatching { writeFileAtomic(resolveUserFile(rawId, METADATA_FILE), oldMetadata, id) }
                if (failure is WorkspaceSkillStoreException) throw failure
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED, id)
            }
        }
    }

    override suspend fun delete(id: WorkspaceSkillId, expectedFingerprint: WorkspaceDigest) {
        val builtIn = builtInRecord(id)
        synchronized(lock) {
            val rawId = validatedId(id)
            val existing = requireUserRecord(id, rawId, builtIn)
            ensureFingerprint(existing, expectedFingerprint, id)
            deleteTree(resolveUserDirectory(rawId))
        }
    }

    override suspend fun setEnabled(
        id: WorkspaceSkillId,
        enabled: Boolean,
        expectedFingerprint: WorkspaceDigest,
    ): WorkspaceSkillRecord {
        val builtIn = builtInRecord(id)
        return mutateMetadata(id, expectedFingerprint, builtIn) { summary ->
            summary.withEnabled(enabled)
        }
    }

    override suspend fun setOrder(
        id: WorkspaceSkillId,
        order: Int,
        expectedFingerprint: WorkspaceDigest,
    ): WorkspaceSkillRecord {
        require(order >= 0) { "Invalid skill order" }
        val builtIn = builtInRecord(id)
        return mutateMetadata(id, expectedFingerprint, builtIn) { summary -> summary.withOrder(order) }
    }

    override suspend fun rename(
        id: WorkspaceSkillId,
        name: String,
        expectedFingerprint: WorkspaceDigest,
    ): WorkspaceSkillRecord {
        validateSkillPath(id, policy)
        val builtIn = builtInRecord(id)
        return mutateMetadata(id, expectedFingerprint, builtIn, checkName = true) { summary ->
            WorkspaceSkillSummary(
                id = summary.id,
                name = name,
                description = summary.description,
                enabled = summary.enabled,
                order = summary.order,
                source = summary.source,
                languages = summary.languages,
                tags = summary.tags,
                contentPath = summary.contentPath,
                contentSha256 = summary.contentSha256,
                policy = summary.policy,
            )
        }
    }

    private fun mutateMetadata(
        id: WorkspaceSkillId,
        expectedFingerprint: WorkspaceDigest,
        builtIn: WorkspaceSkillRecord?,
        checkName: Boolean = false,
        transform: (WorkspaceSkillSummary) -> WorkspaceSkillSummary,
    ): WorkspaceSkillRecord = synchronized(lock) {
        val rawId = validatedId(id)
        val user = userRecord(rawId)
        val existing = user ?: builtIn?.let { builtInPreferences(it) }
            ?: throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.NOT_FOUND, id)
        if (user == null && checkName) throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.READ_ONLY, id)
        ensureFingerprint(existing, expectedFingerprint, id)
        val summary = try {
            transform(existing.summary)
        } catch (failure: IllegalArgumentException) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        if (checkName) {
            ensureNameAvailable(userRecords().filter { it.summary.id != id }, summary)
        }
        if (user == null) {
            val bytes = buildJsonObject {
                put("enabled", summary.enabled)
                put("order", summary.order)
            }.toString().toByteArray(Charsets.UTF_8)
            writeFileAtomic(builtInPreferenceFile(id), bytes, id)
        } else writeFileAtomic(resolveUserFile(rawId, METADATA_FILE), WorkspaceSkillMetadataCodec.encode(summary), id)
        recordOf(summary)
    }

    private fun builtInPreferenceFile(id: WorkspaceSkillId): File {
        validatedId(id)
        val file = File(baseDirectory, id.value + ".builtin-settings.json").canonicalFile
        if (!isWithin(file)) throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.PATH_ESCAPE, id)
        return file
    }

    private fun builtInPreferences(record: WorkspaceSkillRecord): WorkspaceSkillRecord {
        val file = builtInPreferenceFile(record.summary.id)
        if (!file.exists()) return record
        val bytes = FileInputStream(file).use { readSkillBytesBounded(it, 1024, record.summary.id) }
        val values = JsonObjectValue.parse(decodeUtf8(bytes), JsonInputLimits(1024, 4)).json
        if (values.keys != setOf("enabled", "order")) throw WorkspaceSkillStoreException(
            WorkspaceSkillStoreException.Reason.INVALID_METADATA, record.summary.id)
        val enabled = (values["enabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, record.summary.id)
        val order = (values["order"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, record.summary.id)
        if (order < 0) throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, record.summary.id)
        return recordOf(record.summary.withEnabled(enabled).withOrder(order))
    }

    private fun requireUserRecord(
        id: WorkspaceSkillId,
        rawId: String,
        builtIn: WorkspaceSkillRecord?,
    ): WorkspaceSkillRecord {
        userRecord(rawId)?.let { return it }
        if (builtIn != null) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.READ_ONLY, id)
        }
        throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.NOT_FOUND, id)
    }

    private fun ensureFingerprint(
        existing: WorkspaceSkillRecord,
        expected: WorkspaceDigest,
        id: WorkspaceSkillId,
    ) {
        if (existing.fingerprint != expected) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.STALE_FINGERPRINT, id)
        }
    }

    private fun ensureNameAvailable(others: List<WorkspaceSkillRecord>, summary: WorkspaceSkillSummary) {
        val conflict = others.firstOrNull {
            it.summary.id != summary.id && it.summary.conflictNameKey == summary.conflictNameKey
        }
        if (conflict != null) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.DUPLICATE_NAME, summary.id)
        }
    }

    private fun ensureUniqueUserNames(records: List<WorkspaceSkillRecord>) {
        val seen = HashMap<String, WorkspaceSkillId>(records.size * 2)
        records.forEach { record ->
            val key = record.summary.conflictNameKey
            val existing = seen.putIfAbsent(key, record.summary.id)
            if (existing != null && existing != record.summary.id) {
                throw WorkspaceSkillStoreException(
                    WorkspaceSkillStoreException.Reason.DUPLICATE_NAME,
                    record.summary.id,
                )
            }
        }
    }

    private fun userRecords(): List<WorkspaceSkillRecord> {
        val entries = root.directory.listFiles()
            ?: throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED)
        val candidates = entries
            .filter { it.isDirectory && File(it, METADATA_FILE).isFile }
            .sortedBy { it.name }
        if (candidates.size > WorkspaceSkillLimits.MAX_SKILLS) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE)
        }
        val result = ArrayList<WorkspaceSkillRecord>(candidates.size)
        var total = 0L
        candidates.forEach { directory ->
            val id = try {
                WorkspaceSkillId(directory.name)
            } catch (failure: IllegalArgumentException) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_ID)
            }
            validateSkillPath(id, policy)
            val metadata = readUserBytes(id, METADATA_FILE, WorkspaceSkillLimits.MAX_METADATA_BYTES)
            total += metadata.size.toLong()
            if (total > WorkspaceSkillLimits.MAX_TOTAL_BYTES) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
            }
            result += WorkspaceSkillMetadataCodec.decodeMetadata(
                metadata = metadata,
                id = id,
                source = WorkspaceSkillSource.USER,
            )
        }
        return result
    }

    private fun userRecord(rawId: String): WorkspaceSkillRecord? {
        val directory = resolveUserDirectory(rawId)
        if (!directory.isDirectory) return null
        if (!resolveUserFile(rawId, METADATA_FILE).isFile) return null
        val id = WorkspaceSkillId(rawId)
        val metadata = readUserBytes(id, METADATA_FILE, WorkspaceSkillLimits.MAX_METADATA_BYTES)
        val content = readUserBytes(id, CONTENT_FILE, WorkspaceSkillLimits.MAX_CONTENT_BYTES)
        return WorkspaceSkillMetadataCodec.decode(metadata, id, WorkspaceSkillSource.USER, content)
    }

    private suspend fun builtInRecords(): List<WorkspaceSkillRecord> =
        builtIns?.list()?.map { resource ->
            synchronized(lock) { builtInPreferences(WorkspaceSkillMetadataCodec.decodeMetadata(
                metadata = resource.metadataBytes,
                id = resource.id,
                source = WorkspaceSkillSource.BUILT_IN,
            )) }
        } ?: emptyList()

    private suspend fun builtInRecord(id: WorkspaceSkillId): WorkspaceSkillRecord? =
        builtInRecords().firstOrNull { it.summary.id == id }

    private suspend fun readBuiltInContent(id: WorkspaceSkillId): ByteArray? =
        builtIns?.list()?.firstOrNull { it.id == id }?.contentBytes

    private fun readUserBytes(id: WorkspaceSkillId, name: String, maxBytes: Int): ByteArray {
        val file = resolveUserFile(id.value, name)
        if (!file.isFile) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        if (file.length() > maxBytes) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
        }
        return try {
            FileInputStream(file).use { readSkillBytesBounded(it, maxBytes, id) }
        } catch (failure: WorkspaceSkillStoreException) {
            throw failure
        } catch (failure: IOException) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED, id)
        }
    }

    private fun buildSummary(
        draft: WorkspaceSkillDraft,
        rawId: String,
        source: WorkspaceSkillSource,
        contentSha256: String,
    ): WorkspaceSkillSummary = try {
        WorkspaceSkillSummary(
            id = draft.id,
            name = draft.name,
            description = draft.description,
            enabled = draft.enabled,
            order = draft.order,
            source = source,
            languages = draft.languages,
            tags = draft.tags,
            contentPath = WorkspacePath(rawId + "/" + CONTENT_FILE),
            contentSha256 = contentSha256,
            policy = draft.policy,
        )
    } catch (failure: IllegalArgumentException) {
        throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, draft.id)
    }

    private fun validatedId(id: WorkspaceSkillId): String {
        validateSkillPath(id, policy)
        return id.value
    }

    private fun resolveUserDirectory(rawId: String): File = try {
        root.resolve(WorkspacePath(rawId))
    } catch (failure: InvalidWorkspacePath) {
        throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.PATH_ESCAPE)
    }

    private fun resolveUserFile(rawId: String, name: String): File = try {
        root.resolve(WorkspacePath(rawId + "/" + name))
    } catch (failure: InvalidWorkspacePath) {
        throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.PATH_ESCAPE)
    }

    private fun writeFileAtomic(target: File, bytes: ByteArray, id: WorkspaceSkillId) {
        val parent = target.parentFile
            ?: throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED, id)
        val temp = try {
            File.createTempFile(".lnt-skill-", ".tmp", parent)
        } catch (failure: IOException) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED, id)
        } catch (failure: SecurityException) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED, id)
        }
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!temp.renameTo(target)) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED, id)
            }
        } catch (failure: WorkspaceSkillStoreException) {
            throw failure
        } catch (failure: IOException) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED, id)
        } catch (failure: SecurityException) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED, id)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun recordOf(summary: WorkspaceSkillSummary): WorkspaceSkillRecord =
        WorkspaceSkillRecord(summary, WorkspaceSkillMetadataCodec.fingerprint(summary))

    private fun deleteTree(file: File) {
        val canonical = try {
            file.canonicalFile
        } catch (failure: IOException) {
            file.delete()
            return
        }
        if (!isWithin(canonical)) {
            file.delete()
            return
        }
        val normalized = file.absoluteFile.normalize()
        if (canonical.path != normalized.path) {
            // The entry is a symlink; delete the link, never the target tree.
            file.delete()
            return
        }
        val children = file.listFiles()
        if (children != null) children.forEach { child -> deleteTree(child) }
        file.delete()
    }

    private fun isWithin(file: File): Boolean =
        file == baseDirectory || file.path.startsWith(baseDirectory.path + File.separator)

    private fun truncateChars(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        var end = maxChars
        if (end > 0 && text[end - 1].isHighSurrogate()) end -= 1
        return text.substring(0, end)
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    companion object {
        private const val MAX_FILES_PER_SKILL = 256
        private val TEXT_EXTENSIONS = setOf("md", "txt", "json", "jsonl", "yaml", "yml", "js", "mjs", "ts",
            "py", "rb", "css", "html", "htm", "xml", "csv", "toml", "ini")

        private val SKILL_COMPARATOR = compareBy<WorkspaceSkillRecord>(
            { it.summary.order },
            { it.summary.conflictNameKey },
            { it.summary.id.value },
        )

        fun withBuiltInRoot(
            userRoot: File,
            builtInRoot: File? = null,
            policy: PathPolicy = PathPolicy.DEFAULT,
        ): WorkspaceSkillStore = WorkspaceSkillStore(
            userRoot = userRoot,
            builtIns = builtInRoot?.let { FileWorkspaceSkillResourceSource(it, policy) },
            policy = policy,
        )
    }
}

/** Backwards-compatible concrete name used by adapters and tests. */
typealias FileWorkspaceSkillStore = WorkspaceSkillStore

/** Strict metadata codec for `skill.json`; unknown fields and fabricated digests are rejected. */
internal object WorkspaceSkillMetadataCodec {
    private val FIELDS = setOf(
        "name", "description", "enabled", "order", "languages", "tags", "contentSha256", "policy",
    )

    private val POLICY_FIELDS = setOf(
        "allowMaterials", "allowArtifacts", "allowSearch", "allowFetch", "allowProposeChanges",
        "allowPrint", "maxRunMillis", "maxOutputBytes", "maxArtifactBytes", "maxChangeBytes",
        "allowAutoSelect",
    )

    fun decode(
        metadata: ByteArray,
        id: WorkspaceSkillId,
        source: WorkspaceSkillSource,
        contentBytes: ByteArray,
    ): WorkspaceSkillRecord {
        val summary = parseSummary(metadata, id, source)
        val actual = sha256Hex(contentBytes)
        if (actual != summary.contentSha256) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        return WorkspaceSkillRecord(summary, fingerprint(summary))
    }

    /**
     * Metadata-only load for catalogs: the content body is not read or verified here. Callers
     * that show a body use [WorkspaceSkillStore.readContent], which re-checks the digest.
     */
    fun decodeMetadata(
        metadata: ByteArray,
        id: WorkspaceSkillId,
        source: WorkspaceSkillSource,
    ): WorkspaceSkillRecord {
        val summary = parseSummary(metadata, id, source)
        return WorkspaceSkillRecord(summary, fingerprint(summary))
    }

    private fun parseSummary(
        metadata: ByteArray,
        id: WorkspaceSkillId,
        source: WorkspaceSkillSource,
    ): WorkspaceSkillSummary {
        if (metadata.isEmpty() || metadata.size > WorkspaceSkillLimits.MAX_METADATA_BYTES) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        val text = try {
            strictUtf8(metadata)
        } catch (failure: CharacterCodingException) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        val json = try {
            JsonObjectValue.parse(text, JsonInputLimits(maxOf(text.length, 1), 8)).json
        } catch (failure: InvalidJsonPayload) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        } catch (failure: IllegalArgumentException) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        if (!FIELDS.containsAll(json.keys)) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        val name = requireString(json, "name", id)
        val description = optionalString(json, "description", id) ?: ""
        val enabled = optionalBoolean(json, "enabled", id) ?: true
        val order = optionalInt(json, "order", id) ?: 0
        val languages = optionalStringArray(json, "languages", id)
        val tags = optionalStringArray(json, "tags", id)
        if (languages.size > WorkspaceSkillLimits.MAX_LANGUAGES || tags.size > WorkspaceSkillLimits.MAX_TAGS) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
        }
        val policy = parsePolicy(json["policy"], id)
        val contentSha = requireString(json, "contentSha256", id)
        try {
            requireSha256Hex(contentSha)
        } catch (failure: IllegalArgumentException) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        return try {
            WorkspaceSkillSummary(
                id = id,
                name = name,
                description = description,
                enabled = enabled,
                order = order,
                source = source,
                languages = languages,
                tags = tags,
                contentPath = WorkspacePath(id.value + "/" + CONTENT_FILE),
                contentSha256 = contentSha,
                policy = policy,
            )
        } catch (failure: IllegalArgumentException) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
    }

    /**
     * Legacy `skill.json` files have no `policy` object and resolve to [WorkspaceSkillPolicy.DEFAULT].
     * A present policy is strict: every known field is required, unknown fields and wrong scalar
     * types are rejected, and the policy constructor validates all ceilings.
     */
    private fun parsePolicy(element: JsonElement?, id: WorkspaceSkillId): WorkspaceSkillPolicy {
        if (element == null || element is JsonNull) return WorkspaceSkillPolicy.DEFAULT
        if (element !is JsonObject || element.keys != POLICY_FIELDS) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        return try {
            WorkspaceSkillPolicy(
                allowMaterials = requirePolicyBoolean(element, "allowMaterials", id),
                allowArtifacts = requirePolicyBoolean(element, "allowArtifacts", id),
                allowSearch = requirePolicyBoolean(element, "allowSearch", id),
                allowFetch = requirePolicyBoolean(element, "allowFetch", id),
                allowProposeChanges = requirePolicyBoolean(element, "allowProposeChanges", id),
                allowPrint = requirePolicyBoolean(element, "allowPrint", id),
                maxRunMillis = requirePolicyInt(element, "maxRunMillis", id),
                maxOutputBytes = requirePolicyInt(element, "maxOutputBytes", id),
                maxArtifactBytes = requirePolicyInt(element, "maxArtifactBytes", id),
                maxChangeBytes = requirePolicyInt(element, "maxChangeBytes", id),
                allowAutoSelect = requirePolicyBoolean(element, "allowAutoSelect", id),
            )
        } catch (failure: IllegalArgumentException) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
    }

    private fun requirePolicyBoolean(json: JsonObject, key: String, id: WorkspaceSkillId): Boolean {
        val element = json[key] ?: throw invalidMetadata(id)
        if (element !is JsonPrimitive || element.isString) throw invalidMetadata(id)
        return element.booleanOrNull ?: throw invalidMetadata(id)
    }

    private fun requirePolicyInt(json: JsonObject, key: String, id: WorkspaceSkillId): Int {
        val element = json[key] ?: throw invalidMetadata(id)
        if (element !is JsonPrimitive || element.isString) throw invalidMetadata(id)
        return element.intOrNull ?: throw invalidMetadata(id)
    }

    private fun invalidMetadata(id: WorkspaceSkillId): Nothing =
        throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)

    fun encode(summary: WorkspaceSkillSummary): ByteArray {
        if (summary.languages.size > WorkspaceSkillLimits.MAX_LANGUAGES ||
            summary.tags.size > WorkspaceSkillLimits.MAX_TAGS
        ) {
            throw WorkspaceSkillStoreException(
                WorkspaceSkillStoreException.Reason.TOO_LARGE,
                summary.id,
            )
        }
        val bytes = buildJsonObject {
            put("name", summary.name)
            put("description", summary.description)
            put("enabled", summary.enabled)
            put("order", summary.order)
            put("languages", JsonArray(summary.languages.sorted().map { JsonPrimitive(it) }))
            put("tags", JsonArray(summary.tags.sorted().map { JsonPrimitive(it) }))
            put("contentSha256", summary.contentSha256 ?: "")
            put("policy", buildJsonObject {
                put("allowMaterials", summary.policy.allowMaterials)
                put("allowArtifacts", summary.policy.allowArtifacts)
                put("allowSearch", summary.policy.allowSearch)
                put("allowFetch", summary.policy.allowFetch)
                put("allowProposeChanges", summary.policy.allowProposeChanges)
                put("allowPrint", summary.policy.allowPrint)
                put("maxRunMillis", summary.policy.maxRunMillis)
                put("maxOutputBytes", summary.policy.maxOutputBytes)
                put("maxArtifactBytes", summary.policy.maxArtifactBytes)
                put("maxChangeBytes", summary.policy.maxChangeBytes)
                put("allowAutoSelect", summary.policy.allowAutoSelect)
            })
        }.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > WorkspaceSkillLimits.MAX_METADATA_BYTES) {
            throw WorkspaceSkillStoreException(
                WorkspaceSkillStoreException.Reason.TOO_LARGE,
                summary.id,
            )
        }
        return bytes
    }

    fun fingerprint(summary: WorkspaceSkillSummary): WorkspaceDigest {
        val builder = StringBuilder(384)
        builder.append("lnt-workspace-skill-v2\n")
        appendField(builder, summary.id.value)
        appendField(builder, summary.name)
        appendField(builder, summary.description)
        builder.append(if (summary.enabled) "1" else "0").append('\n')
        builder.append(summary.order).append('\n')
        summary.languages.sorted().forEach { appendField(builder, it) }
        builder.append("--\n")
        summary.tags.sorted().forEach { appendField(builder, it) }
        builder.append("--\n")
        appendField(builder, summary.contentSha256 ?: "")
        val policy = summary.policy
        builder.append("--\n")
        builder.append(if (policy.allowMaterials) "1" else "0").append('\n')
        builder.append(if (policy.allowArtifacts) "1" else "0").append('\n')
        builder.append(if (policy.allowSearch) "1" else "0").append('\n')
        builder.append(if (policy.allowFetch) "1" else "0").append('\n')
        builder.append(if (policy.allowProposeChanges) "1" else "0").append('\n')
        builder.append(if (policy.allowPrint) "1" else "0").append('\n')
        builder.append(if (policy.allowAutoSelect) "1" else "0").append('\n')
        builder.append(policy.maxRunMillis).append('\n')
        builder.append(policy.maxOutputBytes).append('\n')
        builder.append(policy.maxArtifactBytes).append('\n')
        builder.append(policy.maxChangeBytes).append('\n')
        return WorkspaceDigest(sha256Hex(builder.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun appendField(builder: StringBuilder, value: String) {
        builder.append(value.length).append(':').append(value).append('\n')
    }

    private fun requireString(json: JsonObject, key: String, id: WorkspaceSkillId): String {
        val element = json[key]
        if (element !is JsonPrimitive || !element.isString) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        return element.content
    }

    private fun optionalString(json: JsonObject, key: String, id: WorkspaceSkillId): String? {
        val element = json[key] ?: return null
        if (element is JsonNull) return null
        if (element !is JsonPrimitive || !element.isString) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        return element.content
    }

    private fun optionalBoolean(json: JsonObject, key: String, id: WorkspaceSkillId): Boolean? {
        val element = json[key] ?: return null
        if (element is JsonNull) return null
        if (element !is JsonPrimitive || element.isString) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        return element.booleanOrNull
            ?: throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
    }

    private fun optionalInt(json: JsonObject, key: String, id: WorkspaceSkillId): Int? {
        val element = json[key] ?: return null
        if (element is JsonNull) return null
        if (element !is JsonPrimitive || element.isString) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        return element.intOrNull
            ?: throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
    }

    private fun optionalStringArray(json: JsonObject, key: String, id: WorkspaceSkillId): Set<String> {
        val element: JsonElement = json[key] ?: return emptySet()
        if (element is JsonNull) return emptySet()
        if (element !is JsonArray) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        val result = LinkedHashSet<String>(element.size)
        element.forEach { item ->
            if (item !is JsonPrimitive || !item.isString) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
            }
            result += item.content
        }
        return result
    }

    private fun strictUtf8(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }
}
