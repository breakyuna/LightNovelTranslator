package com.breakyuna.noveltranslator.nativecore.workspace

import android.content.Context
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Publishes drafts produced by one project analysis run. The application adapter must write
 * through a project-bound [WorkspaceArtifactStore]; this contract only hands over validated
 * drafts and requires the same run identity back.
 */
fun interface ProjectWorkspaceArtifactPublisher {
    suspend fun publish(
        ref: LocalProjectRef,
        lease: RunLease,
        drafts: List<WorkspaceArtifactDraft>,
    ): List<WorkspaceArtifactRecord>
}

/** One analysis result with the fixed revision and artifacts actually published for the run. */
class ProjectWorkspaceAnalysisRun(
    val revision: Long,
    val result: WorkspaceScriptResult,
    artifacts: List<WorkspaceArtifactRecord>,
) {
    val artifacts: List<WorkspaceArtifactRecord> = workspaceFrozenList(artifacts)

    init {
        require(revision >= 0) { "Invalid analysis revision" }
    }

    override fun toString(): String =
        "ProjectWorkspaceAnalysisRun(revision=$revision, exit=${result.exitCode}, artifacts=${this.artifacts.size})"
}

/**
 * Production bridge for Agent analysis/proposal preparation. Exports the complete fixed revision in
 * bounded JSONL chunks, then runs code in the isolated interpreter. It never commits proposals,
 * calls a model or grants filesystem writes. Network reads require an explicit allowFetch call
 * and an injected policy-enforcing Fetch port. Proposal mode accepts one typed item
 * patch file only; the application still persists and approves it through the project writer.
 * Artifacts emitted by a successful script are published through [artifactPublisher] and never
 * become project data.
 */
