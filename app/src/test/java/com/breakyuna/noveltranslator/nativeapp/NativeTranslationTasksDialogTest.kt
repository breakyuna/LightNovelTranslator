package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.breakyuna.noveltranslator.nativecore.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h892dp-mdpi", sdk = [35])
class NativeTranslationTasksDialogTest {
    @get:Rule val compose = createComposeRule()
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    private val spec = TranslationTaskSpec(ref, RunIdentity(ref.projectId, null, RunId("run"), CommandId("command")),
        ModelConfigurationRef("model", 1), 1, 0, 40, true, RemoteTranslationOptions("en"))
    private val summary get() = TranslationTaskSummary(spec.ref, spec.run, spec.configuration, spec.options.targetLanguage, TranslationTaskProgress(1, 1, 1), 3, 2, "READY", false)
    @Test fun failedTaskHasNoPaidResumeAction() {
        val failed = TranslationTaskSummary(ref, spec.run, spec.configuration, "en", TranslationTaskProgress(3, 0, 1),
            3, 0, "FAILED", false)
        var inspected = 0
        compose.setContent { NativeTheme {
            NativeTranslationTasksDialog(ref, { listOf(failed) }, { _, _ ->
                inspected++; NativeTranslationResumePreview(spec, "fixture")
            }, {}, { error("Resumed a failed task") })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-task-failed-command").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-task-command").assertDoesNotExist()
        compose.onNodeWithTag("native-task-confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, inspected) }
    }

    @Test fun failedOlderPageCanBeRetriedWithoutDiscardingLoadedTasks() {
        var olderAttempts = 0
        val olderRun = RunIdentity(ref.projectId, null, RunId("old-run"), CommandId("old-command"))
        val older = TranslationTaskSummary(ref, olderRun, spec.configuration, "en", TranslationTaskProgress(1, 0, 0), 3, 3, "READY", false)
        compose.setContent { NativeTheme {
            NativeTranslationTasksDialog(ref, { error("Expected paged loader") },
                { _, _ -> NativeTranslationResumePreview(spec, "fixture-model") }, {}, {},
                loadPage = { selected, before ->
                    assertEquals(ref, selected)
                    if (before == null) TranslationTaskPage(listOf(summary), 20)
                    else {
                        assertEquals(20L, before)
                        olderAttempts++
                        if (olderAttempts == 1) error("Temporary read failure")
                        TranslationTaskPage(listOf(older), null)
                    }
                })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-task-command").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-tasks-more").performScrollTo().performClick()
        compose.waitUntil(5_000) { olderAttempts == 1 }
        compose.onNodeWithTag("native-task-command").assertExists()
        compose.onNodeWithTag("native-tasks-more").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-task-old-command").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-task-command").assertExists()
        compose.onNodeWithTag("native-tasks-more").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, olderAttempts) }
    }

    @Test fun queueManagedTaskHasNoLegacyDecisionAndRoutesToTheQueueReview() {
        var reviewed: RunIdentity? = null
        compose.setContent { NativeTheme {
            NativeTranslationTasksDialog(ref, { listOf(summary) }, { _, _ -> error("Legacy inspection") },
                {}, { error("Legacy resume") }, inspectReview = { _, _ -> error("Legacy review") },
                terminate = { _, _ -> error("Legacy terminate") },
                queueManagedRuns = setOf("run"), queueReviewableRuns = setOf("run"),
                reviewQueueTask = { reviewed = it })
        } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-task-queue-review-command").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("native-task-legacy-review-command").assertDoesNotExist()
        compose.onNodeWithTag("native-task-command").assertDoesNotExist()
        compose.onNodeWithTag("native-task-queue-review-command").performClick()
        compose.runOnIdle { assertEquals(spec.run, reviewed) }
    }

    @Test fun historicalNonQueueTaskStillShowsItsReadOnlyLegacyReview() {
        var reviewed = 0
        compose.setContent { NativeTheme {
            NativeTranslationTasksDialog(ref, { listOf(summary) }, { _, _ -> NativeTranslationResumePreview(spec, "fixture") },
                {}, {}, inspectReview = { _, run ->
                    reviewed++; TranslationTaskReview(ref, run, "READY", 1, 1, 2, 1, 0,
                        ObjectFingerprint("a".repeat(64)))
                }, terminate = { _, _ -> })
        } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-task-legacy-review-command").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("native-task-queue-review-command").assertDoesNotExist()
        compose.onNodeWithTag("native-task-legacy-review-command").performClick()
        compose.waitUntil(5_000) { reviewed == 1 }
    }

    @Test fun inspectionNeverStartsWorkBeforeExplicitPaidConfirmation() {
        var resumed: TranslationTaskSpec? = null
        compose.setContent { NativeTheme {
            NativeTranslationTasksDialog(ref, { listOf(summary) }, { selected, run ->
                assertEquals(ref, selected); assertEquals(spec.run, run); NativeTranslationResumePreview(spec, "fixture-model")
            }, {}, { resumed = it })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-task-command").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-task-command").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-task-confirm").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertNull(resumed) }
        compose.onNodeWithTag("native-task-confirm").performClick()
        compose.runOnIdle { assertEquals(spec, resumed) }
    }
    @Test fun uncertainTaskCannotReachConfirmationOrDispatch() {
        var resumed = 0
        compose.setContent { NativeTheme {
            NativeTranslationTasksDialog(ref, { listOf(summary) }, { _, _ -> throw TranslationTaskNeedsReview() }, {}, { resumed++ })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-task-command").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-task-command").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("native-task-review").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-task-confirm").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, resumed) }
    }
}
