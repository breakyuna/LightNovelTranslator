package com.breakyuna.noveltranslator.core.translation

import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.data.model.LexiconEntryEntity
import com.breakyuna.noveltranslator.data.model.LexiconKind
import com.breakyuna.noveltranslator.data.model.StoryMemoryEntity

data class ProtocolSegment(val shortId: Int, val logicalSegmentId: Long, val text: String)
data class ProtocolChapter(
    val shortId: Int,
    val logicalChapterId: Long,
    val chapterIndex: Int,
    val title: String,
    val segments: List<ProtocolSegment>
)

data class ContextPackage(
    val stablePrefix: String,
    val matchedLexicon: List<LexiconEntryEntity>,
    val relatedStoryMemory: List<StoryMemoryEntity>,
    val recentContext: String,
    val fingerprint: String
)

data class ParsedTranslationChapter(val shortId: Int, val segments: Map<Int, String>)
data class ParsedTranslationResponse(
    val chapters: List<ParsedTranslationChapter>,
    val metaJson: String?,
    val isTruncated: Boolean
)

object TranslationProtocol {
    fun systemPrompt(sourceLanguage: String, targetLanguage: String): String = """
        You are a professional literary translator from $sourceLanguage to $targetLanguage.
        Translate every supplied segment completely. Preserve meaning, voice, paragraph boundaries, titles,
        dialogue punctuation and every [IMG:...] marker. Obey confirmed terminology exactly.
        Return only the protocol response. Never copy the source, add explanations, or reorder segments.

        The response must be:
        <TRANSLATION><C id="chapter"><S id="segment">translated text</S></C></TRANSLATION>
        <META>{"chapterMemory":[],"storyMemoryDelta":[],"lexiconCandidate":[]}</META>
        META must contain compact incremental data only. Translation remains valid if META cannot be produced.
    """.trimIndent()

    fun userPrompt(context: ContextPackage, chapters: List<ProtocolChapter>): String = buildString {
        append("[TRANSLATION_PROTOCOL]\n").append(context.stablePrefix).append("\n\n")
        if (context.matchedLexicon.isNotEmpty()) {
            append("[LEXICON]\n")
            context.matchedLexicon.forEach { append(it.sourceTerm).append(" => ").append(it.targetTerm).append('\n') }
        }
        if (context.relatedStoryMemory.isNotEmpty()) {
            append("\n[STORY_MEMORY]\n")
            context.relatedStoryMemory.forEach { append(it.factKey).append(": ").append(it.factValue).append('\n') }
        }
        if (context.recentContext.isNotBlank()) append("\n[RECENT_CONTEXT]\n").append(context.recentContext).append('\n')
        append("\n[SOURCE]\n")
        chapters.forEach { chapter ->
            append("<C id=\"").append(chapter.shortId).append("\" title=\"").append(escape(chapter.title)).append("\">\n")
            chapter.segments.forEach { segment ->
                append("<S id=\"").append(segment.shortId).append("\">")
                    .append(escape(segment.text)).append("</S>\n")
            }
            append("</C>\n")
        }
    }

