package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.agent.*
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.workspace.*
import kotlinx.serialization.json.*

/** Data-only package. No archive extraction, arbitrary paths, package manager or install hooks. */
internal class NativeAgentSkillPackage(val draft: WorkspaceSkillDraft, val resources: Map<String, String>) {
    companion object {
        fun decode(payload: JsonObject): NativeAgentSkillPackage {
            require(payload.keys.all { it in setOf("id", "name", "description", "content", "resources", "enabled") })
            fun text(key: String, default: String? = null): String {
                val value = payload[key] ?: return requireNotNull(default)
                require(value is JsonPrimitive && value.isString)
                return value.content
            }
            val resources = payload["resources"]?.jsonObject?.mapValues { (path, value) ->
                require(path != "SKILL.md" && path != "skill.json" && '\\' !in path)
                require(PathPolicy.DEFAULT.parse(path).value == path)
                require(value is JsonPrimitive && value.isString)
                value.content
            }.orEmpty()
            require(resources.size <= 64)
            val enabled = payload["enabled"]?.let {
                require(it is JsonPrimitive && !it.isString)
                requireNotNull(it.booleanOrNull)
            } ?: true
            val draft = WorkspaceSkillDraft(WorkspaceSkillId(text("id")), text("name"), text("content"),
                description = text("description", ""), enabled = enabled)
            require(draft.content.isNotBlank())
            // Validate metadata now, before presenting an approval that the store would reject.
            WorkspaceSkillSummary(draft.id, draft.name, draft.description, draft.enabled, 0, WorkspaceSkillSource.USER)
            require(resources.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } +
                draft.content.toByteArray(Charsets.UTF_8).size <= WorkspaceSkillLimits.MAX_TOTAL_BYTES)
            return NativeAgentSkillPackage(draft, resources)
        }
    }
}

internal fun nativeAgentSkillTools(runtime: RuntimePort, lease: RunLease, ref: LocalProjectRef,
    projects: TextProjectPort, skills: WorkspaceSkillStore, proposals: NativeAgentBusinessProposals
): List<RegisteredAgentTool> = listOf("create_project_skill", "install_project_skill").map { name ->
    RegisteredAgentTool(name,
        "Prepare creation/installation of a user skill for explicit approval. package_json is an object with " +
            "id, name, content (complete SKILL.md), optional description, enabled (boolean), resources " +
            "(relative text file paths mapped to contents, e.g. scripts/check.js). For remote skills, use " +
            "web_fetch to inspect the source first, then provide the exact reviewed text/resources. No URL is " +
            "executed, no hooks or permission grants are accepted. Existing skills cannot be overwritten. " +
            "Installation is not complete until the user approves; do not claim it is saved.",
        ToolParameterSchema(mapOf("package_json" to ToolParameter.Text(2, 512 * 1024))),
        ToolEffect.APPROVAL_PREPARATION, AgentToolHandler { run, args ->
            require(run == lease.identity)
            val payload = JsonObjectValue.parse(requireNotNull(args.text("package_json")),
                JsonInputLimits(512 * 1024, 8)).json
            val pkg = NativeAgentSkillPackage.decode(payload)
            require(skills.list().none { it.summary.id == pkg.draft.id ||
                it.summary.conflictNameKey == pkg.draft.name.lowercase(java.util.Locale.ROOT) })
            val proposal = proposals.prepare(ref, run, projects.open(ref).revision, "install_skill", payload)
            JsonObjectValue.parse(buildJsonObject {
                put("approval", proposal.id); put("digest", proposal.digest); put("state", proposal.status)
            }.toString(), JsonInputLimits(4096, 8))
        })
}
