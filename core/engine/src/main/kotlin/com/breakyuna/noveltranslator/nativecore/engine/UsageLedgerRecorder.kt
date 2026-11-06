package com.breakyuna.noveltranslator.nativecore.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-local bridge from successful model receipts to the immutable UsageLedger.
 * It does not price requests, persist across process death, or infer a token count
 * from text. A failed call with an unknown receipt is not recorded as zero.
 */
class UsageLedgerRecorder(maxRecords: Int = UsageLedger.DEFAULT_MAX_RECORDS) {
    private val mutex = Mutex()
    private var current = UsageLedger.create(maxRecords)

    suspend fun record(record: UsageRequestRecord) {
        mutex.withLock { current = current.record(record) }
    }

    suspend fun snapshot(): UsageLedger = mutex.withLock { current }
}
