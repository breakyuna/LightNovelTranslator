package com.breakyuna.noveltranslator.core.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.data.db.AppDatabase
import com.breakyuna.noveltranslator.data.model.BookEntity
import com.breakyuna.noveltranslator.data.model.EditionEntity
import com.breakyuna.noveltranslator.data.model.LexiconCandidateState
import com.breakyuna.noveltranslator.data.model.LexiconCandidateVoting
import com.breakyuna.noveltranslator.data.model.LexiconEntryEntity
import com.breakyuna.noveltranslator.data.model.LexiconSource
import com.breakyuna.noveltranslator.data.model.ReviewStatus
import com.breakyuna.noveltranslator.data.model.TermCategory
import com.breakyuna.noveltranslator.data.model.TranslationProjectV2Entity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LexiconCandidatePersistenceTest {
    @Test
    fun aggregateSurvivesDatabaseReopenAndIgnoredCandidateIsNotRecreated() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "candidate_persistence_${System.nanoTime()}.db"
        var databaseForCleanup: AppDatabase? = null
        try {
            val initialDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            databaseForCleanup = initialDatabase
            val bookId = initialDatabase.bookDao().insert(BookEntity(title = "Book"))
            val sourceEditionId = initialDatabase.bookDao().insertEdition(
                EditionEntity(bookId = bookId, name = "Source", language = "en")
            )
            val targetEditionId = initialDatabase.bookDao().insertEdition(
                EditionEntity(bookId = bookId, name = "Target", language = "zh", sourceEditionId = sourceEditionId)
            )
            val projectId = initialDatabase.translationProjectV2Dao().insert(
                TranslationProjectV2Entity(
                    bookId = bookId,
                    sourceEditionId = sourceEditionId,
                    targetEditionId = targetEditionId,
                    sourceLanguage = "en",
                    targetLanguage = "zh",
                    providerId = null
                )
            )
            val candidate = ExtractedTermCandidate(
                originalTerm = "Alice",
                translatedTerm = "爱丽丝",
                category = TermCategory.CHARACTER
            )
            val aggregator = LexiconCandidateAggregator(initialDatabase)
            repeat(3) { chapter ->
                aggregator.observeWindow(projectId, chapter + 1, "Alice arrived.", listOf(candidate))
            }
            initialDatabase.close()

            val reopenedDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            databaseForCleanup = reopenedDatabase
            val dao = reopenedDatabase.lexiconCandidateAggregateDao()
            val normalized = LexiconCandidateVoting.normalizeSourceTerm("Alice")
            val restored = dao.getBySource(projectId, normalized)!!
            assertEquals(3, restored.observationCount)

            val officialId = reopenedDatabase.lexiconV2Dao().upsert(
                LexiconEntryEntity(
                    translationProjectId = projectId,
                    sourceTerm = "Alice",
                    targetTerm = "旧译名",
                    category = TermCategory.CHARACTER.name,
                    priority = 42,
                    enabled = false,
                    source = LexiconSource.MANUAL.name,
                    reviewStatus = ReviewStatus.CONFIRMED.name
                )
            )
            val officialBeforeSkip = reopenedDatabase.lexiconV2Dao().getBySourceTerm(projectId, "Alice")!!
            dao.markImported(restored.id)
            val officialAfterSkip = reopenedDatabase.lexiconV2Dao().getBySourceTerm(projectId, "Alice")!!
            assertEquals(officialId, officialAfterSkip.id)
            assertEquals(officialBeforeSkip, officialAfterSkip)

            reopenedDatabase.lexiconV2Dao().upsert(
                LexiconEntryEntity(
                    translationProjectId = projectId,
                    sourceTerm = "Bob",
                    targetTerm = "鲍勃",
                    category = TermCategory.CHARACTER.name,
                    enabled = false,
                    source = LexiconSource.MANUAL.name,
                    reviewStatus = ReviewStatus.CONFIRMED.name
                )
            )
            LexiconCandidateAggregator(reopenedDatabase).observeWindow(
                projectId,
                4,
                "Bob appeared.",
                listOf(candidate.copy(originalTerm = "Bob", translatedTerm = "博布"))
            )
            assertNull(dao.getBySource(projectId, LexiconCandidateVoting.normalizeSourceTerm("Bob")))

            val ignoredCandidate = candidate.copy(originalTerm = "Trash", translatedTerm = "垃圾")
            LexiconCandidateAggregator(reopenedDatabase).observeWindow(
                projectId,
                4,
                "Trash appeared.",
                listOf(ignoredCandidate)
            )
            val ignoredNormalized = LexiconCandidateVoting.normalizeSourceTerm("Trash")
            val activeTrash = dao.getBySource(projectId, ignoredNormalized)!!
            dao.markIgnored(activeTrash.id)
            LexiconCandidateAggregator(reopenedDatabase).observeWindow(
                projectId,
                5,
                "Trash returned.",
                listOf(ignoredCandidate)
            )
            val ignored = dao.getBySource(projectId, ignoredNormalized)!!
            assertEquals(LexiconCandidateState.IGNORED.name, ignored.state)
            assertEquals(1, ignored.observationCount)
            reopenedDatabase.close()
            databaseForCleanup = null
        } finally {
            databaseForCleanup?.close()
            context.deleteDatabase(name)
        }
    }
}
