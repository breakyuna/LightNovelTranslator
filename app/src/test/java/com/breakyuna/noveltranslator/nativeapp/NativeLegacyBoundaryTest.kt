package com.breakyuna.noveltranslator.nativeapp

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native entry is the only production UI. Legacy packages stay in the app module so
 * existing user data can still be exported, but a new native screen must not import the old
 * bookshelf, reader, database or translation runtime again.
 */
class NativeLegacyBoundaryTest {
    @Test
    fun nativeEntryDoesNotImportLegacyMainChain() {
        val root = File("src/main/java/com/breakyuna/noveltranslator/nativeapp")
        assertTrue("native entry source directory is missing: ${root.absolutePath}", root.isDirectory)
        val forbidden = listOf(
            "com.breakyuna.noveltranslator.ui.",
            "com.breakyuna.noveltranslator.data.",
            "com.breakyuna.noveltranslator.core.",
        )
        val offenders = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.useLines { lines ->
                lines.filter { it.startsWith("import ") }.forEach { line ->
                    val imported = line.removePrefix("import ").trim()
                    if (forbidden.any { imported.startsWith(it) }) {
                        offenders += "${file.relativeTo(root).invariantSeparatorsPath}: $imported"
                    }
                }
            }
        }
        assertTrue(
            "native entry imports a retired main-chain package:\n${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun manifestKeepsTheNativeEntryAsLauncher() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".MainActivity\""))
        assertFalse("legacy AppNavigation must not be a manifest entry", manifest.contains("AppNavigation"))
    }
}
