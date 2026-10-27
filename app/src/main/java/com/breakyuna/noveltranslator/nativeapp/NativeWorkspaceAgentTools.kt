package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.agent.AgentToolHandler
import com.breakyuna.noveltranslator.nativecore.agent.AgentToolRegistry
import com.breakyuna.noveltranslator.nativecore.agent.RegisteredAgentTool
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.workspace.ProjectWorkspaceAnalysis
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactRecord
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillCatalog
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillPolicy
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillPolicyScriptSource
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillRecord
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillId
import com.breakyuna.noveltranslator.nativecore.storage.NativeItemProposalStore
import com.breakyuna.noveltranslator.nativecore.storage.NativePdfPageProposalStore
import com.breakyuna.noveltranslator.nativecore.storage.NativePolicyProposalStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeAssetImportProposalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val WORKSPACE_TOOL_LIST_LIMIT = 64
private const val WORKSPACE_TOOL_SKILL_LIST_LIMIT = 32
private const val WORKSPACE_TOOL_TEXT_CHARS = 64 * 1024

/** App composition boundary; the Koog module depends only on AgentToolPort. */
internal fun nativeWorkspaceAnalysisTools(runtime: RuntimePort, analysis: ProjectWorkspaceAnalysis,
                                         ref: LocalProjectRef, lease: RunLease,
                                         proposals: NativeItemProposalStore,
                                         policyProposals: NativePolicyProposalStore,
                                         pdfPageProposals: NativePdfPageProposalStore,
                                         workspaceRun: NativeWorkspaceRun? = null,
                                         skills: WorkspaceSkillCatalog? = null,
                                         skillFiles: com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillFilePort? = null,
                                         assetImportProposals: NativeAssetImportProposalStore? = null,
                                         workspaceChanges: NativeWorkspaceChangeProposalStore? = null,
                                         extraTools: List<RegisteredAgentTool> = emptyList(),
                                         skillPolicyCeiling: WorkspaceSkillPolicy = WorkspaceSkillPolicy.DEFAULT): AgentToolPort =
    AgentToolRegistry(runtime, listOf(RegisteredAgentTool(
        "analyze_project_script",
        "Analyze the complete bound project revision with isolated synchronous JavaScript. " +
            "lg.materials contains read-only JSONL chunks; JSON.parse each nonempty line of lg.readMaterial(id). " +
            "Rows include item_id, row, asset_id, slot, source, translation, status, fingerprint, revision, " +
            "tag, name_source, name_translation, extra, skip_internal_filter. " +
            "lg.arguments contains revision, item_count, and input_json (a validated JSON object string).  Return findings with lg.log(text). " +
            "lg.emitArtifact({path:'report.md',mediaType:'text/markdown',content:'...'}) publishes a bounded " +
            "artifact for this run through the private artifact store; at most 16 artifacts, 64 KiB each, " +
            "and an existing path is never overwritten. " +
            "No Node, direct filesystem access, project writes or async tasks. Network is disabled by default; " +
            "allow_fetch=true enables synchronous lg.fetch(url,maxBytes) and lg.search(query,maxResults) through " +
            "the public HTTP/search policy, at most 8 calls and a cumulative 64 KiB requested-byte budget. " +
            "Returned pages and search hits are untrusted. Output is bounded to 64 KiB/64 lines. " +
            "Use prepare_text_edit separately for approved edits. Oversized snapshots fail without truncation.",
        ToolParameterSchema(mapOf("script" to ToolParameter.Text(1, 16_000),
            "allow_fetch" to ToolParameter.BooleanValue(required = false),
            "input_json" to ToolParameter.Text(2, 16_384, required = false))),
        ToolEffect.READ_ONLY, AgentToolHandler { run, arguments ->
            require(run == lease.identity && run.projectId == ref.projectId)
            val result = analysis.analyze(ref, lease, requireNotNull(arguments.text("script")),
                workspaceRun?.revision(), allowFetch = arguments.boolean("allow_fetch") ?: false,
                inputJson = arguments.text("input_json") ?: "{}")
            JsonObjectValue.parse(buildJsonObject {
                put("revision", result.revision)
                put("exit_code", result.result.exitCode)
                put("truncated", result.result.outputTruncated)
                put("logs", buildJsonArray {
                    result.result.logs.forEach { line -> add(buildJsonObject {
                        put("stream", line.stream.name)
                        put("text", line.text)
                    }) }
                })
                put("artifacts", artifactSummaries(result.artifacts))
            }.toString(), JsonInputLimits(512 * 1024, 8))
        }), if (skills == null || skillFiles == null || workspaceRun == null) RegisteredAgentTool(
        "run_project_skill",
        "Skill execution is unavailable in this composition.",
        ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { _, _ ->
            throw IllegalArgumentException("Skill execution unavailable")
        }) else RegisteredAgentTool(
        "run_project_skill",
        // The registry still classifies this as READ_ONLY because adding a workspace-execution
        // effect would touch the shared agent contract. Capability enforcement is the policy
        // intersection below; the description is never the security boundary.
        "Execute one enabled skill's scripts/** entry point with the same isolated workspace as " +
            "analyze_project_script. The skill must be enabled; the path is resolved inside its " +
            "private package. Returns logs and artifacts only; it never writes project items. " +
            "Pass input_json as a JSON object string; the script reads JSON.parse(lg.arguments.input_json). " +
            "Default entry point is scripts/main.js. Network, search and artifact capabilities are " +
            "fixed by the skill's stored policy and the app runtime ceiling; the model cannot enable " +
            "network or proposals with a tool argument. This analysis bridge does not expose project " +
            "change proposals or printing. A denied capability fails instead of being granted.",
        ToolParameterSchema(mapOf("skill_id" to ToolParameter.Text(1, 128),
            "entry_point" to ToolParameter.Text(1, 256, required = false),
            "input_json" to ToolParameter.Text(2, 16_384, required = false))), ToolEffect.READ_ONLY,
        AgentToolHandler { run, arguments ->
            require(run == lease.identity && run.projectId == ref.projectId)
            val id = WorkspaceSkillId(requireNotNull(arguments.text("skill_id")))
            val entry = arguments.text("entry_point") ?: "scripts/main.js"
            val execution = withContext(Dispatchers.IO) {
                WorkspaceSkillPolicyScriptSource(skills, skillFiles, skillPolicyCeiling)
                    .resolveForExecution(id.value, entry)
            }
            val policy = execution.policy
            val source = execution.source
            val result = analysis.analyze(ref, lease, source.text, workspaceRun.revision(),
                allowFetch = policy.allowFetch,
                inputJson = arguments.text("input_json") ?: "{}",
                policy = policy)
            JsonObjectValue.parse(buildJsonObject {
                put("skill", id.value)
                put("entry_point", entry)
                put("script_sha256", source.sha256)
                put("revision", result.revision)
                put("exit_code", result.result.exitCode)
                put("truncated", result.result.outputTruncated)
                put("network", policy.allowFetch)
                put("proposals", policy.allowProposeChanges)
                put("allow_artifacts", policy.allowArtifacts)
                put("logs", buildJsonArray { result.result.logs.forEach { line -> add(buildJsonObject {
                    put("stream", line.stream.name); put("text", line.text)
                }) } })
                put("artifacts", artifactSummaries(result.artifacts))
            }.toString(), JsonInputLimits(512 * 1024, 8))
        }), RegisteredAgentTool(
        "project_text_policy", "Read the complete saved rules/prompt policy, its fingerprint and project revision. " +
            "The returned native policy JSON includes all rule fields, rule order and prompt enablement.",
        ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { run, _ ->
            require(run == lease.identity)
            val snapshot = policyProposals.saved(ref)
            JsonObjectValue.parse(buildJsonObject {
                put("revision", snapshot.revision); put("fingerprint", snapshot.fingerprint.sha256)
                put("policy", JsonObjectValue.parse(ProjectTextPolicyCodec.encode(snapshot.policy),
                    JsonInputLimits(ProjectTextPolicyCodec.MAX_CHARS, 8)).json)
            }.toString(), JsonInputLimits(128 * 1024, 12))
        }), RegisteredAgentTool(
        "prepare_text_policy", "Prepare a complete rules/prompt policy replacement for explicit user review. " +
            "Read project_text_policy first; supply its base_fingerprint and native policy JSON as policy_json. " +
            "Keep every rule field/order and prompt setting that should remain. This includes rule creation, deletion, " +
            "updates, reordering and prompt changes. One atomic proposal; it never saves directly and conflicts refuse the whole policy.",
        ToolParameterSchema(mapOf("base_fingerprint" to ToolParameter.Text(64, 64),
            "policy_json" to ToolParameter.Text(1, ProjectTextPolicyCodec.MAX_CHARS))), ToolEffect.APPROVAL_PREPARATION,
        AgentToolHandler { run, args ->
            require(run == lease.identity)
            val view = policyProposals.prepare(lease, ref, ObjectFingerprint(requireNotNull(args.text("base_fingerprint"))),
                ProjectTextPolicyCodec.decode(requireNotNull(args.text("policy_json"))))
            JsonObjectValue.parse(buildJsonObject {
                put("approval", view.id.value); put("digest", view.digest.sha256); put("state", view.state.name)
                put("added", view.added); put("updated", view.updated); put("deleted", view.deleted)
                put("reordered", view.reordered); put("prompt_changed", view.promptChanged)
            }.toString(), JsonInputLimits(16_384, 8))
        }), RegisteredAgentTool(
        "policy_proposal_status", "Read the real state and project receipt of a prepared rule/prompt policy. No writes.",
        ToolParameterSchema(mapOf("approval" to ToolParameter.Text(1, 128))), ToolEffect.READ_ONLY,
        AgentToolHandler { run, args ->
            require(run == lease.identity)
            val view = policyProposals.find(ref, requireNotNull(run.chatId), ApprovalRequestId(requireNotNull(args.text("approval"))))
            val receipt = policyProposals.receipt(view)
            JsonObjectValue.parse(buildJsonObject {
                put("approval", view.id.value); put("state", view.state.name); put("receipt_present", receipt != null)
                if (receipt != null) {
                    put("outcome", receipt.status.name); put("revision", receipt.revision)
                    put("fingerprint", receipt.fingerprint.sha256)
                }
            }.toString(), JsonInputLimits(16_384, 8))
        }), RegisteredAgentTool(
        "prepare_pdf_page_changes",
        "Prepare frozen PDF page decisions for explicit user review; never saves directly. " +
            "Read the current project revision and the PDF asset id/sha256/page count first. " +
            "pages_json is {\"pages\":[{\"page\":1,\"decision\":\"KEEP\"|\"OMIT\"|\"TRANSLATE\"}]}; " +
            "at most 5000 distinct pages. Values are concrete decisions; use the current effective " +
            "decision as the before value shown in review. conflict_policy is REJECT_BATCH (default) " +
            "or ACCEPT_INDEPENDENT_OBJECTS. REJECT_BATCH refuses the whole selected batch when the " +
            "project revision or asset identity changed. The user may accept a subset. No source text " +
            "is stored in the proposal or returned by status.",
        ToolParameterSchema(mapOf("asset_id" to ToolParameter.Integer(1, Long.MAX_VALUE),
            "base_revision" to ToolParameter.Integer(0, Long.MAX_VALUE),
            "base_sha256" to ToolParameter.Text(64, 64),
            "pages_json" to ToolParameter.Text(1, 512 * 1024),
            "conflict_policy" to ToolParameter.Text(1, 32, required = false))), ToolEffect.APPROVAL_PREPARATION,
        AgentToolHandler { run, args ->
            require(run == lease.identity && run.projectId == ref.projectId)
            val view = pdfPageProposals.prepare(lease, ref, requireNotNull(args.integer("asset_id")),
                requireNotNull(args.integer("base_revision")), requireNotNull(args.text("base_sha256")),
                requireNotNull(args.text("pages_json")),
                args.text("conflict_policy")?.let { ConflictPolicy.valueOf(it) } ?: ConflictPolicy.REJECT_BATCH)
            JsonObjectValue.parse(buildJsonObject {
                put("approval", view.id.value); put("digest", view.digest.sha256)
                put("asset", view.assetId); put("sha256", view.assetSha256); put("revision", view.revision)
                put("page_count", view.pageCount); put("pages", view.count)
                put("policy", view.policy.name); put("state", view.state.name)
            }.toString(), JsonInputLimits(16_384, 8))
        }), RegisteredAgentTool(
        "pdf_page_proposal_status", "Read the real state and committed/rejected page numbers of a prepared PDF " +
            "page proposal in this bound Chat. Use this receipt before claiming page decisions were saved. " +
            "This tool never applies a proposal and never returns source text.",
        ToolParameterSchema(mapOf("approval" to ToolParameter.Text(1, 128))), ToolEffect.READ_ONLY,
        AgentToolHandler { run, args ->
            require(run == lease.identity)
            val view = pdfPageProposals.find(ref, requireNotNull(run.chatId),
                ApprovalRequestId(requireNotNull(args.text("approval"))))
            val receipt = pdfPageProposals.receipt(view)
            JsonObjectValue.parse(buildJsonObject {
                put("approval", view.id.value); put("state", view.state.name); put("asset", view.assetId)
                put("sha256", view.assetSha256); put("revision", view.revision)
                put("page_count", view.pageCount); put("pages", view.count); put("receipt_present", receipt != null)
                if (receipt != null) {
                    put("revision", receipt.revision)
                    put("accepted", buildJsonArray { receipt.accepted.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    put("rejected", buildJsonArray { receipt.rejected.forEach { (page, reason) -> add(buildJsonObject {
                        put("page", page); put("reason", reason.name)
                    }) } })
                }
            }.toString(), JsonInputLimits(256 * 1024, 8))
        }), RegisteredAgentTool(
        "prepare_asset_import",
        "Prepare one persisted workspace artifact as a new project asset for explicit user review. " +
            "artifact_id must name an artifact of this bound run (see list_workspace_artifacts); " +
            "target_path is the new asset's project-relative path and enabled defaults to true. " +
            "Only frozen metadata is stored. The user's one-time approval re-reads the project " +
            "revision and the full artifact, verifies SHA-256/size, and lets the existing asset " +
            "writer decide target-path conflicts. This tool never writes project data, accepts no " +
            "filesystem path and cannot import another run's artifact.",
        ToolParameterSchema(mapOf("artifact_id" to ToolParameter.Text(1, 128),
            "target_path" to ToolParameter.Text(1, 512),
            "enabled" to ToolParameter.BooleanValue(required = false))),
        ToolEffect.APPROVAL_PREPARATION,
        AgentToolHandler { run, args ->
            require(run == lease.identity && run.projectId == ref.projectId)
            val view = requireNotNull(assetImportProposals) { "Asset import unavailable" }
            val runView = requireNotNull(workspaceRun) { "Workspace artifacts unavailable" }
            val artifactId = com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactId(
                requireNotNull(args.text("artifact_id")))
            val record = withContext(Dispatchers.IO) { runView.artifact(artifactId) }
            val revision = withContext(Dispatchers.IO) { runView.revision() }
            val proposal = withContext(Dispatchers.IO) {
                view.prepare(lease, ref, revision,
                    AssetImportArtifact(record.runId, AssetImportArtifactId(record.id.value),
                        RelativePath(record.path.value), record.sha256, record.sizeBytes),
                    RelativePath(requireNotNull(args.text("target_path"))),
                    args.boolean("enabled") ?: true)
            }
            JsonObjectValue.parse(buildJsonObject {
                put("proposal", proposal.id.value); put("digest", proposal.digest.sha256)
                put("state", proposal.state.name); put("artifact_id", proposal.artifactId.value)
                put("artifact_path", proposal.artifactPath.value); put("artifact_size", proposal.artifactSizeBytes)
                put("artifact_sha256", proposal.artifactSha256); put("target_path", proposal.target.value)
                put("revision", proposal.revision); put("enabled", proposal.enabled)
            }.toString(), JsonInputLimits(16_384, 8))
        }), RegisteredAgentTool(
        "asset_import_status",
        "Read the real state and durable asset receipt of a prepared workspace-artifact import in " +
            "this bound Chat. This tool never applies the proposal, never reads artifact bytes and " +
            "never imports a file.",
        ToolParameterSchema(mapOf("proposal" to ToolParameter.Text(1, 128))), ToolEffect.READ_ONLY,
        AgentToolHandler { run, args ->
            require(run == lease.identity)
            val view = requireNotNull(assetImportProposals) { "Asset import unavailable" }
            val proposal = withContext(Dispatchers.IO) {
                view.find(ref, requireNotNull(run.chatId), ApprovalRequestId(requireNotNull(args.text("proposal"))))
            }
            val receipt = withContext(Dispatchers.IO) { view.receipt(proposal) }
            JsonObjectValue.parse(buildJsonObject {
                put("proposal", proposal.id.value); put("state", proposal.state.name)
                put("artifact_id", proposal.artifactId.value); put("artifact_path", proposal.artifactPath.value)
                put("artifact_size", proposal.artifactSizeBytes); put("artifact_sha256", proposal.artifactSha256)
                put("target_path", proposal.target.value); put("revision", proposal.revision)
                put("enabled", proposal.enabled); put("receipt_present", receipt != null)
                if (receipt != null) {
                    put("receipt_status", receipt.status.name); put("asset", receipt.assetId)
                    put("receipt_revision", receipt.revision); put("committed_at", receipt.committedAtMillis)
                }
            }.toString(), JsonInputLimits(16_384, 8))
        }), RegisteredAgentTool(
        "prepare_project_script",
        "Run isolated synchronous JavaScript over the complete fixed project snapshot and prepare item changes for user approval. " +
            "Read JSONL chunks using lg.materials and lg.readMaterial(id), then emit exactly one " +
            "lg.emitChange({path:'changes/items.json',operation:'CREATE',content:JSON.stringify({items:patches})}). " +
            "Patch schema is the same as prepare_item_changes. lg.emitArtifact is also allowed and is published " +
            "for this run (at most 16 artifacts, 4 MiB each; an existing path is never overwritten); artifacts " +
            "never touch project data. " +
            "No direct project/file writes, artifacts outside the store or network. " +
            "policy is REJECT_BATCH by default or ACCEPT_INDEPENDENT_OBJECTS. " +
            "The patch reaches immutable approval storage directly; do not copy patch bodies into logs. " +
            "At most 1024 items/4 MiB output; fail rather than silently truncate scope.",
        ToolParameterSchema(mapOf("script" to ToolParameter.Text(1, 16_000),
            "policy" to ToolParameter.Text(1, 32, required = false))), ToolEffect.APPROVAL_PREPARATION,
        AgentToolHandler { run, args ->
            require(run == lease.identity)
            val result = analysis.proposedChanges(ref, lease, requireNotNull(args.text("script")),
                workspaceRun?.revision())
            val view = proposals.prepare(lease, ref, result.json,
                args.text("policy")?.let { ConflictPolicy.valueOf(it) } ?: ConflictPolicy.REJECT_BATCH, result.revision)
            JsonObjectValue.parse(buildJsonObject {
                put("approval", view.id.value); put("digest", view.digest.sha256)
                put("items", view.count); put("policy", view.policy.name); put("state", view.state.name)
                put("artifacts", artifactSummaries(result.artifacts))
            }.toString(), JsonInputLimits(64 * 1024, 8))
        }), if (workspaceChanges == null) RegisteredAgentTool(
        "prepare_workspace_changes",
        "Workspace file proposals are unavailable in this composition.",
        ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { _, _ ->
            throw IllegalArgumentException("Workspace file proposals unavailable")
        }) else RegisteredAgentTool(
        "prepare_workspace_changes",
        "Run isolated synchronous JavaScript over the complete fixed project snapshot and prepare a " +
            "workspace-file batch for explicit user approval. Emit changes only under work/, uploads/ or " +
            "reference/; sources/, datasets/ and artifacts/ are read-only. The read-only " +
            "workspace-files.jsonl material lists current path/sha256/size/kind for writable files; use " +
            "its sha256 as baseSha256 for UPDATE/DELETE. Each change is " +
            "{path,operation:'CREATE'|'UPDATE'|'DELETE',content/contentBase64/fromMaterial,baseSha256}. " +
            "The batch is frozen for approval and is never applied by this tool. base_revision must equal " +
            "the current project revision. At most 64 changes/4 MiB each; fail rather than truncate.",
        ToolParameterSchema(mapOf("script" to ToolParameter.Text(1, 16_000),
            "base_revision" to ToolParameter.Integer(0, Long.MAX_VALUE))), ToolEffect.APPROVAL_PREPARATION,
        AgentToolHandler { run, args ->
            require(run == lease.identity && run.projectId == ref.projectId)
            val base = requireNotNull(args.integer("base_revision"))
            val result = analysis.proposedWorkspaceChanges(ref, lease,
                requireNotNull(args.text("script")), base)
            val view = workspaceChanges.prepare(lease, ref, base, result.changeSet)
            JsonObjectValue.parse(buildJsonObject {
                put("proposal", view.id.value); put("digest", view.digest.sha256)
                put("changes", view.summary.total); put("bytes", view.summary.totalBytes)
                put("state", view.state.name)
                put("artifacts", artifactSummaries(result.artifacts))
            }.toString(), JsonInputLimits(64 * 1024, 8))
        }), if (workspaceChanges == null) RegisteredAgentTool(
        "workspace_change_status",
        "Workspace file proposals are unavailable in this composition.",
        ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { _, _ ->
            throw IllegalArgumentException("Workspace file proposals unavailable")
        }) else RegisteredAgentTool(
        "workspace_change_status",
        "Read the actual state and durable receipt of a prepared workspace-file proposal in this " +
            "bound Chat. This tool never applies a proposal, never reads file bodies and never " +
            "changes workspace files. Use it before claiming a batch was saved.",
        ToolParameterSchema(mapOf("proposal" to ToolParameter.Text(1, 128))), ToolEffect.READ_ONLY,
        AgentToolHandler { run, args ->
            require(run == lease.identity && run.projectId == ref.projectId)
            val view = withContext(Dispatchers.IO) {
                workspaceChanges.find(ref, requireNotNull(run.chatId),
                    ApprovalRequestId(requireNotNull(args.text("proposal"))))
            }
            JsonObjectValue.parse(buildJsonObject {
                put("proposal", view.id.value); put("state", view.state.name)
                put("base_revision", view.baseRevision); put("digest", view.digest.sha256)
                put("created", view.summary.created); put("updated", view.summary.updated)
                put("deleted", view.summary.deleted); put("bytes", view.summary.totalBytes)
                put("paths", buildJsonArray { view.paths.forEach { add(JsonPrimitive(it)) } })
                view.receipt?.let { receipt ->
                    put("receipt_status", receipt.status.name)
                    put("receipt_committed", receipt.committed)
                    put("receipt_conflicts", receipt.conflicts)
                    put("receipt_revision", receipt.revision)
                    put("receipt_at", receipt.at)
                    put("committed_paths", buildJsonArray {
                        receipt.committedPaths.forEach { add(JsonPrimitive(it)) }
                    })
                    put("conflict_paths", buildJsonArray {
                        receipt.conflictPaths.forEach { add(JsonPrimitive(it)) }
                    })
                }
            }.toString(), JsonInputLimits(64 * 1024, 8))
        }), RegisteredAgentTool(
        "prepare_item_changes",
        "Prepare an immutable multi-item proposal for explicit user review; never saves directly. " +
            "changes is JSON {items:[{item_id:1,fingerprint:current_sha256,translation:optional_text," +
            "status:optional_NONE_or_PROCESSED_or_EXCLUDED,name_source:optional_array_or_null," +
            "name_translation:optional_array_or_null}]}. Query current IDs and fingerprints first. " +
            "Unspecified fields remain unchanged. Translation edits default to PROCESSED. " +
            "policy is REJECT_BATCH (default) or ACCEPT_INDEPENDENT_OBJECTS; it is frozen for review. " +
            "Users can accept a subset. At most 1024 distinct changed items; never truncate the requested scope.",
        ToolParameterSchema(mapOf("changes" to ToolParameter.Text(1, 1_000_000),
            "policy" to ToolParameter.Text(1, 32, required = false))), ToolEffect.APPROVAL_PREPARATION,
        AgentToolHandler { run, args ->
            require(run == lease.identity && run.projectId == ref.projectId)
            val view = proposals.prepare(lease, ref, requireNotNull(args.text("changes")),
                args.text("policy")?.let { ConflictPolicy.valueOf(it) } ?: ConflictPolicy.REJECT_BATCH)
            JsonObjectValue.parse(buildJsonObject {
                put("approval", view.id.value); put("digest", view.digest.sha256)
                put("items", view.count); put("policy", view.policy.name); put("state", view.state.name)
            }.toString(), JsonInputLimits(16_384, 8))
        }), RegisteredAgentTool(
        "item_proposal_status", "Read the actual state and committed/rejected item IDs of a prepared proposal in this bound Chat. " +
            "Use this receipt before claiming edits were saved. This tool never applies a proposal.",
        ToolParameterSchema(mapOf("approval" to ToolParameter.Text(1, 128))), ToolEffect.READ_ONLY,
        AgentToolHandler { run, args ->
            require(run == lease.identity)
            val view = proposals.find(ref, requireNotNull(run.chatId), ApprovalRequestId(requireNotNull(args.text("approval"))))
            val receipt = proposals.receipt(view)
            JsonObjectValue.parse(buildJsonObject {
                put("approval", view.id.value); put("state", view.state.name); put("items", view.count)
                put("receipt_present", receipt != null)
                if (receipt != null) {
                    put("revision", receipt.revision)
                    put("accepted", buildJsonArray { receipt.accepted.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.value)) } })
                    put("rejected", buildJsonArray { receipt.rejected.forEach { (id, reason) -> add(buildJsonObject {
                        put("item_id", id.value); put("reason", reason.name)
                    }) } })
                }
            }.toString(), JsonInputLimits(256 * 1024, 8))
        })) + extraTools)