    fun parse(raw: String): ParsedTranslationResponse {
        val translationMatch = Regex("<TRANSLATION>([\\s\\S]*?)</TRANSLATION>", RegexOption.IGNORE_CASE).find(raw)
        val translationBody = translationMatch?.groupValues?.get(1) ?: run {
            val open = Regex("<TRANSLATION>", RegexOption.IGNORE_CASE).find(raw)
            if (open == null) "" else raw.substring(open.range.last + 1)
        }
        val chapters = Regex("<C\\s+id=\"?(\\d+)\"?[^>]*>([\\s\\S]*?)</C>", RegexOption.IGNORE_CASE)
            .findAll(translationBody)
            .map { chapter ->
                val segments = Regex("<S\\s+id=\"?(\\d+)\"?[^>]*>([\\s\\S]*?)</S>", RegexOption.IGNORE_CASE)
                    .findAll(chapter.groupValues[2])
                    .associate { it.groupValues[1].toInt() to unescape(it.groupValues[2].trim()) }
                ParsedTranslationChapter(chapter.groupValues[1].toInt(), segments)
            }.toMutableList()
        if (translationMatch == null) {
            val lastOpen = Regex("<C\\s+id=\"?(\\d+)\"?[^>]*>", RegexOption.IGNORE_CASE).findAll(translationBody).lastOrNull()
            val incompleteId = lastOpen?.groupValues?.get(1)?.toIntOrNull()
            if (lastOpen != null && incompleteId != null && chapters.none { it.shortId == incompleteId }) {
                val tail = translationBody.substring(lastOpen.range.last + 1)
                val completeSegments = Regex("<S\\s+id=\"?(\\d+)\"?[^>]*>([\\s\\S]*?)</S>", RegexOption.IGNORE_CASE)
                    .findAll(tail).associate { it.groupValues[1].toInt() to unescape(it.groupValues[2].trim()) }
                if (completeSegments.isNotEmpty()) chapters += ParsedTranslationChapter(incompleteId, completeSegments)
            }
        }
        val meta = Regex("<META>([\\s\\S]*?)</META>", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.trim()
        return ParsedTranslationResponse(
            chapters = chapters,
            metaJson = meta,
            isTruncated = translationMatch == null || raw.contains("<META>", true) && !raw.contains("</META>", true)
        )
    }

    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescape(value: String) = value.replace("&quot;", "\"").replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")
}

data class TokenBudgetPlan(
    val actualBatchSize: Int,
    val inputBudget: Long,
    val outputBudget: Long,
    val requiresSingleChapterChunking: Boolean
)

object TokenBudgetPlanner {
    fun plan(
        maxContextTokens: Int,
        userMaxBatchSize: Int,
        sourceTokenEstimates: List<Long>,
        fixedContextTokens: Long,
        outputRatio: Double = 1.35,
        metaReserve: Long = 800,
        safetyRatio: Double = 0.12
    ): TokenBudgetPlan {
        require(userMaxBatchSize in 1..5)
        val safety = (maxContextTokens * safetyRatio).toLong().coerceAtLeast(512)
        val available = (maxContextTokens.toLong() - fixedContextTokens - metaReserve - safety).coerceAtLeast(0)
        var used = 0L
        var accepted = 0
        for (source in sourceTokenEstimates.take(userMaxBatchSize)) {
            val cost = source + (source * outputRatio).toLong()
            if (used + cost > available) break
            used += cost
            accepted++
        }
        val firstCost = sourceTokenEstimates.firstOrNull()?.let { it + (it * outputRatio).toLong() } ?: 0L
        return TokenBudgetPlan(
            actualBatchSize = accepted.coerceAtMost(userMaxBatchSize),
            inputBudget = (available - used).coerceAtLeast(0),
            outputBudget = (used * outputRatio / (1.0 + outputRatio)).toLong(),
            requiresSingleChapterChunking = sourceTokenEstimates.isNotEmpty() && firstCost > available
        )
    }