class ProjectWorkspaceAnalysis(
    context: Context,
    private val projects: TextProjectPort,
    private val artifactPublisher: ProjectWorkspaceArtifactPublisher? = null,
    private val fetch: WorkspaceFetchPort? = null,
    private val search: WorkspaceSearchPort? = null,
    /** Read-only inventory of writable workspace files for baseSha256/update/delete proposals. */
    private val workspaceInventory: (suspend (LocalProjectRef) -> List<WorkspaceMaterialSummary>)? = null,
) {
    private val context = context.applicationContext

    suspend fun analyze(ref: LocalProjectRef, lease: RunLease, script: String,
                        expectedRevision: Long? = null, allowFetch: Boolean = false,
                        inputJson: String = "{}"): ProjectWorkspaceAnalysisRun {
        require(!allowFetch || fetch != null) { "Fetch unavailable" }
        val input = JsonObjectValue.parse(inputJson, JsonInputLimits(16_384, 16)).json.toString()
        val run = execute(ref, lease, script, ProposalMode.NONE, expectedRevision, allowFetch, input)
        return ProjectWorkspaceAnalysisRun(run.revision, run.result, publishResult(ref, lease, run.result))
    }

    suspend fun proposedChanges(ref: LocalProjectRef, lease: RunLease, script: String,
                                expectedRevision: Long? = null): ProjectScriptItemChanges {
        val run = execute(ref, lease, script, ProposalMode.ITEM, expectedRevision)
        val result = run.result
        require(result.exitCode == 0 && !result.outputTruncated) { "Script did not complete" }
        val change = result.changes.single()
        require(change.path.value == "changes/items.json" && change.operation == WorkspaceChangeOperation.CREATE &&
            change.expectedFingerprint == null) { "Item change proposal required" }
        val bytes = (change.content as? WorkspaceChangeContent.Inline ?: error("Inline item changes required")).bytes
        val json = bytes.toString(Charsets.UTF_8)
        require(json.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Invalid proposal encoding" }
        ItemProposalCodec.patches(json)
        return ProjectScriptItemChanges(run.revision, json, publishResult(ref, lease, result))
    }

    /**
     * Runs one bounded workspace-file proposal. Paths are limited to the writable work areas;
     * material-backed content is copied inline before the temporary analysis root is deleted, so
     * a later approval can never read a vanished material. The caller persists the returned batch
     * through [com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceChangeSetCodec] and
     * applies it only after a user decision.
     */
    suspend fun proposedWorkspaceChanges(ref: LocalProjectRef, lease: RunLease, script: String,
                                         expectedRevision: Long? = null): ProjectScriptWorkspaceChanges {
        val run = execute(ref, lease, script, ProposalMode.WORKSPACE, expectedRevision)
        val result = run.result
        require(result.exitCode == 0 && !result.outputTruncated) { "Script did not complete" }
        require(result.changes.isNotEmpty()) { "Workspace change proposal required" }
        result.changes.forEach { change ->
            require(change.path.value.startsWith("work/") || change.path.value.startsWith("uploads/") ||
                change.path.value.startsWith("reference/")) {
                "Workspace proposal path is read-only"
            }
        }
        val changeSet = WorkspaceChangeSet(
            workspaceChangeSetId(ref, lease, result.changes),
            ref.projectId,
            requireNotNull(lease.identity.chatId),
            lease.identity.runId,
            result.changes,
        )
        return ProjectScriptWorkspaceChanges(run.revision, changeSet, publishResult(ref, lease, result))
    }

    private enum class ProposalMode { NONE, ITEM, WORKSPACE }

    private suspend fun execute(ref: LocalProjectRef, lease: RunLease, script: String,
                                proposalMode: ProposalMode,
                                expectedRevision: Long?, allowFetch: Boolean = false, inputJson: String = "{}"): ProjectWorkspaceAnalysisRun =
        withContext(Dispatchers.IO) {
            require(lease.identity.projectId == ref.projectId)
            require(expectedRevision == null || expectedRevision >= 0) { "Invalid fixed revision" }
            val chat = requireNotNull(lease.identity.chatId)
            val source = WorkspaceScriptSource("project-analysis", "main.js", script)
            val started = System.currentTimeMillis()
            val deadline = started + 120_000
            val root = File(context.cacheDir, "workspace-analysis")
            check(root.isDirectory || root.mkdirs())
            val directory = File(root, UUID.randomUUID().toString())
            check(directory.mkdir())
            try {
                val summary = projects.open(ref)
                if (expectedRevision != null && summary.revision != expectedRevision) {
                    throw WorkspaceMaterialStaleRevision(expectedRevision, summary.revision)
                }
                val paths = summary.assets.associate { it.id to it.path.value }
                val dataset = File(directory, "datasets")
                check(dataset.mkdir())
                var chunk = 0
                var bytesInChunk = 0L
                var totalBytes = 0L
                var output: FileOutputStream? = null
                var after = 0L
                var count = 0
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (System.currentTimeMillis() > deadline) throw WorkspaceScriptTimeoutException()
                        val page = projects.items(ref, summary.revision, after, 100)
                        require(page.revision == summary.revision)
                        if (page.items.isEmpty()) break
                        for (item in page.items) {
                            currentCoroutineContext().ensureActive()
                            check(item.id.value > after) { "Project cursor did not advance" }
                            val bytes = (buildJsonObject {
                                put("item_id", item.id.value)
                                put("row", item.row + 1)
                                put("asset_id", item.assetId)
                                put("file_path", paths[item.assetId] ?: "")
                                put("slot", item.slot)
                                put("source", item.source)
                                put("translation", item.translation)
                                put("status", item.status.name)
                                put("fingerprint", item.fingerprint.sha256)
                                put("revision", summary.revision)
                                put("tag", item.metadata.tag)
                                put("name_source", item.metadata.nameSource?.let { names -> JsonArray(names.map { JsonPrimitive(it) }) } ?: JsonNull)
                                put("name_translation", item.metadata.nameTranslation?.let { names -> JsonArray(names.map { JsonPrimitive(it) }) } ?: JsonNull)
                                put("extra", JsonObjectValue.parse(item.metadata.extraField, JsonInputLimits(65_536, 32)).json)
                                put("skip_internal_filter", item.metadata.skipInternalFilter)
                            }.toString() + "\n").toByteArray(Charsets.UTF_8)
                            require(bytes.size <= CHUNK_BYTES) { "Project item exceeds analysis budget" }
                            require(totalBytes + bytes.size <= TOTAL_BYTES) { "Project exceeds analysis budget" }
                            if (output == null || bytesInChunk + bytes.size > CHUNK_BYTES) {
                                output?.close()
                                output = FileOutputStream(File(dataset, "items-${chunk++}.jsonl"))
                                bytesInChunk = 0
                            }
                            requireNotNull(output).write(bytes)
                            bytesInChunk += bytes.size
                            totalBytes += bytes.size
                            count++
                            after = item.id.value
                        }
                    }
                } finally { output?.close() }
                require(count == summary.itemCount) { "Incomplete project analysis snapshot" }
                require(projects.open(ref).revision == summary.revision) { "Project changed during analysis" }
                val files = WorkspaceFileStore(directory)
                if (proposalMode == ProposalMode.WORKSPACE) {
                    val inventory = workspaceInventory?.invoke(ref).orEmpty()
                        .filter { it.kind in setOf(WorkspaceMaterialKind.WORK, WorkspaceMaterialKind.UPLOAD,
                            WorkspaceMaterialKind.REFERENCE) }
                        .take(MAX_WORKSPACE_INVENTORY)
                    if (inventory.isNotEmpty()) {
                        FileOutputStream(File(dataset, "workspace-files.jsonl")).bufferedWriter(Charsets.UTF_8).use { writer ->
                            inventory.forEach { summary ->
                                writer.write(buildJsonObject {
                                    put("path", summary.path.value)
                                    put("sha256", summary.sha256)
                                    put("size", summary.sizeBytes)
                                    put("kind", summary.kind.name)
                                }.toString())
                                writer.newLine()
                            }
                        }
                    }
                }
                val materials = files.list(WorkspaceMaterialKind.DATASET).map { it.descriptor() }
                val engine = IsolatedWorkspaceScriptEngine(context, object : WorkspaceScriptSourcePort {
                    override suspend fun resolve(scriptId: String, entryPoint: String): WorkspaceScriptSource {
                        require(scriptId == source.scriptId && entryPoint == source.entryPoint)
                        return source
                    }
                }, SynchronousWorkspaceMaterialReadPort(files),
                    search = if (allowFetch) search else null,
                    fetch = if (allowFetch) fetch else null)
                val capabilities = buildSet {
                    add(WorkspaceCapability.READ_MATERIALS)
                    if (allowFetch && search != null) add(WorkspaceCapability.SEARCH)
                    if (allowFetch) add(WorkspaceCapability.FETCH)
                    if (proposalMode != ProposalMode.NONE) add(WorkspaceCapability.PROPOSE_CHANGES)
                    if (artifactPublisher != null) add(WorkspaceCapability.EMIT_ARTIFACTS)
                }
                val result = engine.execute(WorkspaceScriptRequest(
                    CommandId(UUID.randomUUID().toString()), ref.projectId, chat, lease.identity.runId,
                    source.scriptId, source.entryPoint,
                    mapOf("revision" to summary.revision.toString(), "item_count" to count.toString(),
                        "format" to "project-items-jsonl-v1", "input_json" to inputJson), materials,
                    capabilities,
                    WorkspaceScriptLimits.of(WorkspaceBudget(
                        maxOutputBytes = if (proposalMode != ProposalMode.NONE) 4L * 1024 * 1024 else 64L * 1024,
                        maxLogLines = 64,
                        maxChanges = if (proposalMode == ProposalMode.WORKSPACE) MAX_WORKSPACE_CHANGES else 1,
                        maxChangeBytes = 4L * 1024 * 1024,
                        maxTotalChangeBytes = if (proposalMode == ProposalMode.WORKSPACE) 32L * 1024 * 1024
                            else 4L * 1024 * 1024,
                        maxArtifacts = if (artifactPublisher != null) MAX_RUN_ARTIFACTS else 1,
                        // The isolated bridge derives its artifact request bound from maxOutputBytes;
                        // analysis keeps a small log budget, so its artifacts are intentionally 64 KiB.
                        maxArtifactBytes = if (proposalMode != ProposalMode.NONE) MAX_RUN_ARTIFACT_BYTES
                            else MAX_ANALYZE_ARTIFACT_BYTES,
                        maxTotalArtifactBytes = if (proposalMode != ProposalMode.NONE) MAX_RUN_TOTAL_ARTIFACT_BYTES
                            else MAX_ANALYZE_TOTAL_ARTIFACT_BYTES)),
                    deadline, WorkspaceCancellation()))
                val completed = if (proposalMode == ProposalMode.NONE) result
                    else materializeChanges(result, files)
                ProjectWorkspaceAnalysisRun(summary.revision, completed, emptyList())
            } finally {
                // Only this invocation's generated cache is owned here, never user materials.
                directory.deleteRecursively()
            }
        }

    /**
     * Deterministic identity for one Run's frozen batch. Retrying the same script with the same
     * emitted files must reproduce the same change-set identity, otherwise the proposal store's
     * same-Run/command idempotency check would reject a genuine retry.
     */
    private fun workspaceChangeSetId(
        ref: LocalProjectRef,
        lease: RunLease,
        changes: List<WorkspaceChange>,
    ): WorkspaceChangeSetId {
        val canonical = buildString(256 + changes.size * 128) {
            append("lnt-workspace-change-id-v1\n")
            append(ref.projectId.value).append('\n')
            append(ref.instanceId.value).append('\n')
            append(ref.generation.value).append('\n')
            append(lease.identity.runId.value).append('\n')
            append(lease.identity.commandId.value).append('\n')
            changes.forEach { change ->
                append(change.path.value).append('\u0000')
                append(change.operation.name).append('\u0000')
                append(change.expectedFingerprint?.sha256 ?: "-").append('\u0000')
                append(change.content?.sha256?.sha256 ?: "-").append('\u0000')
                append(change.content?.sizeBytes ?: 0L).append('\n')
            }
        }
        return WorkspaceChangeSetId("run-" + TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(canonical)))
    }

    /**
     * Copies material-backed content into inline bytes while the analysis root still exists.
     * A later persisted approval can never depend on a temporary material path or a deleted file.
     */
    private suspend fun materializeChanges(
        result: WorkspaceScriptResult,
        files: WorkspaceFileStore,
    ): WorkspaceScriptResult {
        val changes = result.changes.map { change ->
            when (val content = change.content) {
                is WorkspaceChangeContent.FromMaterial -> {
                    val material = files.read(content.materialId, content.sizeBytes)
                    require(material.sizeBytes == content.sizeBytes &&
                        material.sha256 == content.sha256.sha256) {
                        "Workspace material changed before proposal"
                    }
                    WorkspaceChange(change.path, change.operation, change.expectedFingerprint,
                        WorkspaceChangeContent.Inline(material.bytes))
                }
                else -> change
            }
        }
        return WorkspaceScriptResult(result.exitCode, changes, result.artifacts, result.logs, result.outputTruncated)
    }

    private suspend fun publishResult(
        ref: LocalProjectRef,
        lease: RunLease,
        result: WorkspaceScriptResult,
    ): List<WorkspaceArtifactRecord> {
        currentCoroutineContext().ensureActive()
        return if (artifactPublisher != null && result.exitCode == 0 && !result.outputTruncated &&
            result.artifacts.isNotEmpty()) {
            publishArtifacts(ref, lease, result.artifacts)
        } else {
            emptyList()
        }
    }

    private suspend fun publishArtifacts(
        ref: LocalProjectRef,
        lease: RunLease,
        drafts: List<WorkspaceArtifactDraft>,
    ): List<WorkspaceArtifactRecord> {
        val publisher = requireNotNull(artifactPublisher)
        val records = publisher.publish(ref, lease, drafts)
        require(records.size == drafts.size) { "Artifact publisher returned an incomplete batch" }
        val byPath = records.associateBy { it.path.value }
        require(byPath.size == records.size) { "Artifact publisher returned duplicate paths" }
        drafts.forEach { draft ->
            val record = byPath[draft.path.value]
                ?: throw IllegalStateException("Artifact publisher omitted a draft")
            require(record.runId == lease.identity.runId &&
                record.sha256 == draft.sha256 && record.sizeBytes == draft.sizeBytes) {
                "Artifact publisher returned a mismatched record"
            }
        }
        return records.sortedBy { it.path.value }
    }

    companion object {
        private const val CHUNK_BYTES = 4 * 1024 * 1024
        private const val TOTAL_BYTES = 64L * 1024 * 1024
        private const val MAX_RUN_ARTIFACTS = 16
        private const val MAX_WORKSPACE_CHANGES = 64
        private const val MAX_WORKSPACE_INVENTORY = 1_024
        private const val MAX_RUN_ARTIFACT_BYTES = 4L * 1024 * 1024
        private const val MAX_ANALYZE_ARTIFACT_BYTES = 64L * 1024
        private const val MAX_RUN_TOTAL_ARTIFACT_BYTES = 64L * 1024 * 1024
        private const val MAX_ANALYZE_TOTAL_ARTIFACT_BYTES = 1024L * 1024
    }
}

class ProjectScriptItemChanges(
    val revision: Long,
    val json: String,
    artifacts: List<WorkspaceArtifactRecord> = emptyList(),
) {
    val artifacts: List<WorkspaceArtifactRecord> = workspaceFrozenList(artifacts)

    override fun toString() = "ProjectScriptItemChanges(revision=$revision, body=redacted, artifacts=${this.artifacts.size})"
}

/** One frozen workspace-file batch ready for the approval store; bodies remain redacted here. */
class ProjectScriptWorkspaceChanges(
    val revision: Long,
    val changeSet: WorkspaceChangeSet,
    artifacts: List<WorkspaceArtifactRecord> = emptyList(),
) {
    val artifacts: List<WorkspaceArtifactRecord> = workspaceFrozenList(artifacts)

    override fun toString() =
        "ProjectScriptWorkspaceChanges(revision=$revision, changes=${changeSet.changes.size}, " +
            "digest=${changeSet.digest.sha256}, artifacts=${this.artifacts.size})"
}