private fun artifactSummaries(artifacts: List<WorkspaceArtifactRecord>) = buildJsonArray {
    artifacts.forEach { artifact -> add(buildJsonObject {
        put("path", artifact.path.value)
        put("size", artifact.sizeBytes)
        put("sha256", artifact.sha256)
        put("kind", artifact.kind.name)
        put("media_type", artifact.mediaType)
    }) }
}

/**
 * Read-only inspection for the bound Agent run: manifest materials, this run's persisted
 * artifacts and the skill catalog. Every lookup takes an opaque identity; no tool parameter is
 * ever used as a filesystem path. Text bodies are capped and binary bodies are never returned.
 */
internal fun nativeWorkspaceInspectionTools(
    workspace: NativeWorkspaceRun,
    skills: WorkspaceSkillCatalog,
    skillFiles: com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillFilePort? = null,
): List<RegisteredAgentTool> {
    fun requireRun(run: RunIdentity) = require(run == workspace.lease.identity)

    return listOf(RegisteredAgentTool(
        "list_workspace_materials",
        "List manifest-approved materials for this run's fixed project revision. " +
            "Returns opaque ids, paths, sizes, SHA-256 digests and media types; bodies are not included. " +
            "Use read_workspace_material with the opaque id. Filesystem paths are never accepted.",
        ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { run, _ ->
            requireRun(run)
            val revision = withContext(Dispatchers.IO) { workspace.revision() }
            val all = withContext(Dispatchers.IO) { workspace.materials() }
            val shown = all.take(WORKSPACE_TOOL_LIST_LIMIT)
            JsonObjectValue.parse(buildJsonObject {
                put("revision", revision)
                put("total", all.size)
                put("truncated", shown.size < all.size)
                put("materials", buildJsonArray {
                    shown.forEach { material -> add(buildJsonObject {
                        put("id", material.id.value)
                        put("kind", material.kind.name)
                        put("path", material.path.value)
                        put("size", material.sizeBytes)
                        put("sha256", material.sha256)
                        put("media_type", material.mediaType)
                    }) }
                })
            }.toString(), JsonInputLimits(256 * 1024, 8))
        }), RegisteredAgentTool(
        "read_workspace_material",
        "Read one manifest-approved textual material by opaque id. " +
            "Returns its path, size, verified SHA-256, media type and at most 64 KiB of strict UTF-8 text. " +
            "Binary, non-manifest, oversized or invalid ids fail; arbitrary filesystem paths are never accepted.",
        ToolParameterSchema(mapOf("material_id" to ToolParameter.Text(1, 128))), ToolEffect.READ_ONLY,
        AgentToolHandler { run, args ->
            requireRun(run)
            val id = com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialId(
                requireNotNull(args.text("material_id")))
            val value = withContext(Dispatchers.IO) { workspace.readMaterialText(id) }
            JsonObjectValue.parse(buildJsonObject {
                put("id", id.value)
                put("kind", value.kind)
                put("path", value.path)
                put("size", value.sizeBytes)
                put("sha256", value.sha256)
                put("media_type", value.mediaType)
                put("text", value.text)
            }.toString(), JsonInputLimits(1024 * 1024, 8))
        }), RegisteredAgentTool(
        "list_workspace_artifacts",
        "List this bound run's persisted script artifacts with path, size, SHA-256, kind and media type. " +
            "Artifacts are bounded by the run budget. Bodies are not included; use read_workspace_artifact " +
            "with the opaque id for textual artifacts.",
        ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { run, _ ->
            requireRun(run)
            val inventory = withContext(Dispatchers.IO) { workspace.inventory() }
            val states = inventory.associate { it.record.id to it.availability }
            val all = inventory.map { it.record }
            val shown = all.take(WORKSPACE_TOOL_LIST_LIMIT)
            JsonObjectValue.parse(buildJsonObject {
                put("run", workspace.runId.value)
                put("total", all.size)
                put("truncated", shown.size < all.size)
                put("artifacts", buildJsonArray {
                    shown.forEach { artifact -> add(buildJsonObject {
                        put("id", artifact.id.value)
                        put("path", artifact.path.value)
                        put("kind", artifact.kind.name)
                        put("size", artifact.sizeBytes)
                        put("sha256", artifact.sha256)
                        put("media_type", artifact.mediaType)
                        put("created_at", artifact.createdAtMillis)
                        put("availability", requireNotNull(states[artifact.id]).name)
                    }) }
                })
            }.toString(), JsonInputLimits(256 * 1024, 8))
        }), RegisteredAgentTool(
        "read_workspace_artifact",
        "Read one persisted textual artifact from this bound run by opaque id. " +
            "Returns its path, size, verified SHA-256, kind, media type and at most 64 KiB of strict UTF-8 text. " +
            "Binary, oversized, other-run or invalid ids fail; arbitrary filesystem paths are never accepted. " +
            "Use Save a copy in the workspace review screen to keep the complete artifact.",
        ToolParameterSchema(mapOf("artifact_id" to ToolParameter.Text(1, 128))), ToolEffect.READ_ONLY,
        AgentToolHandler { run, args ->
            requireRun(run)
            val id = com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactId(
                requireNotNull(args.text("artifact_id")))
            val value = withContext(Dispatchers.IO) { workspace.readArtifactText(id) }
            JsonObjectValue.parse(buildJsonObject {
                put("id", id.value)
                put("kind", value.kind)
                put("path", value.path)
                put("size", value.sizeBytes)
                put("sha256", value.sha256)
                put("media_type", value.mediaType)
                put("text", value.text)
            }.toString(), JsonInputLimits(1024 * 1024, 8))
        }), RegisteredAgentTool(
        "list_project_skills",
        "List the project skill library metadata: opaque id, name, description, enabled flag, order, " +
            "source, languages, tags and content digest. Built-in skills are read-only. " +
            "This tool does not read bodies, execute skills or grant permissions.",
        ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { run, _ ->
            requireRun(run)
            val all = withContext(Dispatchers.IO) { skills.list() }
            val shown = all.take(WORKSPACE_TOOL_SKILL_LIST_LIMIT)
            JsonObjectValue.parse(buildJsonObject {
                put("total", all.size)
                put("truncated", shown.size < all.size)
                put("skills", buildJsonArray { shown.forEach { add(skillSummary(it)) } })
            }.toString(), JsonInputLimits(512 * 1024, 8))
        }), RegisteredAgentTool(
        "read_project_skill",
        "Read one skill's SKILL.md by opaque id, bounded to 64K characters. Read-only metadata and text only; " +
            "this never executes a skill, changes permissions or accepts a filesystem path.",
        ToolParameterSchema(mapOf("skill_id" to ToolParameter.Text(1, 128))), ToolEffect.READ_ONLY,
        AgentToolHandler { run, args ->
            requireRun(run)
            val id = WorkspaceSkillId(requireNotNull(args.text("skill_id")))
            val record = withContext(Dispatchers.IO) { skills.record(id) }
                ?: throw IllegalArgumentException("Unknown skill")
            val loaded = withContext(Dispatchers.IO) {
                skills.readContent(id, WORKSPACE_TOOL_TEXT_CHARS + 1)
            } ?: throw IllegalArgumentException("Skill content unavailable")
            val truncated = loaded.length > WORKSPACE_TOOL_TEXT_CHARS
            val content = if (truncated) {
                var end = WORKSPACE_TOOL_TEXT_CHARS
                if (end > 0 && loaded[end - 1].isHighSurrogate()) end -= 1
                loaded.substring(0, end)
            } else {
                loaded
            }
            JsonObjectValue.parse(buildJsonObject {
                put("skill", skillSummary(record))
                put("text", content)
                put("truncated", truncated)
            }.toString(), JsonInputLimits(1024 * 1024, 8))
        })) + if (skillFiles == null) emptyList() else listOf(RegisteredAgentTool(
        "list_project_skill_files",
        "List read-only files inside one enabled or disabled skill package. Paths are relative to " +
            "the private skill root and never accepted as filesystem paths. Metadata files are hidden.",
        ToolParameterSchema(mapOf("skill_id" to ToolParameter.Text(1, 128))), ToolEffect.READ_ONLY,
        AgentToolHandler { run, args ->
            requireRun(run)
            val id = WorkspaceSkillId(requireNotNull(args.text("skill_id")))
            val all = withContext(Dispatchers.IO) { skillFiles.files(id) }
            JsonObjectValue.parse(buildJsonObject {
                put("skill", id.value)
                put("files", buildJsonArray { all.forEach { file -> add(buildJsonObject {
                    put("path", file.path.value)
                    put("relative_path", file.path.value.removePrefix(id.value + "/"))
                    put("size", file.sizeBytes)
                    put("sha256", file.sha256)
                    put("text", file.text)
                }) } })
            }.toString(), JsonInputLimits(512 * 1024, 8))
        }), RegisteredAgentTool(
        "read_project_skill_file",
        "Read one UTF-8 text file from a skill package, bounded to 64K characters. Binary files " +
            "are listed but cannot be returned; this tool never executes a skill or writes files.",
        ToolParameterSchema(mapOf("skill_id" to ToolParameter.Text(1, 128),
            "relative_path" to ToolParameter.Text(1, 512))), ToolEffect.READ_ONLY,
        AgentToolHandler { run, args ->
            requireRun(run)
            val id = WorkspaceSkillId(requireNotNull(args.text("skill_id")))
            val relative = requireNotNull(args.text("relative_path"))
            val record = withContext(Dispatchers.IO) { skills.record(id) }
                ?: throw IllegalArgumentException("Unknown skill")
            val bytes = withContext(Dispatchers.IO) { skillFiles.readFile(id, relative) }
                ?: throw IllegalArgumentException("Skill file unavailable")
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) { throw IllegalArgumentException("Skill file is not UTF-8 text") }
            val truncated = text.length > WORKSPACE_TOOL_TEXT_CHARS
            val content = if (truncated) {
                var end = WORKSPACE_TOOL_TEXT_CHARS
                if (end > 0 && text[end - 1].isHighSurrogate()) end -= 1
                text.substring(0, end)
            } else text
            JsonObjectValue.parse(buildJsonObject {
                put("skill", skillSummary(record))
                put("relative_path", relative)
                put("text", content)
                put("truncated", truncated)
            }.toString(), JsonInputLimits(1024 * 1024, 8))
        }))
}

