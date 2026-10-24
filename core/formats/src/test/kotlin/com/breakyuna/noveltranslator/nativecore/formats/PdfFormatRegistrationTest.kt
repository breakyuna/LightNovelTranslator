package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatAdapterRegistry
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfFormatRegistrationTest {
    @Test fun pdfIsRegisteredAsABinaryFormat() {
        val supported = NativeFormatAdapters.install()
        assertTrue(supported.contains(ProjectAssetFormat.PDF))
        assertTrue(NativeFormatAdapters.supported().contains(ProjectAssetFormat.PDF))
        assertEquals(ProjectAssetFormat.PDF, FormatAdapterRegistry.binary(ProjectAssetFormat.PDF)?.format)
    }
}
