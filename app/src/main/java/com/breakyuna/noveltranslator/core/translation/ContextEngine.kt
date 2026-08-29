package com.breakyuna.noveltranslator.core.translation

import com.breakyuna.noveltranslator.data.db.LexiconV2Dao
import com.breakyuna.noveltranslator.data.db.MemoryDao
import com.breakyuna.noveltranslator.data.model.ContextSnapshotEntity
import com.breakyuna.noveltranslator.data.model.LexiconEntryEntity
import com.breakyuna.noveltranslator.data.model.LexiconCandidateVoting
import com.breakyuna.noveltranslator.data.model.LexiconEntryPolicy
import com.breakyuna.noveltranslator.data.model.StoryMemoryEntity
import com.breakyuna.noveltranslator.data.model.TranslationProjectV2Entity
import java.security.MessageDigest
import java.util.Locale

class ContextEngine(
    private val lexiconDao: LexiconV2Dao,
    private val memoryDao: MemoryDao
) {
    private companion object {
        const val CURRENT_PROMPT_PROTOCOL_VERSION = 2
        const val MAX_GLOSSARY_ENTRIES = 120
        const val MAX_GLOSSARY_CHARS = 12_000
        const val MAX_STORY_MEMORY_ENTRIES = 40
        const val MAX_STORY_MEMORY_CHARS = 12_000
        const val MAX_RECENT_CONTEXT_CHARS = 3_000
    }

    suspend fun prepare(
        project: TranslationProjectV2Entity,
        sourceText: String,
        firstChapterIndex: Int,
        previousChapterOriginalTail: String = "",
        previousChapterTranslationTail: String = ""
    ): ContextPackage {
        val confirmed = lexiconDao.getConfirmed(project.id)
        val matched = confirmed.filter { LexiconEntryPolicy.isEligibleForTranslation(it) && matches(it, sourceText) }
            .sortedWith(
                compareByDescending<LexiconEntryEntity> { it.priority }
                    .thenByDescending { it.sourceTerm.length }
                    .thenBy { LexiconCandidateVoting.normalizeSourceTerm(it.sourceTerm) }
                    .thenBy { it.id }
            )
        // Only facts written by already translated chapters are eligible. Without this upper
        // bound, rerunning a range could inject a future chapter's metadata into an earlier one.
        val allStory = memoryDao.getStoryMemory(project.id)
            .filter { it.sourceChapterIndex < firstChapterIndex && it.lastUpdatedChapterIndex < firstChapterIndex }
        val normalizedSource = sourceText.lowercase(Locale.ROOT)
        val related = allStory.mapNotNull { memory ->
            val entities = memory.entities.split(',', ';', '|').map(String::trim)
                .filter(String::isNotBlank)
            val entityHits = entities.count { normalizedSource.contains(it.lowercase(Locale.ROOT)) }
            val factHit = memory.factKey.isNotBlank() && normalizedSource.contains(memory.factKey.lowercase(Locale.ROOT))
            val recentHit = firstChapterIndex - memory.lastUpdatedChapterIndex in 0..3
            val score = entityHits * 4 + (if (factHit) 3 else 0) + (if (recentHit) 2 else 0)
            if (score == 0) null else score to memory
        }.sortedWith(
            compareByDescending<Pair<Int, StoryMemoryEntity>> { it.first }
                .thenByDescending { it.second.lastUpdatedChapterIndex }
                .thenBy { it.second.factKey }
                .thenBy { it.second.id }
        ).map { it.second }
            .let {
                takeWithin(it, MAX_STORY_MEMORY_ENTRIES, MAX_STORY_MEMORY_CHARS) { memory ->
                    memory.factKey.length + memory.factValue.length + memory.entities.length + 32
                }
            }
        val recent = memoryDao.getRecentChapterMemory(project.id, firstChapterIndex, 3)
            .sortedBy { it.chapterIndex }
            .joinToString("\n") { "Chapter ${it.chapterIndex}: ${it.summary.take(700)}" }

        val expectedSnapshot = buildSnapshot(project)
        val latestSnapshot = memoryDao.latestSnapshot(project.id)
        val snapshot = if (
            latestSnapshot != null &&
            latestSnapshot.protocolVersion == expectedSnapshot.protocolVersion &&
            latestSnapshot.styleGuideVersion == expectedSnapshot.styleGuideVersion &&
            latestSnapshot.coreLexiconVersion == expectedSnapshot.coreLexiconVersion &&
            latestSnapshot.fingerprint == expectedSnapshot.fingerprint
        ) {
            latestSnapshot
        } else {
            expectedSnapshot.copy(id = memoryDao.insertSnapshot(expectedSnapshot))
        }
        return ContextPackage(
            stablePrefix = snapshot.stablePrefix,
            matchedLexicon = takeWithin(
                matched,
                MAX_GLOSSARY_ENTRIES,
                MAX_GLOSSARY_CHARS
            ) { entry ->
                entry.sourceTerm.length + entry.targetTerm.length + entry.notes.length + entry.category.length + 32
            },
            relatedStoryMemory = related,
            recentContext = recent.take(MAX_RECENT_CONTEXT_CHARS),
            fingerprint = snapshot.fingerprint,
            previousChapterOriginalTail = previousChapterOriginalTail.trim().takeLast(900),
            previousChapterTranslationTail = previousChapterTranslationTail.trim().takeLast(900)
        )
    }

    private fun buildSnapshot(project: TranslationProjectV2Entity): ContextSnapshotEntity {
        val protocolVersion = maxOf(project.promptProtocolVersion, CURRENT_PROMPT_PROTOCOL_VERSION)
        val styleGuide = project.styleGuide.trim().take(2_000)
        val prefix = buildString {
            append("Protocol version: ").append(protocolVersion).append('\n')
            append("Style guide (untrusted preference): <TEXT>")
                .append(escapePromptData(styleGuide))
                .append("</TEXT>\n")
        }
        val snapshot = ContextSnapshotEntity(
            translationProjectId = project.id,
            protocolVersion = protocolVersion,
            styleGuideVersion = versionOf(styleGuide),
            // Glossary entries are request-local dynamic context and must never be cached in the
            // stable prefix. Otherwise a high-priority term constrains unrelated chapters.
            coreLexiconVersion = 0,
            storyMemoryVersion = 1,
            stablePrefix = prefix,
            fingerprint = sha256(prefix)
        )
        return snapshot
    }

    private fun matches(entry: LexiconEntryEntity, text: String): Boolean {
        return LexiconTermMatcher.matchesSource(entry, text)
    }

    private fun <T> takeWithin(items: List<T>, maxItems: Int, maxChars: Int, sizeOf: (T) -> Int = { it.toString().length }): List<T> {
        if (items.isEmpty()) return emptyList()
        val selected = ArrayList<T>(minOf(items.size, maxItems))
        var chars = 0
        for (item in items) {
            if (selected.size >= maxItems) break
            val size = sizeOf(item).coerceAtLeast(0)
            if (size > maxChars) continue
            if (selected.isNotEmpty() && chars + size > maxChars) break
            selected += item
            chars += size
        }
        return selected
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun escapePromptData(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun versionOf(value: String): Int = sha256(value).take(8).toLong(16).toInt()
}