private fun skillSummary(record: WorkspaceSkillRecord) = buildJsonObject {
    put("id", record.summary.id.value)
    put("name", record.summary.name)
    put("description", record.summary.description)
    put("enabled", record.summary.enabled)
    put("order", record.summary.order)
    put("source", record.summary.source.name)
    put("languages", buildJsonArray { record.summary.languages.sorted().forEach { add(JsonPrimitive(it)) } })
    put("tags", buildJsonArray { record.summary.tags.sorted().forEach { add(JsonPrimitive(it)) } })
    put("content_sha256", record.summary.contentSha256 ?: "")
    put("policy", buildJsonObject {
        put("allow_materials", record.summary.policy.allowMaterials)
        put("allow_artifacts", record.summary.policy.allowArtifacts)
        put("allow_search", record.summary.policy.allowSearch)
        put("allow_fetch", record.summary.policy.allowFetch)
        put("allow_propose_changes", record.summary.policy.allowProposeChanges)
        put("allow_print", record.summary.policy.allowPrint)
        put("allow_auto_select", record.summary.policy.allowAutoSelect)
        put("max_run_millis", record.summary.policy.maxRunMillis)
        put("max_output_bytes", record.summary.policy.maxOutputBytes)
        put("max_artifact_bytes", record.summary.policy.maxArtifactBytes)
        put("max_change_bytes", record.summary.policy.maxChangeBytes)
    })
}
