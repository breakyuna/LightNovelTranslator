package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.CurrencyCode
import com.breakyuna.noveltranslator.nativecore.domain.ModelCatalogSnapshot
import com.breakyuna.noveltranslator.nativecore.domain.ModelDescriptor
import com.breakyuna.noveltranslator.nativecore.domain.ModelPricing
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProtocol
import com.breakyuna.noveltranslator.nativecore.domain.PriceTable
import com.breakyuna.noveltranslator.nativecore.domain.RequestUsage
import com.breakyuna.noveltranslator.nativecore.domain.TokenMeasurement
import com.breakyuna.noveltranslator.nativecore.domain.TokenPrice
import com.breakyuna.noveltranslator.nativecore.domain.UsageSource
import com.breakyuna.noveltranslator.nativecore.engine.ModelProbeFailure
import com.breakyuna.noveltranslator.nativecore.engine.ModelProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeModelCatalogUiTest {
    private val endpoint = "https://example.test/v1"

    private fun snapshot(fetchedAt: Long = 1_000L, descriptors: List<ModelDescriptor> =
        listOf(ModelDescriptor("model-a", contextTokens = 128_000, outputTokens = 8_192))) =
        ModelCatalogSnapshot(endpoint, NativeModelProtocol.OPENAI_CHAT_COMPLETIONS, fetchedAt, descriptors)

    @Test fun cachedSnapshotMapsToCachedSourceWithOnlyPublishedLimits() {
        val state = nativeModelCatalogState("profile-1", snapshot(), live = false)
        assertEquals("profile-1", state.profileId)
        assertEquals(NativeModelCatalogViewSource.CACHED, state.source)
        assertEquals(1_000L, state.fetchedAtMillis)
        assertEquals(1, state.descriptors.size)
        assertEquals(128_000, state.descriptors.single().contextTokens)
        assertEquals(8_192, state.descriptors.single().outputTokens)
        // A descriptor without limits stays null rather than borrowing context from the protocol.
        val unknown = snapshot(descriptors = listOf(ModelDescriptor("model-b")))
        val unknownState = nativeModelCatalogState("profile-1", unknown, live = false)
        assertNull(unknownState.descriptors.single().contextTokens)
        assertNull(unknownState.descriptors.single().outputTokens)
    }

    @Test fun successfulEmptyResultIsAnEmptyOverrideNotEmptyCache() {
        val state = nativeModelCatalogState("profile-1", snapshot(descriptors = emptyList()), live = true)
        assertTrue(state.descriptors.isEmpty())
        assertEquals(NativeModelCatalogViewSource.EMPTY_OVERRIDE, state.source)
        assertEquals(1_000L, state.fetchedAtMillis)
        assertTrue(state.persisted)
        assertNull(nativeModelCatalogState("profile-1", null, live = false).source)
        // The read-only capability probe shows a live empty result without claiming an override.
        val probe = nativeModelCatalogState("profile-1", snapshot(descriptors = emptyList()),
            live = true, persisted = false)
        assertEquals(NativeModelCatalogViewSource.LIVE, probe.source)
        assertFalse(probe.persisted)
    }

    @Test fun refreshFailureKeepsLastSuccessfulCatalogAndTimestamp() {
        val previous = nativeModelCatalogState("profile-1", snapshot(), live = true)
        val failed = nativeModelCatalogFailure(previous, "profile-1",
            R.string.native_model_catalog_cache_error)
        assertEquals(previous.descriptors, failed.descriptors)
        assertEquals(NativeModelCatalogViewSource.LIVE, failed.source)
        assertEquals(previous.fetchedAtMillis, failed.fetchedAtMillis)
        assertEquals(R.string.native_model_catalog_cache_error, failed.error)
        assertFalse(failed.loading)
        // A late failure for another profile must not replace the current selection.
        val stale = nativeModelCatalogFailure(previous, "profile-2",
            R.string.native_model_catalog_cache_error)
        assertEquals(previous, stale)
    }

    @Test fun switchingProfileClearsTransientCatalogAndProbeState() {
        val previous = nativeModelCatalogState("profile-1", snapshot(), live = true).copy(
            metadataProbe = NativeModelProbeUi.Metadata(1, 3),
            generationProbe = NativeModelProbeUi.Generation(4, false, 5, null),
            generationConfirmation = true,
        )
        val loading = nativeModelCatalogLoading("profile-2", previous)
        assertEquals("profile-2", loading.profileId)
        assertTrue(loading.loading)
        assertTrue(loading.descriptors.isEmpty())
        assertNull(loading.metadataProbe)
        assertNull(loading.generationProbe)
        assertFalse(loading.generationConfirmation)
    }

    @Test fun generationNeedsConfirmationAndNeverRunsTwiceWhileBusy() {
        val base = NativeModelCatalogUiState(profileId = "profile-1",
            source = NativeModelCatalogViewSource.CACHED)
        assertEquals(NativeModelProbeCommand.SHOW_CONFIRMATION,
            nativeModelProbeCommand(base, "profile-1", generation = true, confirmed = false))
        assertEquals(NativeModelProbeCommand.IGNORE_CONFIRMATION,
            nativeModelProbeCommand(base, "profile-1", generation = true, confirmed = true))
        val confirming = base.copy(generationConfirmation = true)
        assertEquals(NativeModelProbeCommand.IGNORE_BUSY,
            nativeModelProbeCommand(confirming, "profile-1", generation = true, confirmed = false))
        assertEquals(NativeModelProbeCommand.RUN_GENERATION,
            nativeModelProbeCommand(confirming, "profile-1", generation = true, confirmed = true))
        assertEquals(NativeModelProbeCommand.IGNORE_BUSY,
            nativeModelProbeCommand(base.copy(generationRunning = true),
                "profile-1", generation = true, confirmed = true))
        assertEquals(NativeModelProbeCommand.IGNORE_BUSY,
            nativeModelProbeCommand(base.copy(loading = true),
                "profile-1", generation = false, confirmed = false))
        assertEquals(NativeModelProbeCommand.IGNORE_STALE,
            nativeModelProbeCommand(base, "profile-2", generation = false, confirmed = false))
        assertEquals(NativeModelProbeCommand.RUN_METADATA,
            nativeModelProbeCommand(base, "profile-1", generation = false, confirmed = false))
    }

    @Test fun billingHintUsesOnlyAnExplicitPriceAndOtherwiseReportsUnknown() {
        val table = PriceTable(CurrencyCode.USD, 1, listOf(ModelPricing(
            input = TokenPrice.ofMicrosPerMillion(1_000_000),
            output = TokenPrice.ofMicrosPerMillion(2_000_000),
            cacheRead = TokenPrice.ZERO,
            cacheWrite = TokenPrice.ZERO,
            modelKey = "model-a",
        )))
        val known = nativeProbeBillingHint(table, "model-a")
        assertTrue(known.priced)
        assertEquals("USD", known.currency)
        assertEquals("2", known.outputPricePerMillion)
        val missing = nativeProbeBillingHint(table, "model-b")
        assertFalse(missing.priced)
        assertNull(missing.currency)
        assertNull(missing.outputPricePerMillion)
        assertFalse(nativeProbeBillingHint(null, "model-a").priced)
    }

    @Test fun probeResultsMapToTransientUiWithoutRetainingPreviewText() {
        val usage = RequestUsage(
            input = TokenMeasurement(12, UsageSource.ACTUAL),
            cacheRead = TokenMeasurement(null, UsageSource.UNKNOWN),
            cacheWrite = TokenMeasurement(null, UsageSource.UNKNOWN),
            thinking = TokenMeasurement(null, UsageSource.UNKNOWN),
            output = TokenMeasurement(3, UsageSource.ESTIMATED),
        )
        val success = nativeProbeUi(ModelProbeResult.GenerationSuccess(
            preview = "secret-preview", truncated = true, elapsedMillis = 7, usage = usage))
        assertTrue(success is NativeModelProbeUi.Generation)
        success as NativeModelProbeUi.Generation
        assertEquals("secret-preview".length, success.outputChars)
        assertTrue(success.truncated)
        assertEquals(12L, success.usage?.input?.tokens)
        assertEquals(3L, success.usage?.output?.tokens)
        assertFalse(success.toString().contains("secret-preview"))

        val metadata = nativeProbeUi(ModelProbeResult.MetadataSuccess(snapshot(), 4))
        assertEquals(NativeModelProbeUi.Metadata(1, 4), metadata)
        val failure = nativeProbeUi(ModelProbeResult.Failure(ModelProbeFailure.RATE_LIMIT, 5_000))
        assertEquals(NativeModelProbeUi.Failure(ModelProbeFailure.RATE_LIMIT, 5_000), failure)
        assertEquals(R.string.native_model_probe_timeout,
            nativeProbeFailureLabel(ModelProbeFailure.TIMEOUT))
    }
}
