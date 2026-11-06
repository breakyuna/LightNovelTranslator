package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelativePathTest {
    @Test
    fun preservesUnicodeNamesAndNestedAssetIdentity() {
        val path = "原稿/第一卷/本文.epub"
        assertEquals(path, RelativePath(path).value)
    }

    @Test
    fun rejectsTraversalAbsoluteAndAmbiguousPaths() {
        for (path in listOf("", ".", "..", "a/../b", "a/./b", "/a", "//server/a", "C:/a", "C:a",
                            "a\\b", "a//b", "a/", "a\u0000b", "a\nb")) {
            assertThrows(IllegalArgumentException::class.java) { RelativePath(path) }
        }
    }
}
