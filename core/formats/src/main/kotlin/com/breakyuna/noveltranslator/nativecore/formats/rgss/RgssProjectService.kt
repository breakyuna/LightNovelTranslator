package com.breakyuna.noveltranslator.nativecore.formats.rgss

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.Collections

/** One in-memory project file. [compressed] is meaningful only for a v3 archive payload. */
class RgssProjectFile(
    path: String,
    bytes: ByteArray,
    val compressed: Boolean = false,
    nameBytes: ByteArray? = null,
) {
    val path: String = canonicalRgssPath(path)
    val bytes: ByteArray = bytes.copyOf()
    internal val archiveNameBytes: ByteArray? = nameBytes?.copyOf()

    init {
        if (this.bytes.size > RgssArchiveLimits.DEFAULT_MAX_ENTRY_BYTES) {
            throw RgssFormatException(RgssFormatException.Reason.LIMIT, "project file")
        }
    }

    fun withBytes(value: ByteArray, compressed: Boolean = this.compressed): RgssProjectFile =
        RgssProjectFile(path, value, compressed, archiveNameBytes)

    override fun toString(): String = "RgssProjectFile(path=$path,size=${bytes.size})"
}

/**
 * In-memory RGSS project representation used by import/export services. It is independent of
 * Android storage: callers hand it bytes or a java.io directory and decide the SAF boundary.
 */
class RgssProject(val version: Int, files: List<RgssProjectFile>) {
    val files: List<RgssProjectFile> = Collections.unmodifiableList(ArrayList(files))

    init {
        if (version != 1 && version != 3) {
            throw RgssFormatException(RgssFormatException.Reason.HEADER, "archive version")
        }
        if (this.files.size > RgssArchiveLimits.DEFAULT_MAX_ENTRIES) {
            throw RgssFormatException(RgssFormatException.Reason.LIMIT, "file count")
        }
        val seen = HashSet<String>()
        var total = 0L
        for (file in this.files) {
            if (!seen.add(file.path.lowercase())) {
                throw RgssFormatException(RgssFormatException.Reason.PATH, "duplicate path")
            }
            if (version == 1 && file.compressed) {
                throw RgssFormatException(RgssFormatException.Reason.HEADER, "v1 compression")
            }
            total += file.bytes.size.toLong()
            if (total > RgssArchiveLimits.DEFAULT_MAX_TOTAL_BYTES) {
                throw RgssFormatException(RgssFormatException.Reason.LIMIT, "total bytes")
            }
        }
    }

    fun file(path: String): RgssProjectFile? = this.files.firstOrNull { it.path == path }

    override fun toString(): String = "RgssProject(version=$version,files=${files.size})"
}

/**
 * Directory/archive service entry for RGSS. Import is bounded and rejects paths that canonicalize
 * outside the chosen root; export writes only validated relative paths and re-checks the parent
 * after creating directories. It does not open the project database or any Android API.
 */
object RgssProjectService {
    fun importArchive(bytes: ByteArray): RgssProject {
        val archive = RgssArchiveCodec.read(bytes)
        return RgssProject(
            archive.version,
            archive.entries.map { RgssProjectFile(it.path, it.data, it.compressed, it.nameBytes) },
        )
    }

    fun exportArchive(project: RgssProject): ByteArray =
        RgssArchiveCodec.write(
            RgssArchive(
                project.version,
                project.files.map {
                    RgssArchiveEntry(it.path, it.bytes, it.compressed, it.archiveNameBytes)
                },
            ),
        )

    fun importDirectory(root: File): RgssProject {
        val canonicalRoot = canonicalDirectory(root)
        val visited = HashSet<String>()
        val files = ArrayList<RgssProjectFile>()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(canonicalRoot)
        while (stack.isNotEmpty()) {
            val directory = stack.removeLast()
            if (!visited.add(directory.canonicalPath)) continue
            val children = directory.listFiles()?.sortedBy { it.name }
                ?: throw RgssFormatException(RgssFormatException.Reason.IO, "list directory")
            for (child in children) {
                val canonical = canonicalInside(canonicalRoot, child)
                if (canonical.isDirectory) {
                    stack.addLast(canonical)
                } else if (canonical.isFile) {
                    if (files.size >= RgssArchiveLimits.DEFAULT_MAX_ENTRIES) {
                        throw RgssFormatException(RgssFormatException.Reason.LIMIT, "file count")
                    }
                    val relative = relativeTo(canonicalRoot, canonical)
                    val bytes = readBounded(canonical, RgssArchiveLimits.DEFAULT_MAX_ENTRY_BYTES)
                    total += bytes.size.toLong()
                    if (total > RgssArchiveLimits.DEFAULT_MAX_TOTAL_BYTES) {
                        throw RgssFormatException(RgssFormatException.Reason.LIMIT, "total bytes")
                    }
                    files.add(RgssProjectFile(relative, bytes, false))
                } else {
                    throw RgssFormatException(RgssFormatException.Reason.PATH, "unsupported entry")
                }
            }
        }
        files.sortBy { it.path }
        val version = if (files.any { it.path.lowercase().endsWith("scripts.rvdata2") }) 3 else 1
        return RgssProject(version, files)
    }

