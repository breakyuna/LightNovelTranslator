package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UsageLedgerRecorderTest {
    @Test fun knownReceiptsAccumulatePerRunAndModelWithoutGuessingUnknowns() = runBlocking {
        val recorder = UsageLedgerRecorder()
        val run = RunIdentity(ProjectId("p"), ChatId("c"), RunId("r"), CommandId("command"))
        val ref = ModelConfigurationRef("model", 1)
        val usage = RequestUsage(TokenMeasurement(10, UsageSource.ACTUAL), TokenMeasurement(null, UsageSource.UNKNOWN),
            TokenMeasurement(null, UsageSource.UNKNOWN), TokenMeasurement(null, UsageSource.UNKNOWN),
            TokenMeasurement(5, UsageSource.ACTUAL))
        recorder.record(UsageRequestRecord(run, RequestAttemptId("a1"), UsageModelRef(ref, "fixture"), usage))
        recorder.record(UsageRequestRecord(run, RequestAttemptId("a2"), UsageModelRef(ref, "fixture"), usage))
        val snapshot = recorder.snapshot()
        assertEquals(2L, snapshot.total.responses)
        assertEquals(BigInteger.valueOf(20), snapshot.total.input.actual)
        assertEquals(BigInteger.valueOf(10), snapshot.total.output.actual)
        assertEquals(0L, snapshot.total.input.unknownRequests)
        assertEquals(BigInteger.valueOf(20), snapshot.runTotal(run).input.actual)
    }
}
