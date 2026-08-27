package com.breakyuna.noveltranslator.core.translation

import com.breakyuna.noveltranslator.data.db.LexiconV2Dao
import com.breakyuna.noveltranslator.data.db.MemoryDao
import com.breakyuna.noveltranslator.data.model.ContextSnapshotEntity
import com.breakyuna.noveltranslator.data.model.LexiconEntryEntity
import com.breakyuna.noveltranslator.data.model.LexiconCandidateVoting
import com.breakyuna.noveltranslator.data.model.LexiconEntryPolicy
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
        val matched = confirmed.filter { LexiconEntryPolicy.isEligibleForTranslation(it) && matches(it, sourceText) }
            .sortedWith(
                compareByDescending<LexiconEntryEntity> { it.priority }
                    .thenByDescending { it.sourceTerm.length }
                    .thenBy { LexiconCandidateVoting.normalizeSourceTerm(it.sourceTerm) }
                    .thenBy { it.id }
            )
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
            matchedLexicon = matched.take(120),
            relatedStoryMemory = related,
            recentContext = recent,
            fingerprint = snapshot.fingerprint
        )
    }

    private fun buildSnapshot(project: TranslationProjectV2Entity): ContextSnapshotEntity {
        val prefix = buildString {
            append("Protocol version: ").append(project.promptProtocolVersion).append('\n')
            append("Style guide: ").append(project.styleGuide.trim()).append('\n')
        }
        val snapshot = ContextSnapshotEntity(
            translationProjectId = project.id,
            protocolVersion = project.promptProtocolVersion,
            styleGuideVersion = versionOf(project.styleGuide.trim()),
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

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun versionOf(value: String): Int = sha256(value).take(8).toLong(16).toInt()
}
