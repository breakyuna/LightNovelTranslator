package com.breakyuna.noveltranslator.nativecore.workspace

import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

object WorkspaceSkillLimits {
    const val MAX_SKILLS: Int = 512
    const val MAX_METADATA_BYTES: Int = 64 * 1024
    const val MAX_CONTENT_BYTES: Int = 256 * 1024
    const val MAX_TOTAL_BYTES: Long = 32L * 1024 * 1024
    const val MAX_LANGUAGES: Int = 32
    const val MAX_TAGS: Int = 64
}

/**
 * One read-only built-in skill package. The resource source owns extraction from APK assets or
 * a shipped directory; the store owns validation and user overrides.
 */
class WorkspaceSkillResource(
    val id: WorkspaceSkillId,
    metadata: ByteArray,
    content: ByteArray,
) {
    private val metadataStorage: ByteArray = metadata.copyOf()
    private val contentStorage: ByteArray = content.copyOf()

    init {
        require(metadataStorage.isNotEmpty()) { "Empty skill metadata" }
        require(metadataStorage.size <= WorkspaceSkillLimits.MAX_METADATA_BYTES) { "Skill metadata too large" }
        require(contentStorage.size <= WorkspaceSkillLimits.MAX_CONTENT_BYTES) { "Skill content too large" }
    }

    val metadataBytes: ByteArray get() = metadataStorage.copyOf()
    val contentBytes: ByteArray get() = contentStorage.copyOf()

    override fun toString(): String =
        "WorkspaceSkillResource(id=${id.value}, metadata=${metadataStorage.size}, content=${contentStorage.size})"
}

/** Optional built-in skill root. An absent root yields an empty list, never invented skills. */
interface WorkspaceSkillResourceSource {
    suspend fun list(): List<WorkspaceSkillResource>

    suspend fun readFile(id: WorkspaceSkillId, path: String, maxBytes: Int): ByteArray? {
        if (path != CONTENT_FILE) return null
        val bytes = list().firstOrNull { it.id == id }?.contentBytes ?: return null
        if (bytes.size > maxBytes) throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
        return bytes
    }

    suspend fun files(id: WorkspaceSkillId): List<WorkspaceSkillFile> {
        val bytes = readFile(id, CONTENT_FILE, WorkspaceSkillLimits.MAX_CONTENT_BYTES) ?: return emptyList()
        return listOf(skillResourceDescriptor(id, CONTENT_FILE, bytes))
    }
}

/**
 * Loads built-in skills from `<root>/<id>/skill.json` plus `<root>/<id>/SKILL.md`. The root is
 * optional; a missing directory is treated as "no built-ins". Names are never inferred from
 * anything except real directories, and canonicalization prevents symlinked escapes.
 */
