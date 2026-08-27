package com.breakyuna.noveltranslator.core.llm

import com.breakyuna.noveltranslator.data.model.GlossaryEntity

object TranslationPrompts {

    fun buildSystemPrompt(
        sourceLanguage: String,
        targetLanguage: String,
        style: String = "Literary Novel"
    ): String {
        return """
You are a master literary translator specializing in web novels, light novels, fantasy, and fiction.
Your task is to translate the novel from $sourceLanguage to $targetLanguage with high fidelity to the author's voice while maintaining natural, fluent, and captivating prose in $targetLanguage.

Strict Translation Rules:
1. Style & Tone: Use a "$style" style. Match the genre, mood, dialogue tone, pacing, and nuances of the original text.
2. Dialogue & Voice: Make character dialogues match their distinct personalities, social status, and emotions.
3. Terminology Consistency: Strictly adhere to the provided Glossary and Terminology Table.
4. Completeness: Translate every single paragraph and sentence faithfully. Never skip, truncate, summarize, or omit anything.
5. Formatting: Retain the original paragraph breaks and dialogue punctuation conventions.
6. Output Format: Output ONLY the translated novel content. Do NOT include greetings, preamble, explanations, notes, or markdown fences unless the original text contains them.
7. Illustrations: Preserve every [IMG:filename] marker byte-for-byte and in the same order and paragraph position.
        """.trimIndent()
    }

    fun buildUserPrompt(
        chapterTitle: String,
        chapterText: String,
        glossary: List<GlossaryEntity>,
        previousContextSummary: String? = null
    ): String {
        val sb = StringBuilder()

        if (!previousContextSummary.isNullOrBlank()) {
            sb.append("=== PREVIOUS STORY CONTEXT & SUMMARY (For Narrative & Character Continuity) ===\n")
            sb.append(previousContextSummary.trim()).append("\n\n")
        }

        if (glossary.isNotEmpty()) {
            sb.append("=== MANDATORY TERMINOLOGY & GLOSSARY (Strictly enforce these translations) ===\n")
            for (term in glossary) {
                sb.append("• [${term.category.name}] \"${term.originalTerm}\" -> \"${term.translatedTerm}\"")
                if (term.notes.isNotBlank()) {
                    sb.append(" (Note: ${term.notes})")
                }
                sb.append("\n")
            }
            sb.append("\n")
        }

        sb.append("=== NOVEL CHAPTER TO TRANSLATE ===\n")
        sb.append("【Title】: ").append(chapterTitle).append("\n\n")
        sb.append(chapterText)
        sb.append("\n\n=== TRANSLATION OUTPUT ONLY ===")

        return sb.toString()
    }

    fun buildChunkUserPrompt(
        chapterTitle: String,
        chunkIndex: Int,
        totalChunks: Int,
        chunkText: String,
        glossary: List<GlossaryEntity>,
        previousContextSummary: String? = null,
        previousChunkTranslationReference: String? = null
    ): String {
        val sb = StringBuilder()

        if (!previousContextSummary.isNullOrBlank()) {
            sb.append("=== PREVIOUS CHAPTER STORY SUMMARY ===\n")
            sb.append(previousContextSummary.trim()).append("\n\n")
        }

        if (!previousChunkTranslationReference.isNullOrBlank()) {
            sb.append("=== PREVIOUS CHUNK REFERENCE (For tone and sentence continuity ONLY - DO NOT REPEAT IN OUTPUT) ===\n")
            sb.append(previousChunkTranslationReference.trim()).append("\n\n")
        }

        if (glossary.isNotEmpty()) {
            sb.append("=== MANDATORY TERMINOLOGY & GLOSSARY (Strictly enforce these translations) ===\n")
            for (term in glossary) {
                sb.append("• [${term.category.name}] \"${term.originalTerm}\" -> \"${term.translatedTerm}\"")
                if (term.notes.isNotBlank()) {
                    sb.append(" (Note: ${term.notes})")
                }
                sb.append("\n")
            }
            sb.append("\n")
        }

        sb.append("=== NOVEL CHAPTER CONTENT (Part $chunkIndex of $totalChunks) ===\n")
        sb.append("【Chapter】: ").append(chapterTitle).append("\n\n")
        sb.append(chunkText)
        sb.append("\n\n=== TRANSLATION OUTPUT ONLY (Translate Part $chunkIndex only, do not repeat previous parts) ===")

        return sb.toString()
    }

