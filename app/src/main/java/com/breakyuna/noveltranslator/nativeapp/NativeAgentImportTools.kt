package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.agent.*
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.workspace.*
import kotlinx.serialization.json.*

/** Restore/import only from frozen user uploads, never arbitrary host paths or model-selected URIs. */
internal class NativeAgentImportService(private val projects: TextProjectPort,
    private val workspace: NativeWorkspaceAccess, private val proposals: NativeAgentBusinessProposals,
    private val importer: suspend (String, LocalProjectRef, CommandId, Long, String?, ByteArray) -> Unit) {
    private val kinds = setOf("lnt", "project_backup", "project_set_backup", "application_backup", "asset")
    suspend fun apply(ref: LocalProjectRef, proposal: NativeAgentBusinessProposal) {
        val payload = proposal.payload
        val format = payload.getValue("format").jsonPrimitive.content
        require(format in kinds)
        val bytes = workspace.readVerifiedUpload(ref, WorkspaceMaterialId(payload.getValue("material_id").jsonPrimitive.content),
            payload.getValue("sha256").jsonPrimitive.content)
        val revision = proposal.document.getValue("revision").jsonPrimitive.long
        require(projects.open(ref).revision == revision)
        val path = payload["asset_path"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
        importer(format, ref, CommandId(proposal.id), revision, path, bytes)
    }
    fun tools(lease: RunLease, ref: LocalProjectRef): List<RegisteredAgentTool> = listOf(RegisteredAgentTool(
        "prepare_application_import", "Prepare import/restore from an existing user upload for explicit approval. " +
            "format: lnt, project_backup, project_set_backup, application_backup (including full archives), asset. " +
            "Read list_workspace_materials and use a kind=UPLOAD material_id and exact sha256. asset requires a relative " +
            "asset_path ending in .txt or .epub. Restoring application/full backups may replace global settings and import " +
            "projects/history; old execution permissions are never restored. No external URI or filesystem path is accepted.",
        ToolParameterSchema(mapOf("format" to ToolParameter.Text(1, 32), "material_id" to ToolParameter.Text(1, 512),
            "sha256" to ToolParameter.Text(64, 64), "asset_path" to ToolParameter.Text(1, 240, required = false))),
        ToolEffect.APPROVAL_PREPARATION, AgentToolHandler { run, args ->
            require(run == lease.identity)
            val format = requireNotNull(args.text("format"))
            require(format in kinds)
            val path = args.text("asset_path")
            if (format == "asset") {
                require(path != null)
                RelativePath(path)
                require(path.substringAfterLast('.').lowercase(java.util.Locale.ROOT) in setOf("txt", "epub"))
            } else require(path == null)
            val material = WorkspaceMaterialId(requireNotNull(args.text("material_id")))
            val sha = requireNotNull(args.text("sha256"))
            val revision = projects.open(ref).revision
            val summary = workspace.listMaterials(ref, revision).single { it.id == material }
            require(summary.kind == WorkspaceMaterialKind.UPLOAD && summary.sha256 == sha)
            val proposal = proposals.prepare(ref, run, revision, "application_import", buildJsonObject {
                put("format", format); put("material_id", material.value); put("sha256", sha)
                put("bytes", summary.sizeBytes); put("source_path", summary.path.value); put("asset_path", path)
            })
            JsonObjectValue.parse(buildJsonObject {
                put("approval", proposal.id); put("digest", proposal.digest); put("state", proposal.status)
            }.toString(), JsonInputLimits(4096, 4))
        }))
}
