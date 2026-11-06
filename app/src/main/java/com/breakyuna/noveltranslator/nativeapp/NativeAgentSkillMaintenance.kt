package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.agent.*
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.workspace.*
import kotlinx.serialization.json.*

internal class NativeAgentSkillMaintenance(private val skills: WorkspaceSkillStore,
    private val projects: TextProjectPort, private val proposals: NativeAgentBusinessProposals) {
    private val schema = ToolParameterSchema(mapOf(
        "skill_id" to ToolParameter.Text(1, 128), "base_fingerprint" to ToolParameter.Text(64, 64),
        "operation" to ToolParameter.Text(1, 32), "name" to ToolParameter.Text(1, 128, required = false),
        "description" to ToolParameter.Text(0, 1024, required = false),
        "content" to ToolParameter.Text(0, 256 * 1024, required = false),
        "enabled" to ToolParameter.BooleanValue(required = false), "order" to ToolParameter.Integer(0, Int.MAX_VALUE.toLong(), required = false),
        "path" to ToolParameter.Text(1, 512, required = false), "destination" to ToolParameter.Text(1, 512, required = false),
        "resource_sha256" to ToolParameter.Text(3, 64, required = false),
    ))
    private suspend fun validate(arguments: JsonObjectValue): WorkspaceSkillRecord {
        val args = schema.validate(arguments)
        val record = requireNotNull(skills.record(WorkspaceSkillId(requireNotNull(args.text("skill_id")))))
        require(record.fingerprint.sha256 == args.text("base_fingerprint"))
        when (requireNotNull(args.text("operation"))) {
            "update" -> {
                require(record.summary.source == WorkspaceSkillSource.USER)
                val draft = draft(record, args)
                WorkspaceSkillSummary(draft.id, draft.name, draft.description, draft.enabled, draft.order,
                    WorkspaceSkillSource.USER, draft.languages, draft.tags, policy = draft.policy)
            }
            "enable" -> requireNotNull(args.boolean("enabled"))
            "order" -> requireNotNull(args.integer("order"))
            "delete" -> require(record.summary.source == WorkspaceSkillSource.USER)
            "write_resource", "delete_resource", "rename_resource" -> {
                require(record.summary.source == WorkspaceSkillSource.USER)
                val path = requireNotNull(args.text("path"))
                fun validatePath(value: String) {
                    require(PathPolicy.DEFAULT.parse(value).value == value && '\\' !in value)
                    require(value.split('/').none { it in setOf("SKILL.md", "skill.json") })
                }
                validatePath(path)
                val old = skills.readFile(record.summary.id, path)
                val expected = requireNotNull(args.text("resource_sha256"))
                require(if (old == null) expected == "NEW" else TextProjectFingerprint.bytes(old) == expected)
                when (args.text("operation")) {
                    "write_resource" -> {
                        val content = requireNotNull(args.text("content"))
                        require(TextProjectFingerprint.utf8(content).size <= WorkspaceSkillLimits.MAX_CONTENT_BYTES)
                    }
                    "delete_resource" -> require(old != null)
                    "rename_resource" -> {
                        require(old != null)
                        val destination = requireNotNull(args.text("destination"))
                        validatePath(destination)
                        require(skills.readFile(record.summary.id, destination) == null)
                    }
                }
            }
            else -> error("Unknown skill operation")
        }
        return record
    }
    private suspend fun draft(record: WorkspaceSkillRecord, args: ValidatedToolArguments): WorkspaceSkillDraft {
        val summary = record.summary
        return WorkspaceSkillDraft(summary.id, args.text("name") ?: summary.name,
            args.text("content") ?: requireNotNull(skills.readContent(summary.id)),
            args.text("description") ?: summary.description, summary.enabled, summary.order,
            summary.languages, summary.tags, summary.policy)
    }
    suspend fun apply(payload: JsonObject) {
        val arguments = JsonObjectValue.parse(payload.getValue("arguments").toString(), JsonInputLimits(512 * 1024, 8))
        val record = validate(arguments)
        val args = schema.validate(arguments)
        val id = record.summary.id
        val expected = record.fingerprint
        when (args.text("operation")) {
            "update" -> skills.update(id, draft(record, args), expected)
            "enable" -> skills.setEnabled(id, requireNotNull(args.boolean("enabled")), expected)
            "order" -> skills.setOrder(id, requireNotNull(args.integer("order")).toInt(), expected)
            "delete" -> skills.delete(id, expected)
            "write_resource" -> skills.writeResource(id, requireNotNull(args.text("path")),
                requireNotNull(args.text("content")), expected, args.text("resource_sha256")?.takeUnless { it == "NEW" })
            "delete_resource" -> skills.deleteResource(id, requireNotNull(args.text("path")), expected, requireNotNull(args.text("resource_sha256")))
            "rename_resource" -> skills.renameResource(id, requireNotNull(args.text("path")), requireNotNull(args.text("destination")),
                expected, requireNotNull(args.text("resource_sha256")))
        }
    }
    fun tools(lease: RunLease, ref: LocalProjectRef): List<RegisteredAgentTool> = listOf(RegisteredAgentTool(
        "prepare_skill_maintenance", "Prepare a skill change for explicit approval. arguments_json has skill_id, base_fingerprint " +
            "from list_project_skills and operation: update (optional name/description/content), enable (enabled boolean), order " +
            "(order integer), delete, write_resource (path/content/resource_sha256), delete_resource (path/resource_sha256), " +
            "rename_resource (path/destination/resource_sha256). Use relative_path and the exact resource SHA from list_project_skill_files; " +
            "NEW only for a new resource. Built-ins permit only enable/order; permissions cannot be elevated. " +
            "The approval includes previous skill body and exact new arguments.",
        ToolParameterSchema(mapOf("arguments_json" to ToolParameter.Text(2, 512 * 1024))), ToolEffect.APPROVAL_PREPARATION,
        AgentToolHandler { run, args ->
            require(run == lease.identity)
            val arguments = JsonObjectValue.parse(requireNotNull(args.text("arguments_json")), JsonInputLimits(512 * 1024, 8))
            val record = validate(arguments)
            val proposal = proposals.prepare(ref, run, projects.open(ref).revision, "skill_maintenance", buildJsonObject {
                put("arguments", arguments.json)
                put("previous_content", skills.readContent(record.summary.id))
                val path = schema.validate(arguments).text("path")
                if (path != null) put("previous_resource", skills.readFile(record.summary.id, path)?.toString(Charsets.UTF_8))
                put("previous_name", record.summary.name); put("previous_description", record.summary.description)
                put("previous_enabled", record.summary.enabled); put("previous_order", record.summary.order)
            })
            JsonObjectValue.parse(buildJsonObject {
                put("approval", proposal.id); put("digest", proposal.digest); put("state", proposal.status)
            }.toString(), JsonInputLimits(4096, 4))
        }))
}
