package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.agent.AgentToolHandler
import com.breakyuna.noveltranslator.nativecore.agent.RegisteredAgentTool
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceCancellation
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSearchOutcome
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSearchPort
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSearchRequest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Search results are external data, never instructions or project facts. */
internal fun nativeWorkspaceSearchTools(
    runtime: RuntimePort,
    lease: RunLease,
    search: WorkspaceSearchPort,
) = listOf(RegisteredAgentTool(
    "web_search",
    "Search the public web through the configured provider chain. Returns bounded title, URL and " +
        "snippet records marked external_untrusted; a normal empty result is success, provider " +
        "failures are reported without silently inventing hits. No login, cookies or model " +
        "credentials are sent. Use web_fetch to read one selected public page.",
    ToolParameterSchema(mapOf(
        "query" to ToolParameter.Text(1, 512),
        "max_results" to ToolParameter.Integer(1, 10, required = false),
    )), ToolEffect.READ_ONLY, AgentToolHandler { run, arguments ->
        require(run == lease.identity && run.projectId == lease.identity.projectId)
        val operation = runtime.beginOperation(lease, OperationKind.HOST_REQUEST)
        val cancellation = WorkspaceCancellation()
        try {
            withTimeout(60_000) {
                val outcome = search.search(WorkspaceSearchRequest.create(
                    query = requireNotNull(arguments.text("query")),
                    deadlineAtMillis = System.currentTimeMillis() + 60_000,
                    cancellation = cancellation,
                    maxResults = (arguments.integer("max_results") ?: 5L).toInt(),
                    maxBytesPerResult = 4_096,
                ))
                JsonObjectValue.parse(buildJsonObject {
                    put("external_untrusted", true)
                    when (outcome) {
                        WorkspaceSearchOutcome.Cancelled -> throw kotlinx.coroutines.CancellationException("Search cancelled")
                        is WorkspaceSearchOutcome.Failed -> {
                            put("ok", false)
                            put("error", outcome.error.category.name)
                            outcome.error.httpStatus?.let { put("http_status", it) }
                        }
                        is WorkspaceSearchOutcome.Found -> {
                            put("ok", true)
                            put("truncated", outcome.truncated)
                            put("hits", buildJsonArray {
                                outcome.hits.forEach { hit ->
                                    add(buildJsonObject {
                                        put("title", hit.title)
                                        put("url", hit.url)
                                        put("snippet", hit.snippet)
                                    })
                                }
                            })
                        }
                    }
                }.toString(), JsonInputLimits(512 * 1024, 8))
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            JsonObjectValue.parse(
                """{"external_untrusted":true,"ok":false,"error":"TIMEOUT"}""",
                JsonInputLimits(1024, 4),
            )
        } finally {
            cancellation.cancel()
            runtime.endOperation(operation)
        }
    })
)
