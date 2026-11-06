package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFormatPolicyTest {
    @Test
    fun onlyTxtAndEpubAreActive() {
        assertTrue(isNativeFormatActive(ProjectAssetFormat.TXT))
        assertTrue(isNativeFormatActive(ProjectAssetFormat.EPUB))
        assertFalse(isNativeFormatActive(ProjectAssetFormat.PDF))
        assertFalse(isNativeFormatActive(ProjectAssetFormat.RPGMAKER_RGSS))
        assertFalse(isNativeFormatActive(ProjectAssetFormat.MD_V2))
    }

    @Test
    fun pathDetectionFreezesOtherExtensions() {
        assertTrue(isNativeFormatActive("book.txt"))
        assertTrue(isNativeFormatActive("book.epub"))
        assertFalse(isNativeFormatActive("book.pdf"))
        assertFalse(isNativeFormatActive("Scripts.rvdata2"))
    }
}
