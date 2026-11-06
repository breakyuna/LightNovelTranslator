package com.breakyuna.noveltranslator.core.ondevice

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertFalse

class OnDeviceErrorsTest {

    private fun genAiException(code: Int): GenAiException =
        GenAiException("test", IllegalStateException("boom"), code)

    @Test
    fun `quota errors map to QUOTA_BUSY`() {
        assertEquals(
            OnDeviceAssistError.QUOTA_BUSY,
            classifyGenAiError(GenAiException.ErrorCode.BUSY)
        )
        assertEquals(
            OnDeviceAssistError.QUOTA_BUSY,
            classifyGenAiError(GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED)
        )
    }

    @Test
    fun `background use maps to BACKGROUND_BLOCKED`() {
        assertEquals(
            OnDeviceAssistError.BACKGROUND_BLOCKED,
            classifyGenAiError(GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED)
        )
    }

    @Test
    fun `device level errors map to UNSUPPORTED_DEVICE`() {
        for (code in listOf(
            GenAiException.ErrorCode.NOT_AVAILABLE,
            GenAiException.ErrorCode.NOT_SUPPORTED,
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE
        )) {
            assertEquals(
                "code=$code",
                OnDeviceAssistError.UNSUPPORTED_DEVICE,
                classifyGenAiError(code)
            )
        }
    }

    @Test
    fun `unknown and request size errors map to FAILED`() {
        assertEquals(OnDeviceAssistError.FAILED, classifyGenAiError(GenAiException.ErrorCode.UNKNOWN))
        assertEquals(OnDeviceAssistError.FAILED, classifyGenAiError(GenAiException.ErrorCode.REQUEST_TOO_LARGE))
        assertEquals(OnDeviceAssistError.FAILED, classifyGenAiError(GenAiException.ErrorCode.REQUEST_TOO_SMALL))
    }

    @Test
    fun `classify delegates GenAiException and defaults others to FAILED`() {
        assertEquals(
            OnDeviceAssistError.QUOTA_BUSY,
            classifyOnDeviceFailure(genAiException(GenAiException.ErrorCode.BUSY))
        )
        assertEquals(
            OnDeviceAssistError.FAILED,
            classifyOnDeviceFailure(IllegalStateException("plain"))
        )
    }

    @Test
    fun `feature status mapping`() {
        assertNull(featureStatusToError(FeatureStatus.AVAILABLE))
        assertEquals(OnDeviceAssistError.DOWNLOADING, featureStatusToError(FeatureStatus.DOWNLOADABLE))
        assertEquals(OnDeviceAssistError.DOWNLOADING, featureStatusToError(FeatureStatus.DOWNLOADING))
        assertEquals(OnDeviceAssistError.UNAVAILABLE, featureStatusToError(FeatureStatus.UNAVAILABLE))
    }

    @Test
    fun `only quota and downloading are retryable`() {
        assertTrue(OnDeviceAssistError.QUOTA_BUSY.isRetryable)
        assertTrue(OnDeviceAssistError.DOWNLOADING.isRetryable)
        assertFalse(OnDeviceAssistError.UNSUPPORTED_DEVICE.isRetryable)
        assertFalse(OnDeviceAssistError.BACKGROUND_BLOCKED.isRetryable)
        assertFalse(OnDeviceAssistError.UNAVAILABLE.isRetryable)
        assertFalse(OnDeviceAssistError.FAILED.isRetryable)
    }

    @Test
    fun `backoff is 1s 2s 4s 8s capped`() {
        assertEquals(0L, OnDeviceBackoff.delayForAttempt(0))
        assertEquals(0L, OnDeviceBackoff.delayForAttempt(-3))
        assertEquals(1_000L, OnDeviceBackoff.delayForAttempt(1))
        assertEquals(2_000L, OnDeviceBackoff.delayForAttempt(2))
        assertEquals(4_000L, OnDeviceBackoff.delayForAttempt(3))
        assertEquals(8_000L, OnDeviceBackoff.delayForAttempt(4))
        assertEquals(8_000L, OnDeviceBackoff.delayForAttempt(5))
        assertEquals(8_000L, OnDeviceBackoff.delayForAttempt(100))
    }

    @Test
    fun `retry succeeds after quota errors with backoff`() = runTest {
        var attempts = 0
        val result = withOnDeviceRetry(maxAttempts = 4) {
            attempts++
            if (attempts < 3) throw genAiException(GenAiException.ErrorCode.BUSY)
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `retry gives up after max attempts`() = runTest {
        var attempts = 0
        val thrown = runCatching {
            withOnDeviceRetry(maxAttempts = 3) {
                attempts++
                throw genAiException(GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED)
            }
        }.exceptionOrNull()
        assertTrue(thrown is GenAiException)
        assertEquals(3, attempts)
    }

    @Test
    fun `non retryable error fails immediately`() = runTest {
        var attempts = 0
        val thrown = runCatching {
            withOnDeviceRetry(maxAttempts = 4) {
                attempts++
                throw genAiException(GenAiException.ErrorCode.NOT_AVAILABLE)
            }
        }.exceptionOrNull()
        assertTrue(thrown is GenAiException)
        assertEquals(1, attempts)
    }

    @Test
    fun `cancellation is rethrown without retry`() = runTest {
        var attempts = 0
        val thrown = runCatching {
            withOnDeviceRetry(maxAttempts = 4) {
                attempts++
                throw kotlin.coroutines.cancellation.CancellationException("cancelled")
            }
        }.exceptionOrNull()
        assertTrue(thrown is java.util.concurrent.CancellationException)
        assertEquals(1, attempts)
    }
}
