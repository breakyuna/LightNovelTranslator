package com.breakyuna.noveltranslator.nativecore.formats.rgss

import com.breakyuna.noveltranslator.nativecore.domain.FormatAdapterRegistry
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.formats.NativeFormatAdapters
import org.junit.Assert.*
import org.junit.Test

class RgssRegistryIntegrationTest {
    @Test fun rgssArchivesScriptsAndDataFilesAreDetectedWithoutClaimingOtherData() {
        assertEquals(ProjectAssetFormat.RPGMAKER_RGSS, ProjectAssetFormat.detect("Game.rgssad"))
        assertEquals(ProjectAssetFormat.RPGMAKER_RGSS, ProjectAssetFormat.detect("Game.rgss2a"))
        assertEquals(ProjectAssetFormat.RPGMAKER_RGSS, ProjectAssetFormat.detect("Data/Scripts.rvdata2"))
        assertEquals(ProjectAssetFormat.RPGMAKER_RGSS, ProjectAssetFormat.detect("Data/Actors.rxdata"))
        assertEquals(ProjectAssetFormat.RPGMAKER_RGSS, ProjectAssetFormat.detect("Data/Map001.rvdata2"))
        assertEquals(ProjectAssetFormat.BINARY, ProjectAssetFormat.detect("Other/Actors.rxdata"))
        assertEquals(ProjectAssetFormat.BINARY, ProjectAssetFormat.detect("Other/Map001.rvdata2"))
    }

    @Test fun installedCatalogExposesTheRgssBinaryAdapterExactlyOnce() {
        assertTrue(NativeFormatAdapters.supported().contains(ProjectAssetFormat.RPGMAKER_RGSS))
        assertTrue(NativeFormatAdapters.install().contains(ProjectAssetFormat.RPGMAKER_RGSS))
        val adapter = FormatAdapterRegistry.binary(ProjectAssetFormat.RPGMAKER_RGSS)
        assertNotNull(adapter)
        assertEquals(ProjectAssetFormat.RPGMAKER_RGSS, adapter!!.format)
    }
}
