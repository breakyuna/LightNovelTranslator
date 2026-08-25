package com.breakyuna.noveltranslator.core.translation

import com.breakyuna.noveltranslator.data.db.LexiconV2Dao
import com.breakyuna.noveltranslator.data.db.MemoryDao
import com.breakyuna.noveltranslator.data.model.ContextSnapshotEntity
import com.breakyuna.noveltranslator.data.model.LexiconEntryEntity
import com.breakyuna.noveltranslator.data.model.TranslationProjectV2Entity
import java.security.MessageDigest

class ContextEngine(
    private val lexiconDao: LexiconV2Dao,
    private val memoryDao: MemoryDao
) {
    suspend fun prepare(
        project: TranslationProjectV2Entity,
        sourceText: String,
        firstChapterIndex: Int
    ): ContextPackage {
        val confirmed = lexiconDao.getConfirmed(project.id)
        val matched = confirmed.filter { matches(it, sourceText) }
            .sortedWith(compareByDescending<LexiconEntryEntity> { it.priority }.thenByDescending { it.sourceTerm.length })
        val allStory = memoryDao.getStoryMemory(project.id)
        val normalizedSource = sourceText.lowercase()
        val related = allStory.filter { memory ->
            memory.entities.split(',', ';', '|').map(String::trim).filter(String::isNotBlank)
                .any { normalizedSource.contains(it.lowercase()) } ||
                normalizedSource.contains(memory.factKey.lowercase()) ||
                firstChapterIndex - memory.lastUpdatedChapterIndex in 0..3
        }.take(40)
        val recent = memoryDao.getRecentChapterMemory(project.id, firstChapterIndex, 3)
            .sortedBy { it.chapterIndex }
            .joinToString("\n") { "Chapter ${it.chapterIndex}: ${it.summary.take(700)}" }

        val snapshot = memoryDao.latestSnapshot(project.id) ?: createSnapshot(project, confirmed)
        return ContextPackage(
            stablePrefix = snapshot.stablePrefix,
            matchedLexicon = matched.take(120),
            relatedStoryMemory = related,
            recentContext = recent,
            fingerprint = snapshot.fingerprint
        )
    }

    private suspend fun createSnapshot(
        project: TranslationProjectV2Entity,
        confirmed: List<LexiconEntryEntity>
    ): ContextSnapshotEntity {
        val coreTerms = confirmed.filter { it.priority >= CORE_PRIORITY }.take(80)
        val prefix = buildString {
            append("Protocol version: ").append(project.promptProtocolVersion).append('\n')
            append("Style guide: ").append(project.styleGuide.trim()).append('\n')
            if (coreTerms.isNotEmpty()) {
                append("Core terminology:\n")
                coreTerms.forEach { append(it.sourceTerm).append(" => ").append(it.targetTerm).append('\n') }
            }
        }
        val snapshot = ContextSnapshotEntity(
            translationProjectId = project.id,
            protocolVersion = project.promptProtocolVersion,
            styleGuideVersion = 1,
            coreLexiconVersion = confirmed.maxOfOrNull { it.updatedAt }?.hashCode() ?: 0,
            storyMemoryVersion = 1,
            stablePrefix = prefix,
            fingerprint = sha256(prefix)
        )
        return snapshot.copy(id = memoryDao.insertSnapshot(snapshot))
    }

    private fun matches(entry: LexiconEntryEntity, text: String): Boolean {
        val options = if (entry.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val terms = listOf(entry.sourceTerm) + entry.aliases.split(',', ';', '|')
        return terms.map(String::trim).filter(String::isNotBlank).any { term ->
            if (entry.exactMatch && term.all { it.code < 128 && (it.isLetterOrDigit() || it == '_') }) {
                Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(term)}(?![\\p{L}\\p{N}_])", options).containsMatchIn(text)
            } else if (entry.caseSensitive) text.contains(term) else text.contains(term, ignoreCase = true)
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object { private const val CORE_PRIORITY = 100 }
}
