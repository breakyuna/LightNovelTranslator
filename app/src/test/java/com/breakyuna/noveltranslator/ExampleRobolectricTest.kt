package com.breakyuna.noveltranslator

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.core.agent.TermExtractionAgent
import com.breakyuna.noveltranslator.core.project.ProjectFileManager
import com.breakyuna.noveltranslator.data.model.TermCategory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Novel Translator", appName)
    }

    @Test
    fun `test ProjectFileManager sanitize filename`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileManager = ProjectFileManager(context)

        // Test normal title
        val normalName = fileManager.sanitizeChapterFileName(1, "第一章 开始旅程")
        assertEquals("0001_第一章 开始旅程.txt", normalName)

        val transName = fileManager.sanitizeChapterFileName(1, "第一章 开始旅程", isTranslated = true)
        assertEquals("0001_第一章 开始旅程_translated.txt", transName)

        // Test title with invalid OS characters
        val dirtyTitle = "Chapter 2: <The> \"Great\" *Storm* / \\ | ?"
        val cleanName = fileManager.sanitizeChapterFileName(2, dirtyTitle)
        assertFalse("Filename should not contain illegal characters", cleanName.contains(":"))
        assertFalse(cleanName.contains("<"))
        assertFalse(cleanName.contains(">"))
        assertFalse(cleanName.contains("*"))
        assertFalse(cleanName.contains("?"))
        assertFalse(cleanName.contains("|"))
        assertTrue(cleanName.startsWith("0002_"))
        assertTrue(cleanName.endsWith(".txt"))

        // Test empty title fallback
        val emptyTitleName = fileManager.sanitizeChapterFileName(5, "")
        assertEquals("chap_0005.txt", emptyTitleName)

        val emptyTransName = fileManager.sanitizeChapterFileName(5, "", isTranslated = true)
        assertEquals("trans_0005.txt", emptyTransName)
    }

    @Test
    fun `test ProjectFileManager directory and read write`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileManager = ProjectFileManager(context)
        val testProjectId = 99999L

        val savedOriginalFile = fileManager.saveOriginalChapter(testProjectId, 1, "测试原始文本内容", "第一章")
        val readOriginal = fileManager.readOriginalChapter(testProjectId, savedOriginalFile)
        assertEquals("测试原始文本内容", readOriginal)

        val savedTransFile = fileManager.saveTranslatedChapter(testProjectId, 1, "Test translated text content", "第一章")
        val readTrans = fileManager.readTranslatedChapter(testProjectId, savedTransFile)
        assertEquals("Test translated text content", readTrans)

        // Clean up
        fileManager.deleteProjectFiles(testProjectId)
        val readAfterDelete = fileManager.readOriginalChapter(testProjectId, savedOriginalFile)
        assertEquals("", readAfterDelete)
    }

    @Test
    fun `test TermExtractionAgent parseTermsJson with code fences`() {
        val fencedJson = """
            ```json
            [
              {
                "original": "九阳神功",
                "suggested": "Nine Yang Divine Skill",
                "category": "SKILL",
                "notes": "Legendary martial art"
              },
              {
                "original": "张无忌",
                "suggested": "Zhang Wuji",
                "category": "CHARACTER",
                "notes": "Protagonist"
              }
            ]
            ```
        """.trimIndent()

        val terms = TermExtractionAgent.parseTermsJson(1L, fencedJson)
        assertEquals(2, terms.size)
        assertEquals("九阳神功", terms[0].originalTerm)
        assertEquals("Nine Yang Divine Skill", terms[0].translatedTerm)
        assertEquals(TermCategory.SKILL, terms[0].category)
        assertEquals("张无忌", terms[1].originalTerm)
        assertEquals(TermCategory.CHARACTER, terms[1].category)

        // Malformed provider output must be surfaced instead of masquerading as zero terms.
        val malformed = "This is not json at all."
        assertThrows(IllegalArgumentException::class.java) {
            TermExtractionAgent.parseTermsJson(1L, malformed)
        }
    }
}
