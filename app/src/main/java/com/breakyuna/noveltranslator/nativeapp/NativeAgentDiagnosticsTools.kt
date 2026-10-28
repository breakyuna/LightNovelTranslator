package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.agent.*
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.storage.*
import kotlinx.serialization.json.*

internal class NativeAgentDiagnosticsService(private val store: NativeDiagnosticsStore,
    private val projects: TextProjectPort, private val proposals: NativeAgentBusinessProposals) {
    suspend fun apply(proposal: NativeAgentBusinessProposal) {
        require(proposal.kind == "clear_diagnostics")
        store.clearReviewed(proposal.payload.getValue("before").jsonObject)
    }

    fun tools(lease: RunLease, ref: LocalProjectRef): List<RegisteredAgentTool> = listOf(
        RegisteredAgentTool("diagnostics_page", "Read a bounded page of retained diagnostic summaries. Optional cursor comes from next_cursor; " +
            "level is DEBUG/INFO/WARN/ERROR. Optional detail bodies are excluded. A clear invalidates all old cursors.",
            ToolParameterSchema(mapOf("cursor" to ToolParameter.Text(1, 96, required = false),
                "level" to ToolParameter.Text(1, 16, required = false), "category" to ToolParameter.Text(1, 128, required = false),
                "limit" to ToolParameter.Integer(1, 50, required = false))), ToolEffect.READ_ONLY,
            AgentToolHandler { run, args ->
                require(run == lease.identity)
                val page = store.query((args.integer("limit") ?: 25L).toInt(),
                    args.text("cursor")?.let { NativeDiagnosticCursor.parse(it) },
                    args.text("level")?.let { NativeDiagnosticLevel.valueOf(it) }, args.text("category"))
                JsonObjectValue.parse(buildJsonObject {
                    put("entries", buildJsonArray { page.entries.forEach { entry -> add(buildJsonObject {
                        put("id", entry.id); put("timestamp", entry.timestampMillis); put("level", entry.level.name)
                        put("category", entry.category); put("message", entry.message); put("has_detail", entry.hasDetail)
                    }) } })
                    page.nextCursor?.let { put("next_cursor", it.token) }
                }.toString(), JsonInputLimits(256 * 1024, 8))
            }),
        RegisteredAgentTool("diagnostics_maintenance_snapshot", "Read the exact retained log count and opaque fingerprint for a clear proposal. No log bodies are included.",
            ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { run, _ ->
                require(run == lease.identity)
                JsonObjectValue.parse(store.maintenanceSnapshot().toString(), JsonInputLimits(4096, 4))
            }),
        RegisteredAgentTool("prepare_diagnostics_clear", "Prepare deletion of all retained application diagnostic logs for explicit approval. " +
            "This is global, includes all projects, and does not delete project data or usage receipts. Read diagnostics_maintenance_snapshot first. " +
            "New or removed records invalidate approval; interrupted deletion must be inspected and is never replayed automatically.",
            ToolParameterSchema(mapOf("base_fingerprint" to ToolParameter.Text(64, 64))), ToolEffect.APPROVAL_PREPARATION,
            AgentToolHandler { run, args ->
                require(run == lease.identity)
                val before = store.maintenanceSnapshot()
                require(before.getValue("fingerprint").jsonPrimitive.content == args.text("base_fingerprint"))
                val proposal = proposals.prepare(ref, run, projects.open(ref).revision, "clear_diagnostics", buildJsonObject {
                    put("scope", "all_application_diagnostics"); put("before", before)
                })
                JsonObjectValue.parse(buildJsonObject {
                    put("approval", proposal.id); put("digest", proposal.digest); put("state", proposal.status)
                }.toString(), JsonInputLimits(4096, 4))
            }),
    )
}
