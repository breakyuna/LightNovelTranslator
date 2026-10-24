package com.breakyuna.noveltranslator.nativecore.domain

/** Explicit scope; null ref means all locally recorded usage, never the first project. */
data class UsageLedgerQuery(
    val ref: LocalProjectRef? = null,
    val chat: ChatId? = null,
    val run: RunIdentity? = null,
    val configuration: ModelConfigurationRef? = null,
) {
    init {
        require(chat == null || ref != null)
        require(run == null || ref != null && run.projectId == ref.projectId && (chat == null || run.chatId == chat))
    }
}

/** Dispatch counts mark the durable dispatch boundary, not confirmed network delivery or billing.
 * Missing provider usage/receipts cannot imply zero cost. */
class DurableUsageSummary(
    val prepared: Long,
    val dispatched: Long,
    val unresolved: Long,
    val totals: RequestUsageTotals,
) {
    init {
        require(prepared >= 0 && dispatched >= 0 && unresolved in 0..dispatched)
        require(totals.responses == dispatched - unresolved)
    }
}

interface UsageLedgerReadPort {
    /** Reads a consistent local snapshot; previous releases without a ledger are not inferred. */
    suspend fun summary(query: UsageLedgerQuery = UsageLedgerQuery()): DurableUsageSummary
    /** Newest first; each page is consistent, receipt updates require an explicit refresh. */
    suspend fun entries(query: UsageLedgerQuery, cursor: UsageLedgerCursor? = null): UsageLedgerPage =
        throw UnsupportedOperationException("Usage details unavailable")
    /** JSON Lines with an explicit completion footer; destination ownership is transferred. */
    suspend fun export(query: UsageLedgerQuery, destination: () -> java.io.OutputStream): Long =
        throw UnsupportedOperationException("Usage export unavailable")
}

enum class UsageReceiptState { PREPARED, DISPATCHED, RECEIVED }
enum class UsageReceiptChannel { AGENT, TRANSLATION }

/** Metadata only; missing usage is never converted into zero. */
class UsageLedgerEntry(
    val id: String, val channel: UsageReceiptChannel, val attempt: RequestAttemptId,
    val ref: LocalProjectRef?, val run: RunIdentity, val configuration: ModelConfigurationRef,
    val model: String?, val state: UsageReceiptState, val usage: RequestUsage?,
    val stop: ModelStopReason?, val failure: ModelFailureKind?,
) {
    init {
        require((state == UsageReceiptState.RECEIVED) == (usage != null))
        require(ref == null || ref.projectId == run.projectId)
        require(state == UsageReceiptState.RECEIVED || stop == null && failure == null)
    }
}

data class UsageLedgerCursor(val query: UsageLedgerQuery, val before: Long) {
    init { require(before > 0) }
}
class UsageLedgerPage(entries: List<UsageLedgerEntry>, val next: UsageLedgerCursor?) {
    val entries = frozenList(entries)
}