class FileWorkspaceSkillResourceSource(
    private val rootDirectory: File,
    private val policy: PathPolicy = PathPolicy.DEFAULT,
    private val canonicalizer: (File) -> String = { it.canonicalPath },
) : WorkspaceSkillResourceSource {
    override suspend fun list(): List<WorkspaceSkillResource> {
        if (!rootDirectory.exists()) return emptyList()
        if (!rootDirectory.isDirectory) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED)
        }
        val root = WorkspaceRoot.open(rootDirectory, canonicalizer)
        val entries = root.directory.listFiles()
            ?: throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED)
        val candidates = entries
            .filter { it.isDirectory && File(it, METADATA_FILE).isFile }
            .sortedBy { it.name }
        if (candidates.size > WorkspaceSkillLimits.MAX_SKILLS) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE)
        }
        val result = ArrayList<WorkspaceSkillResource>(candidates.size)
        var total = 0L
        for (directory in candidates) {
            val id = resourceId(directory.name)
            val metadataFile = try {
                root.resolve(WorkspacePath(id.value + "/" + METADATA_FILE))
            } catch (failure: InvalidWorkspacePath) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.PATH_ESCAPE, id)
            }
            val contentFile = try {
                root.resolve(WorkspacePath(id.value + "/" + CONTENT_FILE))
            } catch (failure: InvalidWorkspacePath) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.PATH_ESCAPE, id)
            }
            if (!contentFile.isFile) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
            }
            val metadata = readBounded(metadataFile, WorkspaceSkillLimits.MAX_METADATA_BYTES, id)
            val content = readBounded(contentFile, WorkspaceSkillLimits.MAX_CONTENT_BYTES, id)
            total += metadata.size.toLong() + content.size.toLong()
            if (total > WorkspaceSkillLimits.MAX_TOTAL_BYTES) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
            }
            result += WorkspaceSkillResource(id, metadata, content)
        }
        return result
    }

    override suspend fun readFile(id: WorkspaceSkillId, path: String, maxBytes: Int): ByteArray? {
        validateResourcePath(id, path, maxBytes)
        if (!rootDirectory.isDirectory) return null
        val root = WorkspaceRoot.open(rootDirectory, canonicalizer)
        val directory = root.resolve(WorkspacePath(id.value))
        val target = root.resolve(WorkspacePath(id.value + "/" + path))
        if (!target.canonicalPath.startsWith(directory.canonicalPath + File.separator))
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.PATH_ESCAPE, id)
        if (!target.isFile) return null
        return readBounded(target, maxBytes, id)
    }

    override suspend fun files(id: WorkspaceSkillId): List<WorkspaceSkillFile> {
        validateSkillPath(id, policy)
        if (!rootDirectory.isDirectory) return emptyList()
        val root = WorkspaceRoot.open(rootDirectory, canonicalizer)
        val directory = root.resolve(WorkspacePath(id.value))
        if (!directory.isDirectory) return emptyList()
        val result = ArrayList<WorkspaceSkillFile>()
        val visited = HashSet<String>()
        var total = 0L
        suspend fun walk(folder: File, prefix: String, depth: Int) {
            if (depth > 16 || visited.size >= 256 || !visited.add(folder.canonicalPath)) throw WorkspaceSkillStoreException(
                WorkspaceSkillStoreException.Reason.PATH_ESCAPE, id)
            val children = folder.listFiles() ?: throw WorkspaceSkillStoreException(
                WorkspaceSkillStoreException.Reason.IO_FAILED, id)
            if (children.size > 256) throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
            for (child in children.sortedBy { it.name }) {
                if (child.name == METADATA_FILE) continue
                val relative = prefix + child.name
                val target = root.resolve(WorkspacePath(id.value + "/" + relative))
                if (!target.canonicalPath.startsWith(directory.canonicalPath + File.separator))
                    throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.PATH_ESCAPE, id)
                if (target.isDirectory) walk(target, relative + "/", depth + 1)
                else if (target.isFile) {
                    if (result.size >= 256) throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
                    val bytes = requireNotNull(readFile(id, relative, WorkspaceSkillLimits.MAX_CONTENT_BYTES))
                    total += bytes.size
                    if (total > WorkspaceSkillLimits.MAX_TOTAL_BYTES) throw WorkspaceSkillStoreException(
                        WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
                    result += skillResourceDescriptor(id, relative, bytes)
                }
            }
        }
        walk(directory, "", 0)
        return workspaceFrozenList(result)
    }

    private fun resourceId(name: String): WorkspaceSkillId {
        val id = try {
            WorkspaceSkillId(name)
        } catch (failure: IllegalArgumentException) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_ID)
        }
        validateSkillPath(id, policy)
        return id
    }

    private fun readBounded(file: File, maxBytes: Int, id: WorkspaceSkillId): ByteArray {
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
}

/**
 * Reads optional `assets/workspace-skills/<id>/skill.json` and `SKILL.md`. This is the Android
 * wiring for built-in skills; the catalog is empty when the asset directory is absent. Tests
 * use [FileWorkspaceSkillResourceSource] instead of instantiating an AssetManager.
 */
