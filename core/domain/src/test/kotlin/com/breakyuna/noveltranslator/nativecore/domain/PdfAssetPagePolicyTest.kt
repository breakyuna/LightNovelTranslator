package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class PdfAssetPagePolicyTest {
    private val sha = "a".repeat(64)
    private val asset = ProjectAssetSummary(2, RelativePath("book.pdf"), ProjectAssetFormat.PDF, 1, true, 10, sha, 4)
    private val policy = PdfAssetPagePolicy(2, sha, "kot-")
    private fun item(page: Int, imported: String = "translate") = TextProjectItem(ItemId(page.toLong()), page,
        "source", "", TextItemStatus.NONE, ObjectFingerprint("b".repeat(64)), assetId = 2,
        metadata = TextItemMetadata(tag = "page:$page", extraField =
            """{"v":1,"page":$page,"decision":"$imported","digest":"$sha"}"""))

    @Test fun oldSettingsKeepExactCanonicalEncodingAndFingerprint() {
        val old = """{"format":"lnt-project-settings","version":1,"target_language":"","source_language":"auto","bilingual_deduplicate_same":true,"write_translated_names":false,"prompt_enabled":false}"""
        assertEquals(old, ProjectSettingsCodec.encode(ProjectSettingsCodec.decode(old)))
        assertEquals(ProjectSettings().fingerprint(), ProjectSettingsCodec.decode(old).fingerprint())
    }

    @Test fun newSettingsRoundTripWithoutLosingLanguagesAndBindEveryPage() {
        val settings = ProjectSettings("zh", "ja", false, true, true, listOf(policy))
        val restored = ProjectSettingsCodec.decode(ProjectSettingsCodec.encode(settings))
        assertEquals(settings.fingerprint(), restored.fingerprint())
        assertEquals("zh", restored.targetLanguage)
        assertTrue(restored.promptEnabled)
        val filter = ProjectPageTranslationPolicy(restored, listOf(asset))
        assertFalse(filter.accepts(item(1)))
        assertFalse(filter.accepts(item(2)))
        assertTrue(filter.accepts(item(3)))
        assertFalse(filter.accepts(item(4, "keep")))
        assertTrue(filter.accepts(item(4)))
        assertFalse(ProjectPageTranslationPolicy(ProjectSettings(), listOf(asset)).accepts(item(1, "omit")))
    }

    @Test fun staleAssetBindingsAndMalformedPageIdentitiesAreRejected() {
        val settings = ProjectSettings(pdfPages = listOf(policy))
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPageTranslationPolicy(settings, listOf(asset.copy(sha256 = "b".repeat(64))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPageTranslationPolicy(settings, listOf(asset.copy(itemCount = 3)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProjectPageTranslationPolicy(settings, listOf(asset)).accepts(item(5))
        }
        assertThrows(IllegalArgumentException::class.java) { ProjectSettings(pdfPages = listOf(policy, policy)) }
        val encoded = ProjectSettingsCodec.encode(settings)
        for (value in listOf("2.0", "2e0", "\"2\"", "true")) {
            assertThrows(IllegalArgumentException::class.java) {
                ProjectSettingsCodec.decode(encoded.replace("\"asset\":2", "\"asset\":$value"))
            }
        }
    }
}
