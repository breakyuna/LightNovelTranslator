package com.breakyuna.noveltranslator.nativecore.workspace.mcp

import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceNetworkFailure

/**
 * Stable, bounded MCP failure classes. No variant carries server text, a URL or a credential;
 * callers may show the enum or map it into an existing workspace failure category.
 */
enum class McpFailureKind {
    INVALID_CONFIG,
    INVALID_REQUEST,
    DISABLED,
    CREDENTIAL_UNAVAILABLE,
    POLICY_REJECTED,
    DNS,
    CONNECT,
    TLS,
    TIMEOUT,
    HTTP_STATUS,
    CONTENT_TOO_LARGE,
    UNSUPPORTED_CONTENT,
    CANCELLED,
    NOT_INITIALIZED,
    PROTOCOL,
    BATCH_NOT_SUPPORTED,
    UNSUPPORTED_PROTOCOL,
    CAPABILITY_MISSING,
    RPC_ERROR,
    TOOL_NOT_ALLOWED,
    NAME_CONFLICT,
    INTERNAL,
}

/**
 * A bounded MCP failure. The exception message contains only [kind]; it never contains a
 * credential, request body, server URL, tool result or server-provided error message.
 * [httpStatus], [rpcCode] and [retryAfterMillis] are validated metadata, not free text.
 */
class McpException(
    val kind: McpFailureKind,
    val httpStatus: Int? = null,
    val rpcCode: Int? = null,
    val retryAfterMillis: Long? = null,
) : Exception("MCP failure: $kind") {
    init {
        require(httpStatus == null || httpStatus in 100..599) { "Invalid HTTP status" }
        require(retryAfterMillis == null || retryAfterMillis in 0L..McpLimits.MAX_RETRY_AFTER_MILLIS) {
            "Invalid retry delay"
        }
    }

    override fun toString(): String =
        "McpException(kind=$kind, httpStatus=$httpStatus, rpcCode=$rpcCode)"
}

/** Maps protocol failures into the category set shared with search/fetch callers. */
fun McpFailureKind.toWorkspaceNetworkFailure(): WorkspaceNetworkFailure = when (this) {
    McpFailureKind.INVALID_CONFIG,
    McpFailureKind.INVALID_REQUEST -> WorkspaceNetworkFailure.INVALID_REQUEST
    McpFailureKind.DISABLED,
    McpFailureKind.CREDENTIAL_UNAVAILABLE,
    McpFailureKind.POLICY_REJECTED,
    McpFailureKind.CAPABILITY_MISSING,
    McpFailureKind.TOOL_NOT_ALLOWED,
    McpFailureKind.NAME_CONFLICT,
    McpFailureKind.NOT_INITIALIZED,
    McpFailureKind.UNSUPPORTED_PROTOCOL -> WorkspaceNetworkFailure.POLICY_REJECTED
    McpFailureKind.DNS -> WorkspaceNetworkFailure.DNS
    McpFailureKind.CONNECT -> WorkspaceNetworkFailure.CONNECT
    McpFailureKind.TLS -> WorkspaceNetworkFailure.TLS
    McpFailureKind.TIMEOUT -> WorkspaceNetworkFailure.TIMEOUT
    McpFailureKind.HTTP_STATUS -> WorkspaceNetworkFailure.HTTP_STATUS
    McpFailureKind.CONTENT_TOO_LARGE -> WorkspaceNetworkFailure.CONTENT_TOO_LARGE
    McpFailureKind.UNSUPPORTED_CONTENT -> WorkspaceNetworkFailure.UNSUPPORTED_CONTENT
    McpFailureKind.CANCELLED -> WorkspaceNetworkFailure.CANCELLED
    McpFailureKind.PROTOCOL,
    McpFailureKind.BATCH_NOT_SUPPORTED,
    McpFailureKind.RPC_ERROR,
    McpFailureKind.INTERNAL -> WorkspaceNetworkFailure.INTERNAL
}
