package com.breakyuna.noveltranslator.core.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDevicePromptConfigTest {

    @Test
    fun `sanitized clamps temperature into 0 to 2`() {
        assertEquals(0f, OnDevicePromptConfig(temperature = -1f).sanitized().temperature!!, 0.0001f)
        assertEquals(2f, OnDevicePromptConfig(temperature = 9f).sanitized().temperature!!, 0.0001f)
        assertEquals(0.7f, OnDevicePromptConfig(temperature = 0.7f).sanitized().temperature!!, 0.0001f)
        assertNull(OnDevicePromptConfig().sanitized().temperature)
    }

    @Test
    fun `sanitized caps max output tokens well below the 4k guidance`() {
        val capped = OnDevicePromptConfig(maxOutputTokens = 100_000).sanitized()
        assertEquals(OnDevicePromptConfig.MAX_OUTPUT_TOKENS, capped.maxOutputTokens)
        assertTrue(capped.maxOutputTokens!! < OnDeviceTokenBudget.MAX_INPUT_TOKENS)
        assertEquals(64, OnDevicePromptConfig(maxOutputTokens = 64).sanitized().maxOutputTokens)
    }

    @Test
    fun `sanitized drops negative seed and clamps candidates`() {
        assertNull(OnDevicePromptConfig(seed = -1).sanitized().seed)
        assertEquals(7, OnDevicePromptConfig(seed = 7).sanitized().seed)
        assertEquals(1, OnDevicePromptConfig(candidateCount = 0).sanitized().candidateCount)
        assertEquals(
            OnDevicePromptConfig.MAX_CANDIDATES,
            OnDevicePromptConfig(candidateCount = 99).sanitized().candidateCount
        )
    }

    @Test
    fun `sanitized keeps valid topK`() {
        assertNull(OnDevicePromptConfig(topK = 0).sanitized().topK)
        assertEquals(16, OnDevicePromptConfig(topK = 16).sanitized().topK)
    }

    @Test
    fun `input limit matches official 4000 token bound`() {
        assertEquals(4000, OnDevicePromptConfig.MAX_INPUT_TOKENS)
        assertEquals(4000, OnDeviceTokenBudget.MAX_INPUT_TOKENS)
    }
}
