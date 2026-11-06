package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.RequestAttemptId
import com.breakyuna.noveltranslator.nativecore.domain.RequestUsage
import com.breakyuna.noveltranslator.nativecore.domain.RequestUsageTotals
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.TokenMeasurement
import com.breakyuna.noveltranslator.nativecore.domain.UsageSource
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageLedgerTest {
    private val unknown = TokenMeasurement(null, UsageSource.UNKNOWN)

    private fun actual(tokens: Long) = TokenMeasurement(tokens, UsageSource.ACTUAL)

    private fun estimated(tokens: Long) = TokenMeasurement(tokens, UsageSource.ESTIMATED)

    private fun usageOf(
        input: TokenMeasurement = unknown,
        cacheRead: TokenMeasurement = unknown,
        cacheWrite: TokenMeasurement = unknown,
        thinking: TokenMeasurement = unknown,
        output: TokenMeasurement = unknown,
    ) = RequestUsage(input, cacheRead, cacheWrite, thinking, output)

    private fun run(name: String) = RunIdentity(ProjectId("project"), null, RunId(name), CommandId("command-$name"))

    private fun model(name: String = "fixture", configuration: String = "model") =
        UsageModelRef(ModelConfigurationRef(configuration, 1), name)

    private fun record(
        run: RunIdentity = run("run"),
        model: UsageModelRef = model(),
        usage: RequestUsage = usageOf(),
        attempt: String = "attempt",
    ) = UsageRequestRecord(run, RequestAttemptId(attempt), model, usage)

    @Test
    fun actualEstimatedAndUnknownStaySeparateForEveryComponent() {
        val ledger = UsageLedger.EMPTY.record(record(usage = usageOf(
            input = actual(10),
            cacheRead = estimated(4),
            cacheWrite = unknown,
            thinking = actual(2),
            output = estimated(5),
        )))
        assertEquals(1L, ledger.total.responses)
        assertEquals(BigInteger.valueOf(10), ledger.total.input.actual)
        assertEquals(BigInteger.ZERO, ledger.total.input.estimated)
        assertEquals(0L, ledger.total.input.unknownRequests)
        assertEquals(BigInteger.ZERO, ledger.total.cacheRead.actual)
        assertEquals(BigInteger.valueOf(4), ledger.total.cacheRead.estimated)
        assertEquals(1L, ledger.total.cacheWrite.unknownRequests)
        assertEquals(BigInteger.valueOf(2), ledger.total.thinking.actual)
        assertEquals(BigInteger.valueOf(5), ledger.total.output.estimated)
    }

    @Test
    fun unknownIsNotConvertedToZero() {
        val ledger = UsageLedger.EMPTY.record(record())
        assertEquals(BigInteger.ZERO, ledger.total.input.actual)
        assertEquals(1L, ledger.total.input.unknownRequests)
        assertEquals(1L, ledger.total.output.unknownRequests)
        assertEquals(1L, ledger.total.responses)
    }

    @Test
    fun tokenTotalsExceedLongWithoutWrapping() {
        val maximum = actual(Long.MAX_VALUE)
        val ledger = UsageLedger.EMPTY
            .record(record(attempt = "one", usage = usageOf(input = maximum, output = maximum)))
            .record(record(attempt = "two", usage = usageOf(input = maximum, output = maximum)))
        val expected = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(2))
        assertEquals(expected, ledger.total.input.actual)
        assertEquals(expected, ledger.total.output.actual)
        assertEquals(2L, ledger.total.responses)
    }

    @Test
    fun perRunAndPerModelTotalsAreIndependent() {
        val firstRun = run("first")
        val secondRun = run("second")
        val firstModel = model("a", "config-a")
        val secondModel = model("b", "config-b")
        val ledger = UsageLedger.EMPTY
            .record(record(firstRun, firstModel, usageOf(input = actual(10))))
            .record(record(firstRun, firstModel, usageOf(input = actual(5))))
            .record(record(secondRun, secondModel, usageOf(input = estimated(7))))

        assertEquals(2L, ledger.runTotal(firstRun).responses)
        assertEquals(BigInteger.valueOf(15), ledger.runTotal(firstRun).input.actual)
        assertEquals(1L, ledger.runTotal(secondRun).responses)
        assertEquals(BigInteger.valueOf(7), ledger.runTotal(secondRun).input.estimated)
        assertEquals(BigInteger.valueOf(15), ledger.modelTotal(firstModel).input.actual)
        assertEquals(BigInteger.valueOf(7), ledger.modelTotal(secondModel).input.estimated)
        assertEquals(2, ledger.recordsFor(firstRun).size)
        assertEquals(1, ledger.recordsFor(secondRun).size)
        assertEquals(3L, ledger.total.responses)
    }

    @Test
    fun checkedReceiptCounterFailsInsteadOfWrapping() {
        val total = RequestUsageTotals(responses = Long.MAX_VALUE)
        try {
            total.add(usageOf())
            throw AssertionError("Wrapped receipt counter")
        } catch (_: ArithmeticException) {
        }
    }

    @Test
    fun requestRecordsAreBoundedButTotalsAreNot() {
        val ledger = UsageLedger.create(maxRecords = 1)
            .record(record(attempt = "one", usage = usageOf(input = actual(1))))
            .record(record(attempt = "two", usage = usageOf(input = actual(2))))
        assertEquals(1, ledger.records.size)
        assertEquals("two", ledger.records.single().attemptId.value)
        assertEquals(1L, ledger.droppedRecords)
        assertEquals(2L, ledger.total.responses)
        assertEquals(BigInteger.valueOf(3), ledger.total.input.actual)

        val none = UsageLedger.create(maxRecords = 0).record(record())
        assertTrue(none.records.isEmpty())
        assertEquals(1L, none.droppedRecords)
        assertEquals(1L, none.total.responses)
    }

    @Test
    fun snapshotsNeverCarryBodyText() {
        val ledger = UsageLedger.EMPTY.record(record())
        assertFalse(ledger.toString().contains("prompt"))
        assertFalse(ledger.toString().contains("response body"))
    }
}
