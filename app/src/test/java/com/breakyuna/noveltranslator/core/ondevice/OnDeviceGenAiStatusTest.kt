package com.breakyuna.noveltranslator.core.ondevice

import com.google.mlkit.genai.common.FeatureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceGenAiStatusTest {

    @Test
    fun `available statuses are reflected`() {
        val status = OnDeviceGenAiStatus(
            apiLevel = 35,
            promptStatus = FeatureStatus.AVAILABLE,
            summarizationStatus = FeatureStatus.AVAILABLE,
            proofreadingStatus = FeatureStatus.AVAILABLE,
            baseModelName = "gemini-nano",
            unsupportedReason = null
        )
        assertTrue(status.apiLevelSupported)
        assertTrue(status.promptAvailable)
        assertTrue(status.summarizationAvailable)
        assertTrue(status.proofreadingAvailable)
        assertTrue(status.anyCapabilityAvailable)
        assertFalse(status.anyDownloading)
        assertFalse(status.chineseSummarizationSupported)
        assertNull(status.unsupportedReason)
    }

    @Test
    fun `api level below 26 disables everything`() {
        val status = OnDeviceGenAiStatus(
            apiLevel = 24,
            promptStatus = FeatureStatus.AVAILABLE,
            summarizationStatus = FeatureStatus.AVAILABLE,
            proofreadingStatus = FeatureStatus.AVAILABLE,
            baseModelName = null,
            unsupportedReason = "需要 Android 8.0"
        )
        assertFalse(status.apiLevelSupported)
        assertFalse(status.promptAvailable)
        assertFalse(status.summarizationAvailable)
        assertFalse(status.proofreadingAvailable)
        assertFalse(status.anyCapabilityAvailable)
    }

    @Test
    fun `downloadable counts as downloading but not available`() {
        val status = OnDeviceGenAiStatus(
            apiLevel = 36,
            promptStatus = FeatureStatus.DOWNLOADABLE,
            summarizationStatus = FeatureStatus.DOWNLOADING,
            proofreadingStatus = FeatureStatus.UNAVAILABLE,
            baseModelName = "gemini-nano",
            unsupportedReason = "x"
        )
        assertTrue(status.anyDownloading)
        assertFalse(status.anyCapabilityAvailable)
        assertFalse(status.promptAvailable)
    }

    @Test
    fun `reason is null when all available and mentions download when downloading`() {
        assertNull(
            buildUnsupportedReason(
                apiLevelSupported = true,
                promptStatus = FeatureStatus.AVAILABLE,
                summarizationStatus = FeatureStatus.AVAILABLE,
                proofreadingStatus = FeatureStatus.AVAILABLE
            )
        )
        val reason = buildUnsupportedReason(
            apiLevelSupported = true,
            promptStatus = FeatureStatus.AVAILABLE,
            summarizationStatus = FeatureStatus.DOWNLOADING,
            proofreadingStatus = FeatureStatus.UNAVAILABLE
        )
        requireNotNull(reason)
        assertTrue(reason.contains("下载中"))
        assertTrue(reason.contains("不可用"))
    }

    @Test
    fun `reason mentions api level when unsupported`() {
        val reason = buildUnsupportedReason(
            apiLevelSupported = false,
            promptStatus = FeatureStatus.UNAVAILABLE,
            summarizationStatus = FeatureStatus.UNAVAILABLE,
            proofreadingStatus = FeatureStatus.UNAVAILABLE
        )
        assertEquals(OnDeviceDeviceSupport.API_LEVEL_REASON, reason)
    }
}
