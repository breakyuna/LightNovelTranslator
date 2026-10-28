package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.agent.*
import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.serialization.json.*

internal class NativeAgentAuthService(private val runtime: RuntimePort, private val models: NativeModelProfilePort,
    private val oauth: NativeOAuthController, private val projects: TextProjectPort,
    private val proposals: NativeAgentBusinessProposals) {
    private fun value(state: NativeOAuthUiState): JsonObject = buildJsonObject {
        put("configured", state.configured); put("fingerprint", state.fingerprint)
        put("authorization_endpoint", state.authorizationEndpoint); put("token_endpoint", state.tokenEndpoint)
        put("client_id", state.clientId); put("scopes", state.scopes.joinToString(" "))
    }
    private fun draft(value: JsonObject): NativeOAuthConfigDraft {
        val schema = ToolParameterSchema(mapOf("authorization_endpoint" to ToolParameter.Text(1, 2048),
            "token_endpoint" to ToolParameter.Text(1, 2048), "client_id" to ToolParameter.Text(1, 1024),
            "scopes" to ToolParameter.Text(0, 4096)))
        val args = schema.validate(JsonObjectValue.parse(value.toString(), JsonInputLimits(32 * 1024, 4)))
        return NativeOAuthConfigDraft(requireNotNull(args.text("authorization_endpoint")), requireNotNull(args.text("token_endpoint")),
            requireNotNull(args.text("client_id")), requireNotNull(args.text("scopes")))
    }
    suspend fun apply(proposal: NativeAgentBusinessProposal) {
        val payload = proposal.payload
        val write = runtime.beginAuthenticationWrite()
        try {
            val profile = requireNotNull(models.find(payload.getValue("profile_id").jsonPrimitive.content))
            require(profile.reference.revision == payload.getValue("model_revision").jsonPrimitive.long)
            val baseline = payload.getValue("before").jsonObject
            val expected = baseline["fingerprint"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            val operation = payload.getValue("operation").jsonPrimitive.content
            oauth.applyReviewedManagement(profile, expected, operation,
                if (operation == "save") draft(payload.getValue("value").jsonObject) else null)
        } finally { runtime.endAuthenticationWrite(write) }
    }
    fun tools(lease: RunLease, ref: LocalProjectRef): List<RegisteredAgentTool> = listOf(
        RegisteredAgentTool("oauth_configuration", "Read public OAuth2/PKCE endpoints, public client ID, scopes and configuration fingerprint. " +
            "No token, verifier, client secret or credential reference is returned. Login consent/credential entry uses request_user_management.",
            ToolParameterSchema(mapOf("profile_id" to ToolParameter.Text(1, 128))), ToolEffect.READ_ONLY,
            AgentToolHandler { run, args ->
                require(run == lease.identity)
                val profile = requireNotNull(models.find(requireNotNull(args.text("profile_id"))))
                JsonObjectValue.parse(buildJsonObject {
                    put("profile_id", profile.id); put("model_revision", profile.reference.revision); put("value", value(oauth.load(profile.id)))
                }.toString(), JsonInputLimits(32 * 1024, 8))
            }),
        RegisteredAgentTool("prepare_oauth_management", "Prepare OAuth save/remove/logout/cancel_login for explicit approval. " +
            "profile_id and base_fingerprint are required (NEW only if not configured). save requires value_json with exactly " +
            "authorization_endpoint, token_endpoint, client_id, scopes (space-separated). Endpoints must use HTTPS. " +
            "Configuration changes invalidate old tokens/pending flows. No secrets may be supplied; browser login uses request_user_management area=oauth.",
            ToolParameterSchema(mapOf("profile_id" to ToolParameter.Text(1, 128), "operation" to ToolParameter.Text(1, 16),
                "base_fingerprint" to ToolParameter.Text(3, 64), "value_json" to ToolParameter.Text(2, 32 * 1024, required = false))),
            ToolEffect.APPROVAL_PREPARATION, AgentToolHandler { run, args ->
                require(run == lease.identity)
                val profile = requireNotNull(models.find(requireNotNull(args.text("profile_id"))))
                val before = value(oauth.load(profile.id))
                val expected = before["fingerprint"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content ?: "NEW"
                require(expected == args.text("base_fingerprint"))
                val operation = requireNotNull(args.text("operation")); require(operation in setOf("save", "remove", "logout", "cancel_login"))
                val replacement = if (operation == "save") JsonObjectValue.parse(requireNotNull(args.text("value_json")),
                    JsonInputLimits(32 * 1024, 4)).json.also { draft(it).toConfig(profile.id, false) } else null
                val proposal = proposals.prepare(ref, run, projects.open(ref).revision, "oauth_management", buildJsonObject {
                    put("profile_id", profile.id); put("model_revision", profile.reference.revision); put("before", before)
                    put("operation", operation); replacement?.let { put("value", it) }
                })
                JsonObjectValue.parse(buildJsonObject {
                    put("approval", proposal.id); put("digest", proposal.digest); put("state", proposal.status)
                }.toString(), JsonInputLimits(4096, 4))
            }),
    )
}
