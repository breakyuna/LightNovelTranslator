package com.breakyuna.noveltranslator.core.llm

import com.breakyuna.noveltranslator.data.model.GlossaryEntity
import com.breakyuna.noveltranslator.data.model.LexiconCandidateVoting
import com.breakyuna.noveltranslator.data.model.ReviewStatus

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
3. Terminology Consistency: For every confirmed glossary entry that appears in the source, use its exact target translation consistently. Do not invent alternate transliterations, omit the target, or let unconfirmed candidates influence the translation.
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
        val activeGlossary = selectConfirmedGlossaryForText(glossary, chapterText)

        if (!previousContextSummary.isNullOrBlank()) {
            sb.append("=== PREVIOUS STORY CONTEXT & SUMMARY (For Narrative & Character Continuity) ===\n")
            sb.append(previousContextSummary.trim()).append("\n\n")
        }

        if (activeGlossary.isNotEmpty()) {
            sb.append("=== MANDATORY TERMINOLOGY & GLOSSARY (Strictly enforce these translations) ===\n")
            for (term in activeGlossary) {
                sb.append("• [${term.category.name}] \"${term.originalTerm.trim()}\" -> \"${term.translatedTerm.trim()}\"")
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
        val activeGlossary = selectConfirmedGlossaryForText(glossary, chunkText)

        if (!previousContextSummary.isNullOrBlank()) {
            sb.append("=== PREVIOUS CHAPTER STORY SUMMARY ===\n")
            sb.append(previousContextSummary.trim()).append("\n\n")
        }

        if (!previousChunkTranslationReference.isNullOrBlank()) {
            sb.append("=== PREVIOUS CHUNK REFERENCE (For tone and sentence continuity ONLY - DO NOT REPEAT IN OUTPUT) ===\n")
            sb.append(previousChunkTranslationReference.trim()).append("\n\n")
        }

        if (activeGlossary.isNotEmpty()) {
            sb.append("=== MANDATORY TERMINOLOGY & GLOSSARY (Strictly enforce these translations) ===\n")
            for (term in activeGlossary) {
                sb.append("• [${term.category.name}] \"${term.originalTerm.trim()}\" -> \"${term.translatedTerm.trim()}\"")
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

    /**
     * Legacy prompt boundary: only confirmed, usable terms that occur in this exact source
     * payload may become translation constraints. V2 uses ContextEngine for the same policy.
     */
    fun selectConfirmedGlossaryForText(
        glossary: Iterable<GlossaryEntity>,
        sourceText: String
    ): List<GlossaryEntity> = glossary.asSequence()
        .filter { it.reviewStatus == ReviewStatus.CONFIRMED.name }
        .map { it.copy(originalTerm = it.originalTerm.trim(), translatedTerm = it.translatedTerm.trim()) }
        .filter { it.originalTerm.isNotBlank() && it.translatedTerm.isNotBlank() }
        .filter { sourceText.contains(it.originalTerm, ignoreCase = true) }
        .distinctBy { LexiconCandidateVoting.normalizeSourceTerm(it.originalTerm) }
        .toList()

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
    You are an expert novel editor and lore archivist. Analyze the novel excerpt and discover only terms that are genuinely useful as stable translation glossary entries.

Source language: $sourceLanguage
Required target language for every suggested translation: $targetLanguage
Already CONFIRMED source terms (do not return these again): ${existing.ifBlank { "None" }}

DISCOVERY RULE: Be complete during discovery, but keep the final term boundary strict and necessary. Include a term only when it is one of these:
1. A specific character name, nickname, or alias in this work.
2. A specific place, country, city, academy, dungeon, region, or other named location.
3. A specific organization, family, faction, race, religion, deity, or other named world-building group.
4. A skill, spell, ability, or system that has a proper name.
5. A named weapon, artifact, item, currency, material, or other uniquely named object.
6. A work-specific concept whose meaning or translation cannot be stably inferred from an ordinary translation and matters for world-building consistency.
7. A specific, fixed special title used as a named designation in this work.

DO NOT extract ordinary nouns, common verbs, adjectives, adverbs, pronouns, ordinary descriptive phrases, generic jobs, generic family/social relationship words, generic titles, generic magic or weapon classes, generic monster classes, or generic place types. A word is not special merely because it appears in a fantasy novel.

Usually exclude examples such as: sword, magic, castle, carriage, monster, guild, warrior, merchant, student, teacher, father, mother, young master, miss, lord, mister, mana. Include one only if the excerpt explicitly establishes it as a specific fixed named concept, not merely because it is capitalized or used often.

BOUNDARY RULES:
- "original" must be an exact continuous substring copied from the excerpt, with no rewriting, translation, invented spelling, or surrounding explanation.
- If the text says "Knight Irene" and Irene is the actual name, return "Irene", not "Knight Irene".
- If "Silver Moon Academy" is the official institution name, return the complete phrase "Silver Moon Academy".
- Do not return a whole sentence or a long descriptive phrase. Prefer the smallest necessary proper name, except when the complete phrase itself is the established name.
- A source term may intentionally keep the same spelling in the target language for a named token or control-like marker; do not use source==target as a reason to include an ordinary word.
- Keep notes to one short sentence about disambiguation, identity, or world-building meaning. Do not write an essay.

POSITIVE examples:
- "Irene entered the Silver Moon Academy." -> "Irene" (CHARACTER), "Silver Moon Academy" (LOCATION)
- "He invoked Starfall Severance." -> "Starfall Severance" (SKILL) when it is the named spell, not "magic".
- "The relic Dawnpiercer was drawn." -> "Dawnpiercer" (ITEM) when it is the named relic, not "sword".
NEGATIVE examples:
- "The knight raised his sword." -> do not extract "knight" or "sword".
- "They rode a carriage toward the castle." -> do not extract "carriage" or "castle".
- "The young master spoke to his mother." -> do not extract "young master" or "mother".

Allowed category values are exactly:
- CHARACTER: specific person name, nickname, or alias
- LOCATION: specific named place
- LORE: specific named faction, race, religion, deity, or work-specific concept
- SKILL: specifically named skill, spell, ability, or system
- ITEM: specifically named object, weapon, currency, or material
- HONORIFIC: specific fixed special designation or title
Never output CUSTOM or any other category.

Output only a valid JSON array of objects:
[
  {
    "original": "original term in text",
    "suggested": "recommended target translation",
    "category": "CHARACTER",
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
