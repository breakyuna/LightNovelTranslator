package com.breakyuna.noveltranslator.nativecore.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityCatalogTest {
    private val ref = CapabilityModelRef(ModelProtocol.OPENAI_CHAT_COMPLETIONS, "fixture")
    private val known = ModelCapabilities(
        tools = CapabilitySupport.SUPPORTED,
        vision = CapabilitySupport.SUPPORTED,
        reasoning = CapabilitySupport.SUPPORTED,
        contextWindowTokens = 128_000,
        maxOutputTokens = 8_000,
    )
    private val catalog = CapabilityCatalog.of(ref to known)

    @Test
    fun supportedRequirementsPassAndAbsentModelsStayUnknown() {
        val requirement = CapabilityRequirement(tools = true, vision = true, reasoning = true,
            minimumContextTokens = 100_000, maximumOutputTokens = 4_000)
        assertTrue(catalog.validate(ref.protocol, ref.model, requirement) is CapabilityDecision.Supported)
        catalog.requireSupported(ref.protocol, ref.model, requirement)

        val absent = catalog.describe(ModelProtocol.ANTHROPIC_MESSAGES, "fixture")
        assertSame(ModelCapabilities.UNKNOWN, absent)
        assertEquals(CapabilitySupport.UNKNOWN, absent.tools)
    }

    @Test
    fun unsupportedAndUnknownFeaturesAreRejectedBeforeDispatch() {
        val unsupported = CapabilityCatalog.of(ref to known.copy(tools = CapabilitySupport.UNSUPPORTED))
        assertRejected(unsupported, CapabilityRequirement(tools = true),
            CapabilityFeature.TOOLS, CapabilityRejectionReason.UNSUPPORTED)

        val unknown = CapabilityCatalog.of(ref to known.copy(reasoning = CapabilitySupport.UNKNOWN))
        assertRejected(unknown, CapabilityRequirement(reasoning = true),
            CapabilityFeature.REASONING, CapabilityRejectionReason.UNKNOWN)

        assertRejected(CapabilityCatalog.EMPTY, CapabilityRequirement(vision = true),
            CapabilityFeature.VISION, CapabilityRejectionReason.UNKNOWN)
    }

    @Test
    fun contextAndOutputRequirementsUseKnownLimits() {
        assertRejected(catalog, CapabilityRequirement(minimumContextTokens = 200_000),
            CapabilityFeature.CONTEXT_WINDOW, CapabilityRejectionReason.LIMIT_TOO_SMALL)
        assertRejected(catalog, CapabilityRequirement(maximumOutputTokens = 16_000),
            CapabilityFeature.OUTPUT_TOKENS, CapabilityRejectionReason.LIMIT_TOO_SMALL)
        assertRejected(CapabilityCatalog.EMPTY, CapabilityRequirement(minimumContextTokens = 1),
            CapabilityFeature.CONTEXT_WINDOW, CapabilityRejectionReason.UNKNOWN)
        assertRejected(CapabilityCatalog.EMPTY, CapabilityRequirement(maximumOutputTokens = 1),
            CapabilityFeature.OUTPUT_TOKENS, CapabilityRejectionReason.UNKNOWN)
    }

    @Test
    fun rejectionsAreOrderedAndAllCanBeInspected() {
        val all = catalog.rejections(ref.protocol, ref.model,
            CapabilityRequirement(tools = true, minimumContextTokens = 200_000, maximumOutputTokens = 16_000))
        assertEquals(listOf(CapabilityFeature.CONTEXT_WINDOW, CapabilityFeature.OUTPUT_TOKENS),
            all.map { it.feature })
        assertRejected(catalog, CapabilityRequirement(vision = true, minimumContextTokens = 200_000),
            CapabilityFeature.CONTEXT_WINDOW, CapabilityRejectionReason.LIMIT_TOO_SMALL)
    }

    @Test
    fun sakuraToolAndVisionClaimsCannotOverrideTheTextOnlyCodec() {
        val sakura = CapabilityCatalog.of(
            CapabilityModelRef(ModelProtocol.SAKURA_LLM, "fixture") to
                ModelCapabilities(tools = CapabilitySupport.SUPPORTED, vision = CapabilitySupport.SUPPORTED),
        )
        assertRejected(sakura, CapabilityRequirement(tools = true),
            CapabilityFeature.TOOLS, CapabilityRejectionReason.UNSUPPORTED,
            protocol = ModelProtocol.SAKURA_LLM)
        assertRejected(sakura, CapabilityRequirement(vision = true),
            CapabilityFeature.VISION, CapabilityRejectionReason.UNSUPPORTED,
            protocol = ModelProtocol.SAKURA_LLM)
    }

    @Test
    fun catalogLookupIsExactAndImmutable() {
        val extended = catalog.with(ModelProtocol.ANTHROPIC_MESSAGES, "other",
            ModelCapabilities(tools = CapabilitySupport.SUPPORTED))
        assertSame(ModelCapabilities.UNKNOWN, catalog.describe(ModelProtocol.ANTHROPIC_MESSAGES, "other"))
        assertTrue(extended.describe(ModelProtocol.ANTHROPIC_MESSAGES, "other").tools == CapabilitySupport.SUPPORTED)
        assertTrue(catalog.describe(ModelProtocol.OPENAI_CHAT_COMPLETIONS, "FIXTURE") == ModelCapabilities.UNKNOWN)

        try {
            catalog.with(ref.protocol, ref.model, known)
            throw AssertionError("Accepted duplicate capability entry")
        } catch (_: IllegalArgumentException) {
        }
        try {
            CapabilityCatalog.of(ref to known, ref to known)
            throw AssertionError("Accepted duplicate capability entry")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun rejectionMessageAndInvalidDescriptorsDoNotExposeModelText() {
        val error = try {
            CapabilityCatalog.EMPTY.requireSupported(ModelProtocol.OPENAI_RESPONSES, "private-model",
                CapabilityRequirement(tools = true))
            throw AssertionError("Accepted unknown capability")
        } catch (failure: UnsupportedModelCapability) {
            failure
        }
        assertFalse(error.message.orEmpty().contains("private-model"))
        assertEquals(CapabilityFeature.TOOLS, error.feature)
        assertEquals(CapabilityRejectionReason.UNKNOWN, error.reason)
    }

    private fun assertRejected(
        catalog: CapabilityCatalog,
        requirement: CapabilityRequirement,
        feature: CapabilityFeature,
        reason: CapabilityRejectionReason,
        protocol: ModelProtocol = ref.protocol,
        model: String = ref.model,
    ) {
        val decision = catalog.validate(protocol, model, requirement)
        if (decision !is CapabilityDecision.Rejected) throw AssertionError("Accepted unsupported requirement")
        assertEquals(feature, decision.rejection.feature)
        assertEquals(reason, decision.rejection.reason)
        try {
            catalog.requireSupported(protocol, model, requirement)
            throw AssertionError("Accepted unsupported requirement")
        } catch (failure: UnsupportedModelCapability) {
            assertEquals(feature, failure.feature)
            assertEquals(reason, failure.reason)
        }
    }
}
