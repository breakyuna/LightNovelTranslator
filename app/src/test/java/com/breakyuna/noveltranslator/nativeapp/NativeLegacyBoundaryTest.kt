package com.breakyuna.noveltranslator.nativeapp

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

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
        val manifestFile = File("src/main/AndroidManifest.xml")
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifestFile)
        val android = "http://schemas.android.com/apk/res/android"
        fun Element.children(tag: String): List<Element> {
            val nodes = getElementsByTagName(tag)
            return (0 until nodes.length).map { nodes.item(it) as Element }
        }
        val launchers = manifest.documentElement.children("activity").filter { activity ->
            activity.children("intent-filter").any { filter ->
                filter.children("action").any {
                    it.getAttributeNS(android, "name") == "android.intent.action.MAIN"
                } && filter.children("category").any {
                    it.getAttributeNS(android, "name") == "android.intent.category.LAUNCHER"
                }
            }
        }
        assertEquals("Exactly one native launcher is required", 1, launchers.size)
        val name = launchers.single().getAttributeNS(android, "name")
        val qualified = if (name.startsWith(".")) "com.breakyuna.noveltranslator$name" else name
        assertEquals("com.breakyuna.noveltranslator.MainActivity", qualified)
        assertEquals("true", launchers.single().getAttributeNS(android, "exported"))
        assertFalse("legacy AppNavigation must not be a manifest entry", manifestFile.readText().contains("AppNavigation"))
    }
}
