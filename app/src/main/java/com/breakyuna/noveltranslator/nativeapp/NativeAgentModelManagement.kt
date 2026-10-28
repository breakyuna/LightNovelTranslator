package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.agent.*
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.storage.NativeModelSettingsStore
import kotlinx.serialization.json.*

internal class NativeAgentModelManagement(private val models: NativeModelSettingsStore,
    private val projects: TextProjectPort, private val proposals: NativeAgentBusinessProposals,
    private val clearProfileAuth: suspend (NativeModelProfile) -> Unit = {},
    private val clearRevisionAuth: suspend (ModelConfigurationRef) -> Unit = {}) {
    suspend fun apply(proposal: NativeAgentBusinessProposal): JsonObject {
        val payload = proposal.payload
        if (proposal.kind == "model_profile_save") {
            val profileId = payload["id"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            val previous = profileId?.let { models.find(it) }
            val created = models.saveReviewedProfile(payload.getValue("before").jsonObject.getValue("fingerprint").jsonPrimitive.content,
                profileId, NativeAgentModelProfileCodec.decode(payload.getValue("value").jsonObject),
                payload.getValue("credential_handling").jsonPrimitive.content == "retain")
            val cleaned = if (previous == null) true else kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { clearRevisionAuth(previous.reference) }.isSuccess
            }
            return buildJsonObject { put("profile_id", created); put("auth_cleanup_complete", cleaned) }
        }
        val deleting = payload.getValue("operation").jsonPrimitive.content == "delete"
        val previous = if (deleting) models.find(payload.getValue("id").jsonPrimitive.content) else null
        val id = models.applyReviewedProfileMutation(payload.getValue("before").jsonObject.getValue("fingerprint").jsonPrimitive.content,
            payload.getValue("id").jsonPrimitive.content, payload.getValue("operation").jsonPrimitive.content,
            payload["name"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
        val cleaned = if (previous == null) true else kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            runCatching { clearProfileAuth(previous) }.isSuccess
        }
        return buildJsonObject { put("profile_id", id); if (deleting) put("auth_cleanup_complete", cleaned) }
    }
    fun tools(lease: RunLease, ref: LocalProjectRef): List<RegisteredAgentTool> = listOf(
        RegisteredAgentTool("read_model_profile", "Read complete non-secret standard profile configuration. Extra headers/body and credentials are never returned; existing opaque extra fields are preserved on save.",
            ToolParameterSchema(mapOf("profile_id" to ToolParameter.Text(1, 128))), ToolEffect.READ_ONLY, AgentToolHandler { run, args ->
                require(run == lease.identity)
                val before = models.reviewedProfileSnapshot()
                val profile = requireNotNull(models.find(requireNotNull(args.text("profile_id"))))
                JsonObjectValue.parse(buildJsonObject {
                    put("base_fingerprint", before.getValue("fingerprint")); put("value", NativeAgentModelProfileCodec.encode(profile))
                    put("has_credentials", profile.settings.hasCredential)
                }.toString(), JsonInputLimits(64 * 1024, 8))
            }),
        RegisteredAgentTool("prepare_model_profile_save", "Prepare creation or replacement of standard model settings for explicit approval. Optional profile_id means edit; omit for creation. " +
            "base_fingerprint is from model_management_snapshot. value_json has name,kind,purpose,group,enabled,endpoint,model,protocol; " +
            "optional paired concurrency/requests_per_minute, tools/vision/reasoning booleans, context_tokens/output_tokens, temperature/top_p decimal strings, " +
            "generation_output_tokens, reasoning_effort/service_tier. Read read_model_profile before editing and keep unrelated fields. " +
            "credential_handling must be clear or retain: retain only works for an existing profile at the same endpoint; clear installs no API key. " +
            "Secrets must be entered by the user outside chat. Capability flags are configuration, not verified provider capability. Opaque extra fields are preserved.",
            ToolParameterSchema(mapOf("profile_id" to ToolParameter.Text(1, 128, required = false), "base_fingerprint" to ToolParameter.Text(64, 64),
                "value_json" to ToolParameter.Text(2, 32 * 1024), "credential_handling" to ToolParameter.Text(1, 16))),
            ToolEffect.APPROVAL_PREPARATION, AgentToolHandler { run, args ->
                require(run == lease.identity)
                val before = models.reviewedProfileSnapshot()
                require(before.getValue("fingerprint").jsonPrimitive.content == args.text("base_fingerprint"))
                val value = JsonObjectValue.parse(requireNotNull(args.text("value_json")), JsonInputLimits(32 * 1024, 4)).json
                val draft = NativeAgentModelProfileCodec.decode(value)
                val id = args.text("profile_id"); val handling = requireNotNull(args.text("credential_handling"))
                require(handling in setOf("clear", "retain"))
                val old = id?.let { requireNotNull(models.find(it)) }
                if (handling == "retain") require(old != null && NativeModelEndpoint.base(old.settings.endpoint) == NativeModelEndpoint.base(draft.endpoint))
                val proposal = proposals.prepare(ref, run, projects.open(ref).revision, "model_profile_save", buildJsonObject {
                    put("before", before); put("id", id); put("credential_handling", handling); put("value", value)
                    old?.let { put("previous_value", NativeAgentModelProfileCodec.encode(it)) }
                })
                JsonObjectValue.parse(buildJsonObject {
                    put("approval", proposal.id); put("digest", proposal.digest); put("state", proposal.status)
                }.toString(), JsonInputLimits(4096, 4))
            }),
        RegisteredAgentTool("model_management_snapshot", "Read credential-free model management metadata and its opaque CAS fingerprint.",
            ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { run, _ ->
                require(run == lease.identity)
                JsonObjectValue.parse(models.reviewedProfileSnapshot().toString(), JsonInputLimits(128 * 1024, 8))
            }),
        RegisteredAgentTool("prepare_model_management", "Prepare model profile rename/enable/disable/move_up/move_down/" +
            "select_translation/select_agent/delete/copy for explicit approval. Read model_management_snapshot first; " +
            "base_fingerprint must match the entire catalog. rename/copy require name. Copy retains credentials inside the " +
            "credential store; tools never return or accept secrets. Delete removes the profile and its configuration revisions.",
            ToolParameterSchema(mapOf("profile_id" to ToolParameter.Text(1, 128), "operation" to ToolParameter.Text(1, 32),
                "base_fingerprint" to ToolParameter.Text(64, 64), "name" to ToolParameter.Text(1, 128, required = false))),
            ToolEffect.APPROVAL_PREPARATION, AgentToolHandler { run, args ->
                require(run == lease.identity)
                val operation = requireNotNull(args.text("operation"))
                require(operation in setOf("rename", "enable", "disable", "move_up", "move_down", "select_translation", "select_agent", "delete", "copy"))
                val before = models.reviewedProfileSnapshot()
                require(before.getValue("fingerprint").jsonPrimitive.content == args.text("base_fingerprint"))
                val id = requireNotNull(args.text("profile_id"))
                require(before.getValue("profiles").jsonArray.any { it.jsonObject.getValue("id").jsonPrimitive.content == id })
                if (operation in setOf("copy", "rename")) NativeModelProfileValidation.validateName(requireNotNull(args.text("name")))
                val proposal = proposals.prepare(ref, run, projects.open(ref).revision, "model_management", buildJsonObject {
                    put("before", before); put("id", id); put("operation", operation); put("name", args.text("name"))
                })
                JsonObjectValue.parse(buildJsonObject {
                    put("approval", proposal.id); put("digest", proposal.digest); put("state", proposal.status)
                }.toString(), JsonInputLimits(4096, 4))
            }),
    )
}