class AndroidAssetWorkspaceSkillResourceSource(
    private val assets: AssetManager,
    root: String = DEFAULT_ROOT,
) : WorkspaceSkillResourceSource {
    private val root: String = validateAssetRoot(root)

    override suspend fun list(): List<WorkspaceSkillResource> {
        val children = try {
            assets.list(root)?.toList().orEmpty()
        } catch (failure: IOException) {
            // An absent optional asset directory is the normal "no built-ins" case.
            return emptyList()
        }
        val candidates = children.sorted()
        if (candidates.size > WorkspaceSkillLimits.MAX_SKILLS) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE)
        }
        val result = ArrayList<WorkspaceSkillResource>(candidates.size)
        var total = 0L
        for (name in candidates) {
            val id = try {
                WorkspaceSkillId(name)
            } catch (failure: IllegalArgumentException) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_ID)
            }
            validateSkillPath(id, PathPolicy.DEFAULT)
            val directory = root + "/" + name
            val members = try {
                assets.list(directory)?.toList().orEmpty()
            } catch (failure: IOException) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED, id)
            }
            if (METADATA_FILE !in members) continue
            val metadata = readAsset(directory + "/" + METADATA_FILE, WorkspaceSkillLimits.MAX_METADATA_BYTES, id)
            val content = readAsset(directory + "/" + CONTENT_FILE, WorkspaceSkillLimits.MAX_CONTENT_BYTES, id)
            total += metadata.size.toLong() + content.size.toLong()
            if (total > WorkspaceSkillLimits.MAX_TOTAL_BYTES) {
                throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
            }
            result += WorkspaceSkillResource(id, metadata, content)
        }
        return result
    }

    override suspend fun readFile(id: WorkspaceSkillId, path: String, maxBytes: Int): ByteArray? {
        validateResourcePath(id, path, maxBytes)
        return try {
            assets.open(root + "/" + id.value + "/" + path).use { readSkillBytesBounded(it, maxBytes, id) }
        } catch (_: IOException) { null }
    }

    override suspend fun files(id: WorkspaceSkillId): List<WorkspaceSkillFile> {
        validateSkillPath(id, PathPolicy.DEFAULT)
        val result = ArrayList<WorkspaceSkillFile>()
        var total = 0L
        var directories = 0
        suspend fun walk(prefix: String, depth: Int) {
            if (depth > 16 || ++directories > 256) throw WorkspaceSkillStoreException(
                WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
            val children = try { assets.list(root + "/" + id.value + "/" + prefix).orEmpty() }
            catch (_: IOException) { throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.IO_FAILED, id) }
            if (children.size > 256) throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
            for (name in children.sorted()) {
                if (name == METADATA_FILE) continue
                val path = prefix + name
                validateResourcePath(id, path, WorkspaceSkillLimits.MAX_CONTENT_BYTES)
                val bytes = readFile(id, path, WorkspaceSkillLimits.MAX_CONTENT_BYTES)
                if (bytes == null) walk(path + "/", depth + 1)
                else {
                    total += bytes.size
                    if (result.size >= 256 || total > WorkspaceSkillLimits.MAX_TOTAL_BYTES)
                        throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
                    result += skillResourceDescriptor(id, path, bytes)
                }
            }
        }
        walk("", 0)
        return workspaceFrozenList(result)
    }

    private fun readAsset(path: String, maxBytes: Int, id: WorkspaceSkillId): ByteArray {
        val stream = try {
            assets.open(path)
        } catch (failure: IOException) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_METADATA, id)
        }
        return stream.use { readSkillBytesBounded(it, maxBytes, id) }
    }

    companion object {
        const val DEFAULT_ROOT: String = "workspace-skills"

        private fun validateAssetRoot(raw: String): String {
            require(raw.isNotBlank() && raw.length <= 256)
            require(!raw.startsWith('/') && !raw.endsWith('/'))
            require('\\' !in raw && raw.none { it.isISOControl() })
            require(raw.split('/').none { it.isEmpty() || it == "." || it == ".." })
            return raw
        }
    }
}

internal const val METADATA_FILE: String = "skill.json"
internal const val CONTENT_FILE: String = "SKILL.md"

/** Validates one skill id as a single path segment under either skill root. */
internal fun validateSkillPath(id: WorkspaceSkillId, policy: PathPolicy) {
    val raw = id.value
    if (raw == "." || raw == "..") {
        throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_ID, id)
    }
    val parsed = try {
        policy.parse(raw)
    } catch (failure: InvalidWorkspacePath) {
        throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_ID, id)
    }
    if (parsed.value != raw || '/' in parsed.value || '\\' in parsed.value) {
        throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.INVALID_ID, id)
    }
}

internal fun readSkillBytesBounded(
    input: InputStream,
    maxBytes: Int,
    id: WorkspaceSkillId?,
): ByteArray {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) {
            throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.TOO_LARGE, id)
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun validateResourcePath(id: WorkspaceSkillId, path: String, maxBytes: Int) {
    require(maxBytes in 1..WorkspaceSkillLimits.MAX_CONTENT_BYTES)
    validateSkillPath(id, PathPolicy.DEFAULT)
    val logical = id.value + "/" + path
    val parsed = try { PathPolicy.DEFAULT.parse(logical) }
    catch (_: InvalidWorkspacePath) { throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.PATH_ESCAPE, id) }
    if (path.isBlank() || path.startsWith('/') || '\\' in path || path.any(Char::isISOControl) ||
        parsed.value != logical || path.split('/').any { it.isEmpty() || it == "." || it == ".." || it == METADATA_FILE })
        throw WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.PATH_ESCAPE, id)
}

private fun skillResourceDescriptor(id: WorkspaceSkillId, path: String, bytes: ByteArray): WorkspaceSkillFile {
    val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
        bytes.none { it == 0.toByte() }
    } catch (_: java.nio.charset.CharacterCodingException) { false }
    return WorkspaceSkillFile(WorkspacePath(id.value + "/" + path), bytes.size.toLong(), sha256Hex(bytes), text)
}
