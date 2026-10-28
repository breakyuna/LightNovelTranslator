package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.agent.*
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.storage.*
import kotlinx.serialization.json.*

internal class NativeAgentLibraryService(private val presets: NativeRulePresetStore,
    private val mcp: NativeMcpController, private val projects: TextProjectPort,
    private val proposals: NativeAgentBusinessProposals) {
    private val serverSchema = ToolParameterSchema(mapOf(
        "id" to ToolParameter.Text(1, 64), "name" to ToolParameter.Text(1, 128), "url" to ToolParameter.Text(1, 2048),
        "enabled" to ToolParameter.BooleanValue(), "allow_private_network" to ToolParameter.BooleanValue(),
        "allowed_tools" to ToolParameter.Text(0, 16_384), "timeout_millis" to ToolParameter.Integer(1000, 1_800_000),
        "max_tools" to ToolParameter.Integer(1, 256)))
    private fun server(value: JsonObject): NativeMcpServerDraft {
        val args = serverSchema.validate(JsonObjectValue.parse(value.toString(), JsonInputLimits(32 * 1024, 4)))
        return NativeMcpServerDraft(requireNotNull(args.text("id")), requireNotNull(args.text("name")), requireNotNull(args.text("url")),
            requireNotNull(args.boolean("enabled")), requireNotNull(args.boolean("allow_private_network")), requireNotNull(args.text("allowed_tools")),
            requireNotNull(args.integer("timeout_millis")).toString(), requireNotNull(args.integer("max_tools")).toString())
            .also { it.toRecord().toCoreConfig() }
    }
    private suspend fun mcpSnapshot(): JsonObject {
        val snapshot = mcp.load()
        return buildJsonObject {
            put("fingerprint", snapshot.fingerprint)
            put("servers", buildJsonArray { snapshot.servers.forEach { entry -> add(buildJsonObject {
                val record = entry.config
                put("id", record.id); put("name", record.name); put("url", record.url)
                put("enabled", record.enabled); put("allow_private_network", record.allowPrivateNetwork)
                put("allowed_tools", record.allowedTools.sorted().joinToString("\n"))
                put("timeout_millis", record.timeoutMillis); put("max_tools", record.maxTools)
            }) } })
        }
    }
    suspend fun apply(proposal: NativeAgentBusinessProposal) {
        val payload = proposal.payload
        val expected = payload.getValue("expected").jsonPrimitive.content
        when (proposal.kind) {
            "rule_preset_save" -> presets.save(RulePresetCodec.decode(payload.getValue("value").toString()), expected.takeUnless { it == "NEW" })
            "rule_preset_delete" -> presets.remove(payload.getValue("id").jsonPrimitive.content, expected)
            "mcp_server_save" -> {
                require(mcpSnapshot() == payload.getValue("before"))
                mcp.saveServer(server(payload.getValue("value").jsonObject), expected)
            }
            "mcp_server_delete" -> {
                require(mcpSnapshot() == payload.getValue("before"))
                mcp.deleteServer(payload.getValue("id").jsonPrimitive.content, expected)
            }
            else -> error("Unknown library action")
        }
    }
    fun tools(lease: RunLease, ref: LocalProjectRef): List<RegisteredAgentTool> {
        suspend fun prepare(run: RunIdentity, kind: String, payload: JsonObject): JsonObjectValue {
            val proposal = proposals.prepare(ref, run, projects.open(ref).revision, kind, payload)
            return JsonObjectValue.parse(buildJsonObject {
                put("approval", proposal.id); put("digest", proposal.digest); put("state", proposal.status)
            }.toString(), JsonInputLimits(4096, 4))
        }
        return listOf(
            RegisteredAgentTool("rule_preset_library", "Read the native rule preset library; optional preset_id returns its complete native JSON and fingerprint. " +
                "Use prepare_text_policy to apply selected rules to the project; library changes use prepare_rule_preset.",
                ToolParameterSchema(mapOf("preset_id" to ToolParameter.Text(1, 128, required = false))), ToolEffect.READ_ONLY,
                AgentToolHandler { run, args ->
                    require(run == lease.identity)
                    val id = args.text("preset_id")
                    JsonObjectValue.parse(buildJsonObject {
                        if (id == null) put("presets", buildJsonArray { presets.list().forEach { preset -> add(buildJsonObject {
                            put("id", preset.id); put("name", preset.name); put("description", preset.description); put("rules", preset.rules)
                        }) } }) else {
                            val preset = presets.load(id)
                            put("value", Json.parseToJsonElement(RulePresetCodec.encode(preset)))
                            put("fingerprint", RulePresetCodec.fingerprint(preset))
                        }
                    }.toString(), JsonInputLimits(128 * 1024, 16))
                }),
            RegisteredAgentTool("prepare_rule_preset", "Prepare native preset save/delete for explicit approval. operation is save or delete; " +
                "save requires value_json (RulePresetCodec native JSON), preset_id and base_fingerprint (NEW only for creation). " +
                "delete requires preset_id and its fingerprint. The previous preset is included in the approval.",
                ToolParameterSchema(mapOf("operation" to ToolParameter.Text(1, 16), "preset_id" to ToolParameter.Text(1, 128),
                    "base_fingerprint" to ToolParameter.Text(3, 64), "value_json" to ToolParameter.Text(2, RulePresetCodec.MAX_CHARS, required = false))),
                ToolEffect.APPROVAL_PREPARATION, AgentToolHandler { run, args ->
                    require(run == lease.identity)
                    val operation = requireNotNull(args.text("operation")); require(operation in setOf("save", "delete"))
                    val id = requireNotNull(args.text("preset_id")); val expected = requireNotNull(args.text("base_fingerprint"))
                    val existing = presets.list().firstOrNull { it.id == id }?.let { presets.load(id) }
                    require(if (existing == null) expected == "NEW" else RulePresetCodec.fingerprint(existing) == expected)
                    val value = if (operation == "save") RulePresetCodec.decode(requireNotNull(args.text("value_json"))).also { require(it.id == id) } else null
                    require(operation != "delete" || existing != null)
                    prepare(run, if (operation == "save") "rule_preset_save" else "rule_preset_delete", buildJsonObject {
                        put("id", id); put("expected", expected)
                        existing?.let { put("before", Json.parseToJsonElement(RulePresetCodec.encode(it))) }
                        value?.let { put("value", Json.parseToJsonElement(RulePresetCodec.encode(it))) }
                    })
                }),
            RegisteredAgentTool("mcp_settings_snapshot", "Read native MCP endpoint configuration and its CAS fingerprint. No tokens are returned.",
                ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { run, _ ->
                    require(run == lease.identity); JsonObjectValue.parse(mcpSnapshot().toString(), JsonInputLimits(256 * 1024, 8))
                }),
            RegisteredAgentTool("prepare_mcp_server", "Prepare MCP server save/delete for explicit approval. operation: save/delete. " +
                "base_fingerprint is from mcp_settings_snapshot. save requires server_json with exactly id,name,url,enabled," +
                "allow_private_network,allowed_tools (comma/newline separated names),timeout_millis,max_tools. server_id must match id. " +
                "Changing endpoints/private-network access is a permission change shown in full; credentials must be entered by the user separately.",
                ToolParameterSchema(mapOf("operation" to ToolParameter.Text(1, 16), "server_id" to ToolParameter.Text(1, 64),
                    "base_fingerprint" to ToolParameter.Text(64, 64), "server_json" to ToolParameter.Text(2, 32 * 1024, required = false))),
                ToolEffect.APPROVAL_PREPARATION, AgentToolHandler { run, args ->
                    require(run == lease.identity)
                    val before = mcpSnapshot(); val expected = requireNotNull(args.text("base_fingerprint"))
                    require(before.getValue("fingerprint").jsonPrimitive.content == expected)
                    val id = requireNotNull(args.text("server_id")); val operation = requireNotNull(args.text("operation"))
                    require(operation in setOf("save", "delete"))
                    val value = if (operation == "save") JsonObjectValue.parse(requireNotNull(args.text("server_json")),
                        JsonInputLimits(32 * 1024, 4)).json.also { require(server(it).id == id) } else null
                    if (operation == "delete") require(mcp.load().server(id) != null)
                    prepare(run, if (operation == "save") "mcp_server_save" else "mcp_server_delete", buildJsonObject {
                        put("before", before); put("expected", expected); put("id", id); value?.let { put("value", it) }
                    })
                }),
        )
    }
}
