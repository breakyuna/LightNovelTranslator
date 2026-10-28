package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.agent.*
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.workspace.*
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Export to a private artifact first; system Save/Share remains an explicit user operation. */
internal class NativeAgentExportService(private val projects: TextProjectPort,
    private val proposals: NativeAgentBusinessProposals, private val workspace: NativeWorkspaceAccess,
    private val temporaryRoot: File,
    private val export: suspend (String, LocalProjectRef, Long?, OutputStream) -> Unit) {
    private val kinds = setOf("txt", "bilingual", "original", "lnt", "asset", "asset_bilingual",
        "project_backup", "project_set_backup", "application_backup", "full_archive", "diagnostics", "project_usage", "all_usage")
    suspend fun apply(ref: LocalProjectRef, proposal: NativeAgentBusinessProposal): JsonObject = withContext(Dispatchers.IO) {
        val kind = proposal.payload.getValue("format").jsonPrimitive.content
        require(kind in kinds)
        val asset = proposal.payload["asset_id"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.long
        require(projects.open(ref).revision == proposal.document.getValue("revision").jsonPrimitive.long)
        require(temporaryRoot.isDirectory || temporaryRoot.mkdirs())
        val temporary = File.createTempFile("agent-export-", ".tmp", temporaryRoot)
        try {
            FileOutputStream(temporary).use { file ->
                val context = currentCoroutineContext()
                val limited = object : OutputStream() {
                    var total = 0L
                    override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        context.ensureActive()
                        require(total + length <= WorkspaceHardLimits.MAX_ARTIFACT_BYTES) { "Artifact budget exceeded" }
                        file.write(bytes, offset, length); total += length
                    }
                    override fun flush() = file.flush()
                    // Writers may close their destination; this wrapper does not own the file.
                    override fun close() = flush()
                }
                export(kind, ref, asset, limited)
            }
            currentCoroutineContext().ensureActive()
            require(projects.open(ref).revision == proposal.document.getValue("revision").jsonPrimitive.long)
            val suffix = when (kind) {
                "txt", "bilingual" -> "txt"
                "diagnostics", "project_usage", "all_usage" -> "jsonl"
                "lnt" -> "lnt"
                "asset", "asset_bilingual", "original" -> {
                    val assets = projects.open(ref).assets
                    val source = if (asset == null) assets.first() else assets.single { it.id == asset }
                    source.path.value.substringAfterLast('.', "bin").lowercase(java.util.Locale.ROOT)
                        .takeIf { it.length in 1..12 && it.all { char -> char in 'a'..'z' || char in '0'..'9' } } ?: "bin"
                }
                else -> "zip"
            }
            val records = workspace.publishArtifacts(ref, RunId(proposal.document.getValue("run").jsonPrimitive.content),
                listOf(WorkspaceArtifactDraft(WorkspacePath("exports/${proposal.id}.$suffix"),
                    if (suffix in setOf("txt", "jsonl")) "text/plain" else "application/octet-stream", temporary.readBytes())))
            val artifact = records.single()
            buildJsonObject {
                put("artifact_id", artifact.id.value); put("path", artifact.path.value)
                put("sha256", artifact.sha256); put("bytes", artifact.sizeBytes)
            }
        } finally { temporary.delete() }
    }
    fun tools(lease: RunLease, ref: LocalProjectRef): List<RegisteredAgentTool> = listOf(RegisteredAgentTool(
        "prepare_application_export", "Prepare an export/backup into a private workspace artifact for explicit approval. " +
            "format: txt, bilingual, original, lnt, asset, asset_bilingual, project_backup, project_set_backup, application_backup, " +
            "full_archive, diagnostics, project_usage, all_usage. asset formats require asset_id. Global backup/usage may include " +
            "all projects and histories. No external file path is accepted; the user can save/share the resulting artifact. " +
            "Exports are bounded by the artifact byte budget and refuse oversize output.",
        ToolParameterSchema(mapOf("format" to ToolParameter.Text(1, 32),
            "asset_id" to ToolParameter.Integer(1, Long.MAX_VALUE, required = false))), ToolEffect.APPROVAL_PREPARATION,
        AgentToolHandler { run, args ->
            require(run == lease.identity)
            val kind = requireNotNull(args.text("format"))
            require(kind in kinds)
            val asset = args.integer("asset_id")
            val project = projects.open(ref)
            if (kind in setOf("asset", "asset_bilingual")) require(asset != null && project.assets.any { it.id == asset })
            else require(asset == null)
            val proposal = proposals.prepare(ref, run, project.revision, "application_export", buildJsonObject {
                put("format", kind); put("asset_id", asset?.let(::JsonPrimitive) ?: JsonNull)
            })
            JsonObjectValue.parse(buildJsonObject {
                put("approval", proposal.id); put("digest", proposal.digest); put("state", proposal.status)
            }.toString(), JsonInputLimits(4096, 4))
        }))
}
