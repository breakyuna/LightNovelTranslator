package com.breakyuna.noveltranslator.nativeapp.net

import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceFetchOutcome
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceFetchPort
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceFetchRequest
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceNetworkError
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceNetworkFailure
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSearchHit
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSearchOutcome
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSearchPort
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSearchRequest
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import org.jsoup.Jsoup

/**
 * Keyless public web search through DuckDuckGo's HTML endpoint. It is a real search provider, not
 * a fetch of a user-supplied page: only the query is sent, results are parsed into bounded title /
 * URL / snippet records, and the same public-network, redirect, timeout and cancellation policy
 * as workspace fetch applies. No cookies, model credentials or result bodies are retained.
 */
internal class NativeDuckDuckGoSearch(
    private val fetch: WorkspaceFetchPort,
) : WorkspaceSearchPort {
    override suspend fun search(request: WorkspaceSearchRequest): WorkspaceSearchOutcome {
        if (request.cancellation.isCancelled) return WorkspaceSearchOutcome.Cancelled
        val query = URLEncoder.encode(request.query, Charsets.UTF_8.name())
            .replace("+", "%20")
        val url = "https://html.duckduckgo.com/html/?q=$query&kl=" +
            when (request.language?.lowercase()?.substringBefore('-')) {
                "zh" -> "cn-zh"
                "ja" -> "jp-jp"
                "ko" -> "kr-kr"
                "de" -> "de-de"
                else -> "us-en"
            }
        val maxBytes = (request.maxResults.toLong() * request.maxBytesPerResult)
            .coerceIn(256L * 1024, 1024L * 1024)
        val outcome = fetch.fetch(WorkspaceFetchRequest.create(
            url = url,
            deadlineAtMillis = request.deadlineAtMillis,
            cancellation = request.cancellation,
            maxBytes = maxBytes,
            allowedMediaTypes = setOf("text/html"),
        ))
        return when (outcome) {
            WorkspaceFetchOutcome.Cancelled -> WorkspaceSearchOutcome.Cancelled
            is WorkspaceFetchOutcome.Failed -> WorkspaceSearchOutcome.Failed(outcome.error)
            is WorkspaceFetchOutcome.Fetched -> parse(outcome, request)
        }
    }

    private fun parse(
        outcome: WorkspaceFetchOutcome.Fetched,
        request: WorkspaceSearchRequest,
    ): WorkspaceSearchOutcome {
        val html = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(outcome.document.bytes)).toString()
        } catch (_: Exception) {
            return WorkspaceSearchOutcome.Failed(WorkspaceNetworkError(WorkspaceNetworkFailure.UNSUPPORTED_CONTENT))
        }
        val document = try { Jsoup.parse(html) } catch (_: Exception) {
            return WorkspaceSearchOutcome.Failed(WorkspaceNetworkError(WorkspaceNetworkFailure.UNSUPPORTED_CONTENT))
        }
        val hits = ArrayList<WorkspaceSearchHit>()
        for (result in document.select("div.result")) {
            if (hits.size >= request.maxResults) break
            val anchor = result.selectFirst("a.result__a") ?: continue
            val url = normalize(anchor.attr("href")) ?: continue
            val title = anchor.text().trim().take(512)
            val snippet = result.selectFirst(".result__snippet")?.text()?.trim()?.take(4_096).orEmpty()
            try {
                hits += WorkspaceSearchHit(title.ifBlank { url }, url, snippet)
            } catch (_: IllegalArgumentException) {
                // A result pointing at a non-public address is not a search hit.
            }
        }
        return WorkspaceSearchOutcome.Found(hits, truncated = outcome.document.truncated)
    }

    private fun normalize(href: String): String? {
        val raw = href.trim()
        if (raw.isEmpty()) return null
        val candidate = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/l/?") -> "https://duckduckgo.com$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> return null
        }
        val uri = try { URI(candidate) } catch (_: Exception) { return null }
        if (uri.host?.endsWith("duckduckgo.com") == true) {
            val target = queryParameter(uri.rawQuery, "uddg") ?: return null
            return try { URLDecoder.decode(target, Charsets.UTF_8.name()) } catch (_: Exception) { null }
        }
        return candidate
    }

    private fun queryParameter(query: String?, name: String): String? =
        query?.split('&')?.firstNotNullOfOrNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index).let { key ->
                if (key == name) part.substring(index + 1) else null
            }
        }
}
