package com.breakyuna.noveltranslator.nativecore.workspace

/**
 * Ordered, fail-closed search chain. A provider's normal empty [WorkspaceSearchOutcome.Found]
 * stops the chain (the query genuinely had no results); transport/provider failures advance to
 * the next provider. Cancellation ends the whole chain and is never converted into a fallback.
 * No provider is retried here.
 */
class WorkspaceSearchChain(private val providers: List<WorkspaceSearchPort>) : WorkspaceSearchPort {
    init { require(providers.isNotEmpty()) { "Search chain requires at least one provider" } }

    override suspend fun search(request: WorkspaceSearchRequest): WorkspaceSearchOutcome {
        var last: WorkspaceNetworkError? = null
        for (provider in providers) {
            if (request.cancellation.isCancelled) return WorkspaceSearchOutcome.Cancelled
            when (val outcome = provider.search(request)) {
                is WorkspaceSearchOutcome.Found -> return outcome
                is WorkspaceSearchOutcome.Failed -> last = outcome.error
                WorkspaceSearchOutcome.Cancelled -> return WorkspaceSearchOutcome.Cancelled
            }
        }
        return WorkspaceSearchOutcome.Failed(last ?: WorkspaceNetworkError(WorkspaceNetworkFailure.INTERNAL))
    }
}
