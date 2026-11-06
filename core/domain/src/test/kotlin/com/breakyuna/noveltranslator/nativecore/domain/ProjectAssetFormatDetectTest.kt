package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectAssetFormatDetectTest {
    @Test
    fun rgssDataFilesAreClaimedOnlyUnderTheDataDirectory() {
        assertEquals(ProjectAssetFormat.RPGMAKER_RGSS, ProjectAssetFormat.detect("Data/Actors.rxdata"))
        assertEquals(ProjectAssetFormat.RPGMAKER_RGSS, ProjectAssetFormat.detect("Game\\Data\\Map001.rvdata2"))
        assertEquals(ProjectAssetFormat.RPGMAKER_RGSS, ProjectAssetFormat.detect("Data/Scripts.rxdata"))
        assertEquals(ProjectAssetFormat.BINARY, ProjectAssetFormat.detect("Actors.rxdata"))
        assertEquals(ProjectAssetFormat.BINARY, ProjectAssetFormat.detect("Cache/Map001.rvdata2"))
        assertEquals(ProjectAssetFormat.BINARY, ProjectAssetFormat.detect("Data/Actors.rxdata.bak"))
    }

    @Test
    fun existingFormatDetectionsRemainUnchanged() {
        assertEquals(ProjectAssetFormat.TXT, ProjectAssetFormat.detect("book.txt"))
        assertEquals(ProjectAssetFormat.MD_V2, ProjectAssetFormat.detect("book.md"))
        assertEquals(ProjectAssetFormat.EPUB, ProjectAssetFormat.detect("book.epub"))
        assertEquals(ProjectAssetFormat.PDF, ProjectAssetFormat.detect("book.pdf"))
        assertEquals(ProjectAssetFormat.XLSX, ProjectAssetFormat.detect("book.xlsx"))
        assertEquals(ProjectAssetFormat.TRANS, ProjectAssetFormat.detect("book.trans"))
        assertEquals(ProjectAssetFormat.RPGMAKER_RGSS, ProjectAssetFormat.detect("Data.rgss3a"))
    }
}
