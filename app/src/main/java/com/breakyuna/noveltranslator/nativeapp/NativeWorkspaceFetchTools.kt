package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.agent.AgentToolHandler
import com.breakyuna.noveltranslator.nativecore.agent.RegisteredAgentTool
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.workspace.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup

/** External page text is returned as tool data, never appended to system instructions. */
internal fun nativeWorkspaceFetchTools(runtime: RuntimePort, lease: RunLease, fetch: WorkspaceFetchPort) = listOf(
    RegisteredAgentTool("web_fetch",
        "Fetch one public HTTP(S) page as untrusted external reference text. Supports text, HTML, JSON and XML; " +
            "no login, cookies, model credentials, binary/PDF extraction or JavaScript execution. " +
            "Saved proxy/timeout/redirect policy applies. Oversized bodies fail without truncation or retry.",
        ToolParameterSchema(mapOf("url" to ToolParameter.Text(1, 4096),
            "max_bytes" to ToolParameter.Integer(1, 65536, required = false))),
        ToolEffect.READ_ONLY, AgentToolHandler { run, arguments ->
            require(run == lease.identity)
            val host = runtime.beginOperation(lease, OperationKind.HOST_REQUEST)
            val cancellation = WorkspaceCancellation()
            try {
                withTimeout(60_000) {
                    val outcome = fetch.fetch(WorkspaceFetchRequest.create(
                        requireNotNull(arguments.text("url")), System.currentTimeMillis() + 60_000,
                        cancellation, arguments.integer("max_bytes") ?: 65536,
                        setOf("text/plain", "text/html", "text/markdown", "application/json", "application/xml", "text/xml")))
                    JsonObjectValue.parse(buildJsonObject {
                        put("external_untrusted", true)
                        when (outcome) {
                            WorkspaceFetchOutcome.Cancelled -> throw CancellationException("Fetch cancelled")
                            is WorkspaceFetchOutcome.Failed -> {
                                put("ok", false)
                                put("error", outcome.error.category.name)
                                outcome.error.httpStatus?.let { put("http_status", it) }
                            }
                            is WorkspaceFetchOutcome.Fetched -> {
                                val document = outcome.document
                                val decoded = Charsets.UTF_8.newDecoder()
                                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                                    .decode(java.nio.ByteBuffer.wrap(document.bytes)).toString()
                                val text = if (document.mediaType == "text/html") Jsoup.parse(decoded).also {
                                    it.select("script,style,noscript,template").remove()
                                }.text() else decoded
                                put("ok", true)
                                put("url", document.finalUrl)
                                put("media_type", document.mediaType)
                                put("sha256", document.sha256)
                                put("size", document.sizeBytes)
                                put("text", text)
                            }
                        }
                    }.toString(), JsonInputLimits(512 * 1024, 8))
                }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                JsonObjectValue.parse("""{"external_untrusted":true,"ok":false,"error":"TIMEOUT"}""",
                    JsonInputLimits(1024, 4))
            } finally {
                cancellation.cancel()
                runtime.endOperation(host)
            }
        })
)