    fun exportDirectory(project: RgssProject, root: File) {
        val canonicalRoot = canonicalDirectory(root, create = true)
        for (file in project.files) {
            val target = File(canonicalRoot, file.path.replace('/', File.separatorChar))
            val canonicalTarget = canonicalInside(canonicalRoot, target)
            val parent = canonicalTarget.parentFile
                ?: throw RgssFormatException(RgssFormatException.Reason.PATH, "parent")
            if (!parent.exists() && !parent.mkdirs()) {
                throw RgssFormatException(RgssFormatException.Reason.IO, "create directory")
            }
            canonicalInside(canonicalRoot, parent)
            try {
                FileOutputStream(canonicalTarget).use { output ->
                    output.write(file.bytes)
                    output.flush()
                }
            } catch (_: IOException) {
                throw RgssFormatException(RgssFormatException.Reason.IO, "write file")
            }
        }
    }

    fun readScripts(project: RgssProject): List<Pair<String, RgssScriptsArchive>> =
        project.files.filter { isScriptFile(it.path) }.map { it.path to RgssScriptsCodec.read(it.bytes) }

    fun replaceScripts(project: RgssProject, path: String, archive: RgssScriptsArchive): RgssProject {
        val canonical = canonicalRgssPath(path)
        var found = false
        val files = project.files.map { file ->
            if (file.path == canonical) {
                found = true
                file.withBytes(RgssScriptsCodec.write(archive))
            } else {
                file
            }
        }
        if (!found) {
            throw RgssFormatException(RgssFormatException.Reason.BINDING, "script path")
        }
        return RgssProject(project.version, files)
    }

    private fun isScriptFile(path: String): Boolean =
        path.substringAfterLast('/').lowercase() in SCRIPT_NAMES

    private fun canonicalDirectory(root: File, create: Boolean = false): File {
        val canonical = try {
            root.canonicalFile
        } catch (_: IOException) {
            throw RgssFormatException(RgssFormatException.Reason.IO, "canonical root")
        }
        if (!canonical.exists()) {
            if (!create || !canonical.mkdirs()) {
                throw RgssFormatException(RgssFormatException.Reason.IO, "root directory")
            }
        }
        if (!canonical.isDirectory) {
            throw RgssFormatException(RgssFormatException.Reason.PATH, "root is not a directory")
        }
        return canonical
    }

    private fun canonicalInside(root: File, candidate: File): File {
        val canonical = try {
            candidate.canonicalFile
        } catch (_: IOException) {
            throw RgssFormatException(RgssFormatException.Reason.IO, "canonical path")
        }
        if (!inside(root.canonicalPath, canonical.canonicalPath)) {
            throw RgssFormatException(RgssFormatException.Reason.PATH, "path escape")
        }
        return canonical
    }

    private fun inside(rootPath: String, candidatePath: String): Boolean {
        if (candidatePath == rootPath) return true
        val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
        return candidatePath.startsWith(prefix)
    }

    private fun relativeTo(root: File, file: File): String {
        val rootPath = root.canonicalPath
        val filePath = file.canonicalPath
        if (!inside(rootPath, filePath) || filePath == rootPath) {
            throw RgssFormatException(RgssFormatException.Reason.PATH, "relative path")
        }
        val prefixLength = if (rootPath.endsWith(File.separator)) rootPath.length else rootPath.length + 1
        return filePath.substring(prefixLength).replace(File.separatorChar, '/')
    }

    private fun readBounded(file: File, maximum: Int): ByteArray {
        if (file.length() > maximum.toLong()) {
            throw RgssFormatException(RgssFormatException.Reason.LIMIT, "file bytes")
        }
        return try {
            FileInputStream(file).use { input ->
                val output = ByteArrayOutputStream(minOf(file.length().toInt(), 8_192).coerceAtLeast(64))
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maximum) {
                        throw RgssFormatException(RgssFormatException.Reason.LIMIT, "file bytes")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (failure: RgssFormatException) {
            throw failure
        } catch (_: IOException) {
            throw RgssFormatException(RgssFormatException.Reason.IO, "read file")
        }
    }

    private val SCRIPT_NAMES = setOf("scripts.rxdata", "scripts.rvdata", "scripts.rvdata2")
}
