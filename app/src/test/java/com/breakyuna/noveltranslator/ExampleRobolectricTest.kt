package com.breakyuna.noveltranslator

import com.breakyuna.noveltranslator.core.agent.TermExtractionAgent
import com.breakyuna.noveltranslator.data.model.TermCategory
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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

        val terms = TermExtractionAgent.parseTermsJson(fencedJson)
        assertEquals(2, terms.size)
        assertEquals("九阳神功", terms[0].originalTerm)
        assertEquals("Nine Yang Divine Skill", terms[0].translatedTerm)
        assertEquals(TermCategory.SKILL, terms[0].category)
        assertEquals("张无忌", terms[1].originalTerm)
        assertEquals(TermCategory.CHARACTER, terms[1].category)

        // Malformed provider output must be surfaced instead of masquerading as zero terms.
        val malformed = "This is not json at all."
        assertThrows(IllegalArgumentException::class.java) {
            TermExtractionAgent.parseTermsJson(malformed)
        }
    }
}