    fun buildContinuationPrompt(
        originalChunkText: String,
        partialTranslation: String
    ): String {
        return """
=== TRANSLATION TRUNCATED / CONTINUATION REQUIRED ===
The previous translation response was cut off mid-text due to output token limit.
Continue translating the remainder of the original text from the exact point of cutoff.

Original Text Segment:
$originalChunkText

Partial Translation Already Generated (DO NOT repeat this, continue immediately from where it left off):
...${partialTranslation.takeLast(1200)}

=== CONTINUATION OUTPUT ONLY ===
        """.trimIndent()
    }

    fun buildValidationRetryPrompt(originalPrompt: String, problems: List<String>): String = """
$originalPrompt

IMPORTANT RETRY: The previous response was rejected because ${problems.joinToString("; ")}.
Translate the complete requested source again from the beginning. Preserve every paragraph break and every [IMG:filename] marker exactly. Do not summarize, explain, refuse, or use Markdown fences.
    """.trimIndent()

    fun buildChapterSummaryPrompt(translatedChapter: String): String {
        val excerpt = if (translatedChapter.length > 5000) {
            val head = translatedChapter.take(2000)
            val midStart = (translatedChapter.length / 2) - 1000
            val mid = translatedChapter.substring(midStart, midStart + 2000)
            val tail = translatedChapter.takeLast(1000)
            "$head\n\n[...]\n\n$mid\n\n[...]\n\n$tail"
        } else {
            translatedChapter
        }

        return """
Summarize the following novel chapter in 2-3 concise sentences. Focus on:
1. Key narrative events and plot progress
2. Main characters involved, their emotions and relationships
3. Ongoing conflicts, newly revealed lore, or cliffhangers
Keep it concise to provide story continuity context for translating the subsequent chapter.

Chapter Content:
$excerpt
        """.trimIndent()
    }

    fun buildTermExtractionPrompt(
        textSample: String,
        sourceLanguage: String,
        targetLanguage: String,
        existingTerms: Collection<String> = emptyList()
    ): String {
        val existing = existingTerms.asSequence().map { it.trim().take(120) }
            .filter { it.isNotBlank() }.take(300).joinToString(", ")
        return """
    You are an expert novel editor and lore archivist. Analyze the following novel excerpt and extract key proper nouns and terms to build a translation glossary.

Source language: $sourceLanguage
Required target language for every suggested translation: $targetLanguage
Existing source terms that MUST NOT be returned again: ${existing.ifBlank { "None" }}

Extract entities under these categories:
- CHARACTER (Names, nicknames, aliases)
- LOCATION (Kingdoms, cities, dungeons, taverns, realms)
- LORE (Factions, clans, races, gods, magical concepts)
- SKILL (Techniques, spells, martial arts, abilities)
- ITEM (Weapons, artifacts, currency, materials)
- HONORIFIC (Special titles, forms of address)

Output format: Return valid JSON array of objects:
[
  {
    "original": "original term in text",
    "suggested": "recommended target translation",
    "category": "CHARACTER" | "LOCATION" | "LORE" | "SKILL" | "ITEM" | "HONORIFIC" | "CUSTOM",
    "notes": "short description or context"
  }
]

Text Excerpt:
${textSample}
        """.trimIndent()
    }

    fun buildAgentChapterSplitPrompt(rawTextSample: String): String {
        return """
You are an AI novel structuring agent. The user uploaded raw novel text where chapter boundaries are non-standard or missing.
Inspect the text and identify natural chapter boundaries or scene transitions.

Return a JSON array of detected chapters:
[
  {
    "index": 1,
    "title": "Detected or Inferred Chapter Title",
    "firstSentence": "The exact first 15-30 characters of this chapter to locate split position"
  }
]

Text Sample:
${rawTextSample.take(22000)}
        """.trimIndent()
    }
}
