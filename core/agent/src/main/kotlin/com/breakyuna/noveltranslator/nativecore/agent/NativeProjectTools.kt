package com.breakyuna.noveltranslator.nativecore.agent

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Project binding belongs to the host, never to a model-selected default project. */
fun nativeProjectTools(runtime: RuntimePort, projects: TextProjectPort, ref: LocalProjectRef): AgentToolRegistry =
    AgentToolRegistry(runtime, listOf(RegisteredAgentTool("project_summary", "Read the bound project summary",
        ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { run, _ ->
            require(run.projectId == ref.projectId)
            val project = projects.open(ref)
            JsonObjectValue.parse(buildJsonObject {
                put("name", project.name); put("asset", project.assetPath.value)
                put("items", project.itemCount); put("revision", project.revision)
            }.toString(), JsonInputLimits(16_384, 8))
        }), RegisteredAgentTool("read_item", "Read one stable text item in the bound project",
        ToolParameterSchema(mapOf("item_id" to ToolParameter.Integer(1, Long.MAX_VALUE))), ToolEffect.READ_ONLY,
        AgentToolHandler { run, args ->
            require(run.projectId == ref.projectId)
            val detail = projects.item(ref, ItemId(requireNotNull(args.integer("item_id"))))
                ?: throw TextProjectException(TextProjectException.Reason.UNKNOWN_PROJECT)
            JsonObjectValue.parse(buildJsonObject {
                put("item_id", detail.item.id.value); put("row", detail.item.row + 1)
                put("source", detail.item.source); put("translation", detail.item.translation)
                put("status", detail.item.status.name); put("revision", detail.revision)
                put("fingerprint", detail.item.fingerprint.sha256)
            }.toString(), JsonInputLimits(1024 * 1024, 16))
        })))


/** Preparation only: persists a fixed proposal; cannot consume approval or edit project rows. */
fun nativeApprovalTools(runtime: RuntimePort, workspace: NativeAgentWorkspacePort, ref: LocalProjectRef,
                        lease: RunLease): AgentToolRegistry = AgentToolRegistry(runtime, listOf(
    RegisteredAgentTool("prepare_text_edit", "Prepare one fixed text edit for explicit user approval",
        ToolParameterSchema(mapOf("item_id" to ToolParameter.Integer(1, Long.MAX_VALUE),
            "translation" to ToolParameter.Text(0, 65_536))), ToolEffect.APPROVAL_PREPARATION,
        AgentToolHandler { run, args ->
            require(run == lease.identity && run.projectId == ref.projectId)
            val approval = workspace.prepare(lease, ref, ItemId(requireNotNull(args.integer("item_id"))),
                requireNotNull(args.text("translation")))
            JsonObjectValue.parse(buildJsonObject {
                put("approval", approval.id.value); put("digest", approval.digest); put("state", "PENDING")
            }.toString(), JsonInputLimits(16_384, 8))
        })))

