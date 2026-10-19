package com.breakyuna.noveltranslator.core.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceLanguageSupportTest {

    @Test
    fun `normalize handles region suffixes and case`() {
        assertEquals("zh", OnDeviceLanguageSupport.normalizeLanguage("zh-CN"))
        assertEquals("zh", OnDeviceLanguageSupport.normalizeLanguage("ZH_TW"))
        assertEquals("en", OnDeviceLanguageSupport.normalizeLanguage("EN"))
        assertEquals("", OnDeviceLanguageSupport.normalizeLanguage(null))
        assertEquals("", OnDeviceLanguageSupport.normalizeLanguage("  "))
    }

    @Test
    fun `chinese detection`() {
        assertTrue(OnDeviceLanguageSupport.isChinese("zh"))
        assertTrue(OnDeviceLanguageSupport.isChinese("zh-CN"))
        assertTrue(OnDeviceLanguageSupport.isChinese("ZH-TW"))
        assertTrue(OnDeviceLanguageSupport.isChinese("chinese"))
        assertFalse(OnDeviceLanguageSupport.isChinese("ja"))
        assertFalse(OnDeviceLanguageSupport.isChinese("en"))
        assertFalse(OnDeviceLanguageSupport.isChinese(null))
    }

    @Test
    fun `summary language whitelist excludes chinese`() {
        assertEquals(OnDeviceSummaryLanguage.ENGLISH, OnDeviceLanguageSupport.summaryLanguage("en"))
        assertEquals(OnDeviceSummaryLanguage.ENGLISH, OnDeviceLanguageSupport.summaryLanguage("en-US"))
        assertEquals(OnDeviceSummaryLanguage.JAPANESE, OnDeviceLanguageSupport.summaryLanguage("ja"))
        assertEquals(OnDeviceSummaryLanguage.KOREAN, OnDeviceLanguageSupport.summaryLanguage("ko"))
        assertNull(OnDeviceLanguageSupport.summaryLanguage("zh"))
        assertNull(OnDeviceLanguageSupport.summaryLanguage("fr"))
        assertFalse("zh" in OnDeviceLanguageSupport.SUMMARY_LANGUAGE_CODES)
    }

    @Test
    fun `proofread language whitelist excludes chinese`() {
        assertEquals(OnDeviceProofreadLanguage.ENGLISH, OnDeviceLanguageSupport.proofreadLanguage("en"))
        assertEquals(OnDeviceProofreadLanguage.JAPANESE, OnDeviceLanguageSupport.proofreadLanguage("ja"))
        assertEquals(OnDeviceProofreadLanguage.GERMAN, OnDeviceLanguageSupport.proofreadLanguage("de"))
        assertEquals(OnDeviceProofreadLanguage.FRENCH, OnDeviceLanguageSupport.proofreadLanguage("fr"))
        assertEquals(OnDeviceProofreadLanguage.ITALIAN, OnDeviceLanguageSupport.proofreadLanguage("it"))
        assertEquals(OnDeviceProofreadLanguage.SPANISH, OnDeviceLanguageSupport.proofreadLanguage("es"))
        assertEquals(OnDeviceProofreadLanguage.KOREAN, OnDeviceLanguageSupport.proofreadLanguage("ko"))
        assertNull(OnDeviceLanguageSupport.proofreadLanguage("zh"))
        assertNull(OnDeviceLanguageSupport.proofreadLanguage("ru"))
        assertFalse("zh" in OnDeviceLanguageSupport.PROOFREAD_LANGUAGE_CODES)
    }

    @Test
    fun `fallback advices are non empty`() {
        assertTrue(OnDeviceLanguageSupport.CHINESE_SUMMARY_FALLBACK_ADVICE.isNotBlank())
        assertTrue(OnDeviceLanguageSupport.CHINESE_PROOFREAD_FALLBACK_ADVICE.isNotBlank())
    }
}
