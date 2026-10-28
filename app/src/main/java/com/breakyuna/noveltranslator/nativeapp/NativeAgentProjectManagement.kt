package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.agent.*
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.storage.NativeTextProjectStore
import kotlinx.serialization.json.*

internal class NativeAgentProjectManagement(private val projects: NativeTextProjectStore,
    private val proposals: NativeAgentBusinessProposals) {
    private suspend fun snapshot(ref: LocalProjectRef): JsonObject {
        val project = projects.open(ref)
        return buildJsonObject {
            put("revision", project.revision); put("name", project.name)
            put("settings", Json.parseToJsonElement(ProjectSettingsCodec.encode(projects.projectSettings(ref))))
            put("assets", buildJsonArray { project.assets.forEach { asset -> add(buildJsonObject {
                put("id", asset.id); put("path", asset.path.value); put("sha256", asset.sha256)
                put("format", asset.format.name); put("enabled", asset.enabled); put("ordinal", asset.ordinal)
                put("items", asset.itemCount)
            }) } })
        }
    }
    suspend fun apply(ref: LocalProjectRef, proposal: NativeAgentBusinessProposal) {
        val payload = proposal.payload
        require(snapshot(ref) == payload.getValue("before"))
        val command = CommandId(proposal.id)
        val revision = proposal.document.getValue("revision").jsonPrimitive.long
        val receipt = when (proposal.kind) {
            "project_settings" -> projects.editProjectSettings(command, ref, revision,
                ProjectSettingsCodec.decode(payload.getValue("after").toString()))
            "asset_operation" -> {
                val asset = payload.getValue("asset_id").jsonPrimitive.long
                when (payload.getValue("operation").jsonPrimitive.content) {
                    "remove" -> projects.removeAsset(command, ref, revision, asset)
                    "reset" -> projects.resetAsset(command, ref, revision, asset)
                    "enable" -> projects.setAssetEnabled(command, ref, revision, asset, true)
                    "disable" -> projects.setAssetEnabled(command, ref, revision, asset, false)
                    "move_up", "move_down" -> {
                        val ids = projects.assets(ref).map { it.id }.toMutableList()
                        val from = ids.indexOf(asset)
                        val to = from + if (payload.getValue("operation").jsonPrimitive.content == "move_up") -1 else 1
                        require(from >= 0 && to in ids.indices)
                        java.util.Collections.swap(ids, from, to)
                        projects.reorderAssets(command, ref, revision, ids)
                    }
                    else -> error("Unknown asset action")
                }
            }
            "copy_project" -> { projects.copyProjectReviewed(command, ref, revision); return }
            else -> error("Unknown project action")
        }
        require(receipt.status == AssetMutationStatus.COMMITTED)
    }
    fun tools(lease: RunLease, ref: LocalProjectRef): List<RegisteredAgentTool> {
        suspend fun prepare(run: RunIdentity, kind: String, values: JsonObject, expected: String? = null): JsonObjectValue {
            val before = snapshot(ref)
            if (expected != null) require(TextProjectFingerprint.bytes(before.toString().toByteArray(Charsets.UTF_8)) == expected)
            val proposal = proposals.prepare(ref, run, before.getValue("revision").jsonPrimitive.long, kind,
                JsonObject(values + ("before" to before)))
            return JsonObjectValue.parse(buildJsonObject {
                put("approval", proposal.id); put("digest", proposal.digest); put("state", proposal.status)
            }.toString(), JsonInputLimits(4096, 4))
        }
        return listOf(
            RegisteredAgentTool("project_management_snapshot", "Read exact saved project settings and asset fingerprints/order for management proposals.",
                ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { run, _ ->
                    require(run == lease.identity)
                    val value = snapshot(ref)
                    JsonObjectValue.parse(buildJsonObject {
                        put("snapshot", value); put("fingerprint", TextProjectFingerprint.bytes(value.toString().toByteArray(Charsets.UTF_8)))
                    }.toString(), JsonInputLimits(512 * 1024, 12))
                }),
            RegisteredAgentTool("prepare_project_settings", "Prepare native project settings JSON for explicit approval. " +
                "Read project_management_snapshot first and retain all unrelated fields. Includes source/target languages, " +
                "bilingual deduplication, translated names and prompt enablement.",
                ToolParameterSchema(mapOf("settings_json" to ToolParameter.Text(2, 256 * 1024),
                    "base_fingerprint" to ToolParameter.Text(64, 64))),
                ToolEffect.APPROVAL_PREPARATION, AgentToolHandler { run, args ->
                    require(run == lease.identity)
                    val settings = ProjectSettingsCodec.decode(requireNotNull(args.text("settings_json")))
                    prepare(run, "project_settings", buildJsonObject { put("after", Json.parseToJsonElement(ProjectSettingsCodec.encode(settings))) }, requireNotNull(args.text("base_fingerprint")))
                }),
            RegisteredAgentTool("prepare_asset_operation", "Prepare remove/reset/enable/disable/move_up/move_down of one bound asset. " +
                "Explicit approval is required. REMOVE archives/removes the asset from the project; RESET clears translations/status. " +
                "The approval freezes the complete prior asset list, hashes and project revision.",
                ToolParameterSchema(mapOf("asset_id" to ToolParameter.Integer(1, Long.MAX_VALUE),
                    "operation" to ToolParameter.Text(1, 16))), ToolEffect.APPROVAL_PREPARATION,
                AgentToolHandler { run, args ->
                    require(run == lease.identity)
                    val id = requireNotNull(args.integer("asset_id"))
                    val operation = requireNotNull(args.text("operation"))
                    require(operation in setOf("remove", "reset", "enable", "disable", "move_up", "move_down"))
                    val assets = projects.assets(ref)
                    val index = assets.indexOfFirst { it.id == id }
                    require(index >= 0)
                    require(operation != "remove" || assets.size > 1)
                    require(operation != "move_up" || index > 0)
                    require(operation != "move_down" || index < assets.lastIndex)
                    prepare(run, "asset_operation", buildJsonObject { put("asset_id", id); put("operation", operation) })
                }),
            RegisteredAgentTool("prepare_project_copy", "Prepare a new independent copy of the bound project for explicit approval. " +
                "Existing project/data are preserved; the new copy appears in registered_projects.",
                ToolParameterSchema(emptyMap()), ToolEffect.APPROVAL_PREPARATION, AgentToolHandler { run, _ ->
                    require(run == lease.identity); prepare(run, "copy_project", buildJsonObject {})
                }),
            RegisteredAgentTool("registered_projects", "List registered project identities. A different project is never selected or modified implicitly.",
                ToolParameterSchema(mapOf("limit" to ToolParameter.Integer(1, 100, required = false))), ToolEffect.READ_ONLY,
                AgentToolHandler { run, args ->
                    require(run == lease.identity)
                    JsonObjectValue.parse(buildJsonObject {
                        put("projects", buildJsonArray { projects.registeredProjects((args.integer("limit") ?: 50L).toInt()).forEach { project ->
                            add(buildJsonObject { put("project", project.ref.projectId.value); put("instance", project.ref.instanceId.value)
                                put("generation", project.ref.generation.value) })
                        } })
                    }.toString(), JsonInputLimits(64 * 1024, 8))
                }),
        )
    }
}
