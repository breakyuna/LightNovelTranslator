package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.ModelStopReason
import com.breakyuna.noveltranslator.nativecore.domain.RequestAttemptId
import com.breakyuna.noveltranslator.nativecore.domain.RequestUsage
import com.breakyuna.noveltranslator.nativecore.domain.RequestUsageTotals
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import java.util.Collections

/** Stable model identity for totals; the endpoint and credentials are never part of the ledger. */
data class UsageModelRef(val configuration: ModelConfigurationRef, val model: String) {
    init {
        require(model.isNotBlank() && model.length <= 200 && model.none(Char::isISOControl))
    }
}

/**
 * One response receipt. It intentionally contains no prompt, completion, thinking
 * or tool-argument body. Usage remains the already-normalized [RequestUsage], so
 * ACTUAL, ESTIMATED and UNKNOWN are preserved per component.
 */
class UsageRequestRecord(
    val run: RunIdentity,
    val attemptId: RequestAttemptId,
    val model: UsageModelRef,
    val usage: RequestUsage,
    val stopReason: ModelStopReason? = null,
    val failure: ModelFailureKind? = null,
) {
    override fun toString(): String =
        "UsageRequestRecord(run=${run.runId.value}, attempt=${attemptId.value}, model=${model.model}, " +
            "stopReason=$stopReason, failure=$failure, usage=$usage)"
}

/**
 * Immutable usage ledger. Token totals use the domain `BigInteger`-backed
 * [RequestUsageTotals]; receipt counters use checked addition, so accumulation
 * fails instead of silently wrapping. Unknown is never converted to zero.
 *
 * Per-request diagnostics are bounded by [maxRecords]. When the bound is exceeded
 * the oldest record is dropped and [droppedRecords] is incremented; totals are
 * never affected by that eviction. The ledger is a value: callers must publish the
 * object returned by [record].
 */
class UsageLedger private constructor(
    records: List<UsageRequestRecord>,
    val droppedRecords: Long,
    val total: RequestUsageTotals,
    runTotals: Map<RunIdentity, RequestUsageTotals>,
    modelTotals: Map<UsageModelRef, RequestUsageTotals>,
    val maxRecords: Int,
) {
    val records: List<UsageRequestRecord> = Collections.unmodifiableList(ArrayList(records))
    val runTotals: Map<RunIdentity, RequestUsageTotals> =
        Collections.unmodifiableMap(LinkedHashMap(runTotals))
    val modelTotals: Map<UsageModelRef, RequestUsageTotals> =
        Collections.unmodifiableMap(LinkedHashMap(modelTotals))

    init {
        require(maxRecords in 0..1_000_000)
        require(this.records.size <= maxRecords)
        require(droppedRecords >= 0)
    }

    /** Adds one receipt to the global, run and model aggregates. */
    fun record(record: UsageRequestRecord): UsageLedger {
        val nextRecords = ArrayList(records)
        nextRecords.add(record)
        var dropped = droppedRecords
        if (nextRecords.size > maxRecords) {
            nextRecords.removeAt(0)
            dropped = Math.addExact(dropped, 1)
        }
        val nextRunTotals = LinkedHashMap(runTotals)
        nextRunTotals[record.run] = (runTotals[record.run] ?: RequestUsageTotals()).add(record.usage)
        val nextModelTotals = LinkedHashMap(modelTotals)
        nextModelTotals[record.model] = (modelTotals[record.model] ?: RequestUsageTotals()).add(record.usage)
        return UsageLedger(nextRecords, dropped, total.add(record.usage), nextRunTotals, nextModelTotals, maxRecords)
    }

    fun runTotal(run: RunIdentity): RequestUsageTotals = runTotals[run] ?: RequestUsageTotals()

    fun modelTotal(model: UsageModelRef): RequestUsageTotals = modelTotals[model] ?: RequestUsageTotals()

    fun recordsFor(run: RunIdentity): List<UsageRequestRecord> =
        Collections.unmodifiableList(records.filter { it.run == run })

    override fun toString(): String =
        "UsageLedger(records=${records.size}, droppedRecords=$droppedRecords, total=$total, maxRecords=$maxRecords)"

    companion object {
        const val DEFAULT_MAX_RECORDS = 10_000

        fun create(maxRecords: Int = DEFAULT_MAX_RECORDS): UsageLedger {
            require(maxRecords in 0..1_000_000)
            return UsageLedger(emptyList(), 0, RequestUsageTotals(), emptyMap(), emptyMap(), maxRecords)
        }

        val EMPTY: UsageLedger = create()
    }
}
