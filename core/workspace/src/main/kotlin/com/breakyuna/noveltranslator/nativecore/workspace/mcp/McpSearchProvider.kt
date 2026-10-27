package com.breakyuna.noveltranslator.nativecore.workspace.mcp

import com.breakyuna.noveltranslator.nativecore.domain.InvalidJsonPayload
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceNetworkError
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceNetworkFailure
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSearchHit
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSearchOutcome
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSearchPort
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSearchRequest
import java.net.InetAddress
import java.net.URI
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Declarative arguments for a search-shaped MCP tool. [extraArguments] are copied first and
 * query/limit overwrite the same keys, so a caller's shaping cannot be silently overridden.
 */
class McpSearchTool(
    val toolName: String,
    val queryField: String = "query",
    val limitField: String? = "maxResults",
    private val extraArguments: JsonObjectValue? = null,
) {
    init {
        require(toolName.isNotEmpty() && toolName.length <= McpLimits.MAX_TOOL_NAME_CHARS) {
            "Invalid MCP search tool"
        }
        require(toolName.none { it.isISOControl() || it.isWhitespace() }) { "Invalid MCP search tool" }
        require(FIELD.matches(queryField)) { "Invalid MCP query field" }
        if (limitField != null) {
            require(FIELD.matches(limitField) && limitField != queryField) {
                "Invalid MCP limit field"
            }
        }
    }

    internal fun arguments(query: String, maxResults: Int): JsonObjectValue {
        val built = buildJsonObject {
            extraArguments?.json?.forEach { (key, value) -> put(key, value) }
            put(queryField, query)
            limitField?.let { put(it, maxResults) }
        }
        return try {
            JsonObjectValue.parse(
                built.toString(),
                JsonInputLimits(McpLimits.MAX_REQUEST_BYTES, 32),
            )
        } catch (_: InvalidJsonPayload) {
            throw McpException(McpFailureKind.INVALID_REQUEST)
        } catch (_: Exception) {
            throw McpException(McpFailureKind.INVALID_REQUEST)
        }
    }

    override fun toString(): String = "McpSearchTool(name=$toolName, queryField=$queryField)"

    private companion object {
        val FIELD = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    }
}

/** Raw external hit before URL, control-character and byte-budget validation. */
class McpSearchCandidate(val title: String, val url: String, val snippet: String)

class McpSearchExtraction(
    candidates: List<McpSearchCandidate>,
    val truncated: Boolean,
) {
    val candidates: List<McpSearchCandidate> = Collections.unmodifiableList(ArrayList(candidates))
}

/**
 * Converts bounded MCP tool output into candidates. Implementations must never trust URLs or
 * text; [McpSearchProvider] performs the final public-URL and size checks.
 */
interface McpSearchResultExtractor {
    fun extract(result: McpToolResult, maxResults: Int): McpSearchExtraction
}

/**
 * Default extractor for the common `{"results":[{title,url,description}]}` shape, whether it
 * arrives as structured content or as a JSON text content item. It also accepts `hits`/`items`
 * arrays and a text-only URL list. Unknown shapes produce an empty success, not fabricated hits.
 */
object McpJsonSearchResultExtractor : McpSearchResultExtractor {
    private val ARRAY_KEYS = listOf("results", "hits", "items")

    override fun extract(result: McpToolResult, maxResults: Int): McpSearchExtraction {
        if (maxResults <= 0) return McpSearchExtraction(emptyList(), truncated = false)
        val candidates = ArrayList<McpSearchCandidate>()
        val limit = maxResults + 1
        result.structuredContent?.let { collectObject(it.json, candidates, limit) }
        for (content in result.content) {
            if (content !is McpContent.Text) continue
            val text = content.text.trim()
            if (text.isEmpty()) continue
            val parsed = parseObject(text)
            if (parsed != null) {
                collectObject(parsed, candidates, limit)
            } else if (isHttpUrl(text)) {
                addCandidate(candidates, McpSearchCandidate(text, text, ""), limit)
            }
        }
        return McpSearchExtraction(candidates, candidates.size > maxResults)
    }

    private fun collectObject(
        value: JsonObject,
        candidates: MutableList<McpSearchCandidate>,
        maxResults: Int,
    ) {
        val array = ARRAY_KEYS.firstNotNullOfOrNull { value[it] as? JsonArray }
        if (array == null) {
            val url = textOf(value["url"]) ?: textOf(value["link"])
            if (url != null) {
                addCandidate(
                    candidates,
                    McpSearchCandidate(
                        title = textOf(value["title"]) ?: textOf(value["name"]) ?: "",
                        url = url,
                        snippet = textOf(value["snippet"]) ?: textOf(value["description"]) ?: "",
                    ),
                    maxResults,
                )
            }
            return
        }
        for (element in array) {
            if (candidates.size >= maxResults) return
            val item = element as? JsonObject ?: continue
            val url = textOf(item["url"]) ?: textOf(item["link"]) ?: continue
            addCandidate(
                candidates,
                McpSearchCandidate(
                    title = textOf(item["title"]) ?: textOf(item["name"]) ?: "",
                    url = url,
                    snippet = textOf(item["snippet"]) ?: textOf(item["description"]) ?: textOf(item["content"]) ?: "",
                ),
                maxResults,
            )
        }
    }

    private fun addCandidate(
        candidates: MutableList<McpSearchCandidate>,
        candidate: McpSearchCandidate,
        maxResults: Int,
    ) {
        if (candidates.size < maxResults) candidates += candidate
    }

