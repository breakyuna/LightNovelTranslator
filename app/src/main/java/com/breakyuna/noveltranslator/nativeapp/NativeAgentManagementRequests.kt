package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.agent.*
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

internal data class NativeAgentManagementRequest(val id: String, val ref: LocalProjectRef, val chat: ChatId,
    val area: String, val profileId: String?)

/** Navigation handoff only. Never accepts a credential, starts a probe, logs in, or exports data. */
internal class NativeAgentManagementRequests(private val models: NativeModelProfilePort,
    private val oauth: NativeOAuthController) {
    private val pendingState = MutableStateFlow<NativeAgentManagementRequest?>(null)
    val pending = pendingState.asStateFlow()
    fun consume(expected: NativeAgentManagementRequest): Boolean = pendingState.compareAndSet(expected, null)
    fun tools(lease: RunLease, ref: LocalProjectRef): List<RegisteredAgentTool> = listOf(
        RegisteredAgentTool("authentication_status", "Read sanitized API-key/OAuth presence and expiry for a profile; no token, key, URL, client secret or credential reference is returned.",
            ToolParameterSchema(mapOf("profile_id" to ToolParameter.Text(1, 128))), ToolEffect.READ_ONLY,
            AgentToolHandler { run, args ->
                require(run == lease.identity)
                val profile = requireNotNull(models.find(requireNotNull(args.text("profile_id"))))
                val state = oauth.load(profile.id)
                JsonObjectValue.parse(buildJsonObject {
                    put("profile_id", profile.id); put("revision", profile.reference.revision)
                    put("api_key_configured", profile.settings.hasCredential); put("oauth_configured", state.configured)
                    put("oauth_status", state.status.name); put("has_oauth_token", state.hasToken)
                    put("refreshable", state.refreshable); put("pending_login", state.pendingLogin)
                    state.expiresAtMillis?.let { put("expires_at", it) }
                }.toString(), JsonInputLimits(4096, 4))
            }),
        RegisteredAgentTool("request_user_management", "Request the application's user interaction for secrets, consent or system file selection. " +
            "area: model_credentials, model_advanced, provider_probe, oauth, model_catalog (profile_id required), model_profiles, mcp_credentials, proxy_credentials, " +
            "legacy_export, project_import, workspace, skills, files, diagnostics, usage. workspace opens materials/artifacts with user file selection; " +
            "model_advanced opens the profile editor for opaque headers/body; provider_probe opens that editor and requires the user to initiate the probe. " +
            "The host opens the existing native interface after this run " +
            "finishes; finish your response after requesting it. This only delivers a handoff: USER_INTERACTION_REQUIRED never " +
            "means authentication, a probe, export or configuration has succeeded. Do not ask the user to put secrets into chat. " +
            "The existing UI owns credential entry, browser consent, file selection and paid-probe confirmation.",
            ToolParameterSchema(mapOf("area" to ToolParameter.Text(1, 32), "profile_id" to ToolParameter.Text(1, 128, required = false))),
            ToolEffect.READ_ONLY, AgentToolHandler { run, args ->
                require(run == lease.identity)
                val area = requireNotNull(args.text("area"))
                require(area in setOf("model_credentials", "model_advanced", "provider_probe", "oauth", "model_catalog", "model_profiles", "mcp_credentials", "proxy_credentials",
                    "legacy_export", "project_import", "workspace", "skills", "files", "diagnostics", "usage"))
                val profileId = args.text("profile_id")
                if (area in setOf("model_credentials", "model_advanced", "provider_probe", "oauth", "model_catalog")) require(profileId != null)
                if (profileId != null) require(models.find(profileId) != null)
                val request = NativeAgentManagementRequest(UUID.randomUUID().toString(), ref, requireNotNull(run.chatId), area, profileId)
                val current = pendingState.value
                require(current == null || current.ref == ref && current.chat == request.chat && current.area == area && current.profileId == profileId)
                if (current == null) require(pendingState.compareAndSet(null, request))
                JsonObjectValue.parse(buildJsonObject {
                    put("state", "USER_INTERACTION_REQUIRED"); put("area", area); put("request_id", current?.id ?: request.id)
                }.toString(), JsonInputLimits(4096, 4))
            }),
    )
}
