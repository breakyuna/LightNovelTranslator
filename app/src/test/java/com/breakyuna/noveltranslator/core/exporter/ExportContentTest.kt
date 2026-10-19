package com.breakyuna.noveltranslator.core.exporter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportContentTest {

    private fun chapter(vararg segments: ExportSegment) =
        ExportChapterData(index = 0, title = "第一章", segments = segments.toList())

    private fun segment(original: String?, translation: String?) = ExportSegment(original, translation)

    // ---------- 双语交错顺序 ----------

    @Test
    fun bilingualPairsPutTranslationFirstByDefault() {
        val blocks = ExportContent.chapterBlocks(
            chapter(
                segment("原文一", "译文一"),
                segment("原文二", "译文二")
            ),
            ExportOptions(bilingual = true)
        )
        assertEquals(
            listOf("译文一", "原文一", "译文二", "原文二"),
            blocks.map { it.text }
        )
        assertEquals(listOf(false, true, false, true), blocks.map { it.isOriginal })
    }

    @Test
    fun bilingualPairsPutOriginalFirstWhenRequested() {
        val blocks = ExportContent.chapterBlocks(
            chapter(segment("原文", "译文")),
            ExportOptions(bilingual = true, translationFirst = false)
        )
        assertEquals(listOf("原文", "译文"), blocks.map { it.text })
        assertEquals(listOf(true, false), blocks.map { it.isOriginal })
    }

    // ---------- dedup ----------

    @Test
    fun identicalTranslationCollapsesToOneBlockWhenDeduplicationIsOn() {
        val blocks = ExportContent.chapterBlocks(
            chapter(segment("同一段文本", "同一段文本")),
            ExportOptions(bilingual = true, deduplicateIdentical = true)
        )
        assertEquals(listOf("同一段文本"), blocks.map { it.text })
        assertFalse(blocks.single().isOriginal)
    }

    @Test
    fun identicalTranslationStillEmitsBothBlocksWhenDeduplicationIsOff() {
        val blocks = ExportContent.chapterBlocks(
            chapter(segment("同一段文本", "同一段文本")),
            ExportOptions(bilingual = true, deduplicateIdentical = false)
        )
        assertEquals(listOf("同一段文本", "同一段文本"), blocks.map { it.text })
    }

    // ---------- 回退与空段 ----------

    @Test
    fun missingTranslationFallsBackToOriginalWithoutOriginalMarker() {
        val blocks = ExportContent.chapterBlocks(
            chapter(
                segment("原文", null),
                segment("原文", "   "),
                segment("原文", "")
            ),
            ExportOptions(bilingual = true)
        )
        assertEquals(listOf("原文", "原文", "原文"), blocks.map { it.text })
        assertTrue(blocks.none { it.isOriginal })
    }

    @Test
    fun translationOnlySegmentEmitsJustTheTranslation() {
        val blocks = ExportContent.chapterBlocks(
            chapter(segment(null, "只有译文")),
            ExportOptions(bilingual = true)
        )
        assertEquals(listOf("只有译文"), blocks.map { it.text })
    }

    @Test
    fun emptySegmentsProduceNoBlocksInEitherMode() {
        val segments = listOf(
            segment(null, null),
            segment("   ", ""),
            segment("", "  "),
            segment("有原文", "有译文")
        )
        for (bilingual in listOf(true, false)) {
            val blocks = ExportContent.chapterBlocks(chapter(*segments.toTypedArray()), ExportOptions(bilingual = bilingual))
            assertTrue(blocks.isNotEmpty())
            assertTrue(blocks.all { it.text.isNotBlank() })
        }
    }

    @Test
    fun singleLanguageModeUsesTranslationThenOriginalLikeTheLegacyExporter() {
        val blocks = ExportContent.chapterBlocks(
            chapter(
                segment("原文一", "译文一"),
                segment("原文二", null),
                segment(null, "译文三"),
                segment("空段", "   ")
            ),
            ExportOptions()
        )
        assertEquals(listOf("译文一", "原文二", "译文三"), blocks.map { it.text })
        assertTrue(blocks.none { it.isOriginal })
    }

    // ---------- 段落切分 ----------

    @Test
    fun paragraphsSplitOnBlankLinesAndAreTrimmed() {
        assertEquals(
            listOf("第一段", "第二段", "第三段"),
            ExportContent.splitParagraphs("\n  第一段  \n\n \n 第二段\n\n\n第三段\n")
        )
        assertTrue(ExportContent.splitParagraphs("   \n\n  ").isEmpty())
        assertTrue(ExportContent.splitParagraphs("").isEmpty())
    }

    // ---------- 图片标记 ----------

    @Test
    fun imageMarkerNamesAreNormalizedToBareFileNames() {
        assertEquals(
            listOf("a.png", "b.jpg"),
            ExportContent.imageMarkerNames("前文 [IMG:a.png] 中 [IMG:../b.jpg] 后")
        )
        assertTrue(ExportContent.imageMarkerNames("没有标记").isEmpty())
    }

    // ---------- 预检统计 ----------

    private fun precheckFixture(): Triple<List<ExportChapterData>, Set<String>, ExportPrecheckReport> {
        val chapters = listOf(
            ExportChapterData(
                index = 0,
                title = "第一章",
                segments = listOf(
                    segment("你好", "你好"),            // 已翻译（译文与原文相同）
                    segment("世界", null),              // 回退
                    segment(null, "独立译文")           // 已翻译
                )
            ),
            ExportChapterData(
                index = 1,
                title = "第二章",
                segments = listOf(
                    segment("", ""),                    // 空段
                    segment("[IMG:a.png]图", "[IMG:a.png]插图"),
                    segment("[IMG:missing.png]图", null) // 回退 + 缺失图片
                )
            )
        )
        val report = ExportContent.precheck(chapters, setOf("a.png"))
        return Triple(chapters, setOf("a.png"), report)
    }

    @Test
    fun precheckCountsChaptersSegmentsFallbacksAndImages() {
        val (_, _, report) = precheckFixture()
        assertEquals(2, report.totalChapters)
        assertEquals(5, report.totalSegments)
        assertEquals(1, report.fallbackSegments)
        assertEquals(1, report.emptySegments)
        assertEquals(2, report.imageMarkers)
        assertEquals(1, report.missingImageMarkers)
        assertEquals(
            "你好".length + "世界".length + "独立译文".length +
                "[IMG:a.png]插图".length + "[IMG:missing.png]图".length,
            report.estimatedCharacters
        )
        assertEquals(1.0 / 5.0, report.missingTranslationRatio, 1e-9)
        assertFalse(report.hasBlockingIssues)
        assertTrue(report.hasWarnings)
    }

    @Test
    fun precheckSummaryMentionsCountsFallbackAndMissingImages() {
        val (_, _, report) = precheckFixture()
        val summary = report.summary()
        assertTrue(summary.contains("共 2 章 / 5 段"))
        assertTrue(summary.contains("译文缺失 1 段（20%）"))
        assertTrue(summary.contains("1 处图片标记"))
        assertTrue(summary.contains("空段落 1 段"))
    }

    @Test
    fun precheckBlocksWhenEverythingFallsBackToTheOriginal() {
        val chapters = listOf(
            ExportChapterData(0, "第一章", listOf(segment("甲", null), segment("乙", null)))
        )
        val report = ExportContent.precheck(chapters, emptySet())
        assertEquals(2, report.fallbackSegments)
        assertEquals(1.0, report.missingTranslationRatio, 1e-9)
        assertTrue(report.hasBlockingIssues)
        assertTrue(report.summary().contains("阻断"))
    }

    @Test
    fun precheckBlocksWhenNoSegmentHasAnyText() {
        val chapters = listOf(
            ExportChapterData(0, "第一章", listOf(segment(null, null), segment("  ", ""))),
            ExportChapterData(1, "第二章", emptyList())
        )
        val report = ExportContent.precheck(chapters, emptySet())
        assertEquals(0, report.totalChapters)
        assertEquals(2, report.totalSegments)
        assertEquals(2, report.emptySegments)
        assertTrue(report.hasBlockingIssues)
    }

    @Test
    fun precheckOfEmptyBookBlocksWithNoUsableContentMessage() {
        val report = ExportContent.precheck(emptyList(), emptySet())
        assertTrue(report.hasBlockingIssues)
        assertTrue(report.summary().contains("没有可导出的内容"))
    }

    @Test
    fun cleanTranslationPassesPrecheckWithoutWarnings() {
        val chapters = listOf(
            ExportChapterData(0, "第一章", listOf(segment("原文", "译文")))
        )
        val report = ExportContent.precheck(chapters, emptySet())
        assertFalse(report.hasBlockingIssues)
        assertFalse(report.hasWarnings)
        assertTrue(report.summary().contains("未发现阻断问题"))
    }

    // ---------- 名称安全化 / XML 转义 ----------

    @Test
    fun safeFileNameStripsPathAndHostileCharacters() {
        assertEquals("a_b", ExportContent.safeFileName("a:b"))
        assertEquals("a_b", ExportContent.safeFileName("a*b"))
        assertEquals("a_b", ExportContent.safeFileName("a?b"))
        assertEquals("a_b", ExportContent.safeFileName("a\"b"))
        assertEquals("a_b", ExportContent.safeFileName("a<b"))
        assertEquals("a_b", ExportContent.safeFileName("a>b"))
        assertEquals("a_b", ExportContent.safeFileName("a|b"))
        assertEquals("a_b", ExportContent.safeFileName("a/b"))
        assertEquals("a_b", ExportContent.safeFileName("a\\b"))
        assertEquals("a_b", ExportContent.safeFileName("a\r\n\tb"))
        assertEquals("edition", ExportContent.safeFileName(""))
        assertEquals("___", ExportContent.safeFileName("///"))
        assertEquals(70, ExportContent.safeFileName("x".repeat(200)).length)
        assertEquals("书名", ExportContent.safeFileName("书名"))
    }

    @Test
    fun escapeXmlEscapesMarkupCharactersAndDropsForbiddenControls() {
        assertEquals("&lt;p&gt;&amp;&quot;&apos;", ExportContent.escapeXml("<p>&\"'"))
        assertEquals("ab", ExportContent.escapeXml("a\u0000b"))
        assertEquals("ab", ExportContent.escapeXml("a\u007Fb"))
        assertEquals("a\tb", ExportContent.escapeXml("a\tb"))
        assertEquals("轻小说", ExportContent.escapeXml("轻小说"))
    }

    @Test
    fun archiveNameWhitelistRejectsTraversalAndUnsafeNames() {
        assertTrue(ExportContent.isSafeArchiveName("cover.png"))
        assertTrue(ExportContent.isSafeArchiveName("a-b_c.9.jpeg"))
        assertFalse(ExportContent.isSafeArchiveName("../cover.png"))
        assertFalse(ExportContent.isSafeArchiveName("/etc/passwd"))
        assertFalse(ExportContent.isSafeArchiveName(".."))
        assertFalse(ExportContent.isSafeArchiveName("."))
        assertFalse(ExportContent.isSafeArchiveName(""))
        assertFalse(ExportContent.isSafeArchiveName("my cover.png"))
        assertFalse(ExportContent.isSafeArchiveName("x".repeat(101)))
    }

    @Test
    fun mimeTypeAndLanguageCodeFollowTheOriginalMapping() {
        assertEquals("image/png", ExportContent.mimeTypeFor("png"))
        assertEquals("image/gif", ExportContent.mimeTypeFor("GIF"))
        assertEquals("image/webp", ExportContent.mimeTypeFor("webp"))
        assertEquals("image/svg+xml", ExportContent.mimeTypeFor("svg"))
        assertEquals("image/jpeg", ExportContent.mimeTypeFor("jpg"))
        assertEquals("image/jpeg", ExportContent.mimeTypeFor(""))
        assertEquals("zh", ExportContent.languageCode("zh-CN"))
        assertEquals("zh", ExportContent.languageCode("Chinese"))
        assertEquals("ja", ExportContent.languageCode("ja"))
        assertEquals("ko", ExportContent.languageCode("ko"))
        assertEquals("en", ExportContent.languageCode("fr"))
    }
}