    private fun parseObject(text: String): JsonObject? {
        if (!text.startsWith("{") || text.length > McpLimits.MAX_TOOL_TEXT_CHARS) return null
        return try {
            JsonObjectValue.parse(text, JsonInputLimits(McpLimits.MAX_TOOL_TEXT_CHARS, 32)).json
        } catch (_: InvalidJsonPayload) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun textOf(value: JsonElement?): String? {
        val primitive = value as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    private fun isHttpUrl(value: String): Boolean {
        if (value.length > McpLimits.MAX_URL_CHARS || value.any { it.isWhitespace() || it.isISOControl() }) {
            return false
        }
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return false
        }
        return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()
    }
}

/**
 * MCP-backed [WorkspaceSearchPort]. It invokes one allowlisted search tool and converts only
 * bounded, public, well-formed results into [WorkspaceSearchHit]s. Provider errors advance the
 * normal search chain; a valid empty result is success and stops the chain.
 */
class McpSearchProvider(
    private val invoker: McpToolInvoker,
    private val tool: McpSearchTool,
    private val extractor: McpSearchResultExtractor = McpJsonSearchResultExtractor,
) : WorkspaceSearchPort {
    constructor(
        invoker: McpToolInvoker,
        toolName: String,
        extractor: McpSearchResultExtractor = McpJsonSearchResultExtractor,
    ) : this(invoker, McpSearchTool(toolName), extractor)

    override suspend fun search(request: WorkspaceSearchRequest): WorkspaceSearchOutcome {
        if (request.cancellation.isCancelled) return WorkspaceSearchOutcome.Cancelled
        currentCoroutineContext().ensureActive()
        if (request.deadlineAtMillis <= System.currentTimeMillis()) {
            return WorkspaceSearchOutcome.Failed(WorkspaceNetworkError(WorkspaceNetworkFailure.TIMEOUT))
        }
        val arguments = try {
            tool.arguments(request.query, request.maxResults)
        } catch (failure: McpException) {
            return failed(failure)
        }

        val result = try {
            invoker.callTool(
                toolName = tool.toolName,
                arguments = arguments,
                cancellation = request.cancellation,
                deadlineAtMillis = request.deadlineAtMillis,
            )
        } catch (cancelled: CancellationException) {
            if (request.cancellation.isCancelled) return WorkspaceSearchOutcome.Cancelled
            throw cancelled
        } catch (failure: McpException) {
            return failed(failure)
        } catch (_: Exception) {
            return WorkspaceSearchOutcome.Failed(WorkspaceNetworkError(WorkspaceNetworkFailure.INTERNAL))
        }
        if (request.cancellation.isCancelled) return WorkspaceSearchOutcome.Cancelled
        currentCoroutineContext().ensureActive()
        if (result.isError) {
            return WorkspaceSearchOutcome.Failed(WorkspaceNetworkError(WorkspaceNetworkFailure.INTERNAL))
        }

        val extraction = try {
            extractor.extract(result, request.maxResults)
        } catch (_: Exception) {
            return WorkspaceSearchOutcome.Failed(
                WorkspaceNetworkError(WorkspaceNetworkFailure.UNSUPPORTED_CONTENT),
            )
        }
        val hits = ArrayList<WorkspaceSearchHit>()
        var truncated = extraction.truncated
        for (candidate in extraction.candidates) {
            if (hits.size >= request.maxResults) {
                truncated = true
                break
            }
            if (!mayBePublicHttpUrl(candidate.url)) continue
            val title = McpText.sanitize(
                candidate.title.ifBlank { candidate.url },
                512,
                allowNewlines = false,
            )
            val snippetText = McpText.sanitize(candidate.snippet, 4_096, allowNewlines = true)
            val (snippet, cut) = McpText.truncateUtf8(snippetText, request.maxBytesPerResult)
            if (cut) truncated = true
            val hit = try {
                WorkspaceSearchHit(title, candidate.url, snippet)
            } catch (_: IllegalArgumentException) {
                // Non-public, credential-bearing or malformed URLs are not search hits.
                continue
            }
            hits += hit
        }
        return WorkspaceSearchOutcome.Found(hits, truncated)
    }

    private fun failed(failure: McpException): WorkspaceSearchOutcome {
        if (failure.kind == McpFailureKind.CANCELLED) return WorkspaceSearchOutcome.Cancelled
        val category = if (failure.kind == McpFailureKind.HTTP_STATUS && failure.httpStatus == 429) {
            WorkspaceNetworkFailure.RATE_LIMITED
        } else {
            failure.kind.toWorkspaceNetworkFailure()
        }
        return WorkspaceSearchOutcome.Failed(
            WorkspaceNetworkError(category, failure.httpStatus, failure.retryAfterMillis),
        )
    }

    /**
     * Explicit literal-address check. Hostnames are left to the fetch policy because resolving
     * them here would add a second DNS lookup; the search hit contract already rejects
     * credential-bearing URLs and non-HTTP schemes.
     */
    private fun mayBePublicHttpUrl(value: String): Boolean {
        if (value.length > McpLimits.MAX_URL_CHARS ||
            value.any { it.isWhitespace() || it.isISOControl() }
        ) {
            return false
        }
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        if (uri.userInfo != null) return false
        val host = uri.host ?: return false
        val literalHost = host.removePrefix("[").removeSuffix("]").substringBefore('%')
        val literal = literalHost.equals("localhost", ignoreCase = true) ||
            IPV4_LITERAL.matches(literalHost) || literalHost.contains(':')
        if (!literal) return true
        val address = try {
            InetAddress.getByName(literalHost)
        } catch (_: Exception) {
            return false
        }
        return McpNetworkPolicy.isAllowed(address, false)
    }

    override fun toString(): String = "McpSearchProvider(tool=${tool.toolName})"

    private companion object {
        val IPV4_LITERAL = Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")
    }
}