    fun estimate(text: String): Long = TokenCalculator.estimateTokens(text)
}

data class QaResult(val accepted: Boolean, val problems: List<String>)

object DeterministicTranslationQa {
    fun validate(
        source: ProtocolChapter,
        translated: ParsedTranslationChapter?,
        mandatoryTerms: List<LexiconEntryEntity> = emptyList()
    ): QaResult {
        val problems = mutableListOf<String>()
        if (translated == null) return QaResult(false, listOf("missing chapter boundary"))
        val expected = source.segments.map { it.shortId }
        val actual = translated.segments.keys
        // Map iteration order is not a correctness property. Persistence always follows source order.
        if (actual.size != expected.size || actual.toSet() != expected.toSet()) problems += "segment ids missing or duplicated"
        source.segments.forEach { segment ->
            val target = translated.segments[segment.shortId].orEmpty()
            if (target.isBlank()) problems += "empty segment ${segment.shortId}"
            if (segment.text.length > 120 && target.length < segment.text.length * 0.04) problems += "possible omission in segment ${segment.shortId}"
            val sourceImages = Regex("\\[IMG:[^]]+]", RegexOption.IGNORE_CASE).findAll(segment.text).map { it.value }.toList()
            val targetImages = Regex("\\[IMG:[^]]+]", RegexOption.IGNORE_CASE).findAll(target).map { it.value }.toList()
            if (sourceImages != targetImages) problems += "image markers changed in segment ${segment.shortId}"
            if (target.length > segment.text.length * 8 + 500) problems += "abnormal length in segment ${segment.shortId}"
            if (listOf("I can't translate", "I cannot translate", "as an AI", "以下是翻译", "翻译如下").any { target.contains(it, true) }) {
                problems += "refusal or explanatory text in segment ${segment.shortId}"
            }
        }
        val translatedChapterText = source.segments.joinToString("\n") { translated.segments[it.shortId].orEmpty() }
        mandatoryTerms.distinctBy { it.sourceTerm.lowercase() }.forEach { entry ->
            val sourceTerm = entry.sourceTerm.trim()
            val meaningfulTerm = sourceTerm.any { it.code > 127 } && sourceTerm.length >= 2 ||
                sourceTerm.count(Char::isLetterOrDigit) >= 3
            val appearsInSource = meaningfulTerm && source.segments.any { LexiconTermMatcher.matchesSource(entry, it.text) }
            if (appearsInSource && entry.targetTerm.isNotBlank() &&
                !LexiconTermMatcher.matchesTarget(entry, translatedChapterText)
            ) {
                problems += "mandatory terminology violation: $sourceTerm"
            }
        }
        val duplicates = translated.segments.values.map { it.trim() }.filter { it.length >= 30 }.groupingBy { it }.eachCount().filterValues { it > 1 }
        if (duplicates.isNotEmpty()) problems += "repeated translated segments"
        return QaResult(problems.isEmpty(), problems.distinct())
    }
}

internal object LexiconTermMatcher {
    fun matchesSource(entry: LexiconEntryEntity, text: String): Boolean {
        val terms = sequenceOf(entry.sourceTerm) + entry.aliases.split(',', ';', '|').asSequence()
        return terms.map(String::trim).filter(String::isNotBlank)
            .any { containsSourceTerm(text, it, entry.caseSensitive, entry.exactMatch) }
    }

    fun matchesTarget(entry: LexiconEntryEntity, text: String): Boolean {
        val target = entry.targetTerm.trim()
        if (target.isBlank()) return true
        val chunks = Regex("[\\p{L}\\p{N}_]+").findAll(target).map { Regex.escape(it.value) }.toList()
        if (chunks.isEmpty()) return contains(text, target, entry.caseSensitive)

        val isAsciiWordTerm = target.all { it.code < 128 && (it.isLetterOrDigit() || it == '_' || it.isWhitespace() || isPunctuation(it)) }
        if (!isAsciiWordTerm) {
            val normalizedTarget = normalizeSurface(target, entry.caseSensitive)
            val normalizedText = normalizeSurface(text, entry.caseSensitive)
            return normalizedTarget.isNotBlank() && normalizedText.contains(normalizedTarget)
        }
        val prefix = if (isAsciiWordTerm) "(?<![\\p{L}\\p{N}_])" else ""
        val suffix = if (isAsciiWordTerm) {
            if (entry.kind == LexiconKind.TERMINOLOGY.name) "(?:s|es|ed|ing)?(?![\\p{L}\\p{N}_])"
            else "(?![\\p{L}\\p{N}_])"
        } else ""
        val options = if (entry.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(prefix + chunks.joinToString("[\\p{P}\\p{Z}\\s]*") + suffix, options).containsMatchIn(text)
    }

    private fun containsSourceTerm(text: String, term: String, caseSensitive: Boolean, exactMatch: Boolean): Boolean {
        val isAsciiPhrase = term.all {
            it.code < 128 && (it.isLetterOrDigit() || it == '_' || it.isWhitespace() || isPunctuation(it))
        }
        if (exactMatch && isAsciiPhrase) {
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            return Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(term)}(?![\\p{L}\\p{N}_])", options)
                .containsMatchIn(text)
        }
        return contains(text, term, caseSensitive)
    }

    private fun contains(text: String, term: String, caseSensitive: Boolean): Boolean =
        if (caseSensitive) text.contains(term) else text.contains(term, ignoreCase = true)

    private fun normalizeSurface(value: String, caseSensitive: Boolean): String = buildString(value.length) {
        value.forEach { char ->
            if (char.isLetterOrDigit() || char == '_') append(if (caseSensitive) char else char.lowercaseChar())
        }
    }

    private fun isPunctuation(char: Char): Boolean = !char.isLetterOrDigit() && !char.isWhitespace() && char != '_'
}
