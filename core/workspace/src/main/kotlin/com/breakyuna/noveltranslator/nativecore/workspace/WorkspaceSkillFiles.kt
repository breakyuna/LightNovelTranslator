package com.breakyuna.noveltranslator.nativecore.workspace

/**
 * One readable file inside a skill package. [path] is relative to the skill directory and is
 * never accepted from a model as a filesystem path; the catalog validates it under the private
 * skill root. [text] marks files that can be previewed as strict UTF-8.
 */
class WorkspaceSkillFile(
    val path: WorkspacePath,
    val sizeBytes: Long,
    val sha256: String,
    val text: Boolean,
) {
    init {
        require(sizeBytes >= 0)
        requireSha256Hex(sha256)
    }

    override fun toString(): String = "WorkspaceSkillFile(path=${path.value}, size=$sizeBytes)"
}

/**
 * Read-only resource tree for skill packages. It deliberately has no write method: skill
 * editing continues through [WorkspaceSkillCatalog] so versioned metadata and content
 * fingerprints stay consistent.
 */
interface WorkspaceSkillFilePort {
    suspend fun files(id: WorkspaceSkillId): List<WorkspaceSkillFile> = emptyList()

    suspend fun readFile(id: WorkspaceSkillId, relativePath: String,
        maxBytes: Int = WorkspaceSkillLimits.MAX_CONTENT_BYTES): ByteArray? = null
}

/**
 * Resolves files under the skill's scripts directory from an enabled skill into bounded UTF-8 script source. The
 * caller must still pass the source through the normal workspace run/approval pipeline.
 */
class WorkspaceSkillScriptSource(
    private val catalog: WorkspaceSkillCatalog,
    private val files: WorkspaceSkillFilePort,
) : WorkspaceScriptSourcePort {
    override suspend fun resolve(scriptId: String, entryPoint: String): WorkspaceScriptSource {
        val id = try { WorkspaceSkillId(scriptId) } catch (_: IllegalArgumentException) {
            throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.INVALID_SCRIPT_ID)
        }
        if (!entryPoint.startsWith("scripts/") || '\\' in entryPoint || entryPoint.any(Char::isISOControl))
            throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.INVALID_ENTRY_POINT)
        val record = try { catalog.record(id) } catch (_: Exception) {
            throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.READ_FAILED)
        } ?: throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.NOT_FOUND)
        if (!record.summary.enabled)
            throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.NOT_FOUND)
        val bytes = try { files.readFile(id, entryPoint, minOf(WorkspaceSkillLimits.MAX_CONTENT_BYTES,
            WorkspaceScriptSourceLimits.MAX_SOURCE_BYTES.toInt())) }
        catch (_: Exception) { throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.READ_FAILED) }
            ?: throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.NOT_FOUND)
        if (bytes.size.toLong() > WorkspaceScriptSourceLimits.MAX_SOURCE_BYTES)
            throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.TOO_LARGE)
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.INVALID_ENCODING)
        }
        return WorkspaceScriptSource(id.value, entryPoint, text)
    }
}
