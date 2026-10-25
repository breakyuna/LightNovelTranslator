package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.breakyuna.noveltranslator.nativecore.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Queue dialog lifecycle buttons only; no runner or paid request is started here. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h892dp-mdpi", sdk = [35])
class NativeTranslationQueueDialogTest {
    @get:Rule val compose = createComposeRule()
    private val ref = LocalProjectRef(ProjectId("p"), ProjectInstanceId("i"), RecoveryGeneration(1))
    private val spec = TranslationTaskSpec(ref, RunIdentity(ref.projectId, null, RunId("run"), CommandId("command")),
        ModelConfigurationRef("model", 1), 1, 0, 40, false, RemoteTranslationOptions("en"))

    @Test fun queuedTaskOffersStartNotPause() {
        var started: TranslationTaskQueueIdentity? = null
        var paused: TranslationTaskQueueIdentity? = null
        val snapshot = snapshot(TranslationTaskQueueState.QUEUED)
        compose.setContent {
            NativeTheme {
                NativeTranslationQueueDialog(
                    load = { snapshot }, ref = ref, resume = {}, cancel = {},
                    start = { started = it }, pause = { paused = it }, dismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-queue-start-run").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertTrue(compose.onAllNodesWithTag("native-queue-pause-run").fetchSemanticsNodes().isEmpty())
        }
        compose.onNodeWithTag("native-queue-start-run").performClick()
        compose.runOnIdle {
            assertEquals(spec.queueIdentity(), started)
            assertNull(paused)
        }
    }

    @Test fun runningTaskOffersPause() {
        var resumed: TranslationTaskQueueIdentity? = null
        var paused: TranslationTaskQueueIdentity? = null
        val running = snapshot(TranslationTaskQueueState.RUNNING)
        compose.setContent {
            NativeTheme {
                NativeTranslationQueueDialog(
                    load = { running }, ref = ref, resume = { resumed = it }, cancel = {},
                    start = {}, pause = { paused = it }, dismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-queue-pause-run").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertTrue(compose.onAllNodesWithTag("native-queue-resume-run").fetchSemanticsNodes().isEmpty())
        }
        compose.onNodeWithTag("native-queue-pause-run").performClick()
        compose.runOnIdle {
            assertEquals(spec.queueIdentity(), paused)
            assertNull(resumed)
        }
    }

    @Test fun pausedTaskOffersResumeAndCancel() {
        var resumed: TranslationTaskQueueIdentity? = null
        val paused = snapshot(TranslationTaskQueueState.PAUSED)
        compose.setContent {
            NativeTheme {
                NativeTranslationQueueDialog(
                    load = { paused }, ref = ref, resume = { resumed = it }, cancel = {},
                    start = {}, pause = {}, dismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-queue-resume-run").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertTrue(compose.onAllNodesWithTag("native-queue-pause-run").fetchSemanticsNodes().isEmpty())
        }
        compose.onNodeWithTag("native-queue-resume-run").performClick()
        compose.runOnIdle { assertEquals(spec.queueIdentity(), resumed) }
    }

    @Test fun needsReviewTaskWithBatchesOffersFailureReview() {
        var reviewed: TranslationTaskQueueIdentity? = null
        val failed = snapshot(TranslationTaskQueueState.NEEDS_REVIEW)
        compose.setContent {
            NativeTheme {
                NativeTranslationQueueDialog(
                    load = { failed }, ref = ref, resume = {}, cancel = {},
                    start = {}, pause = {}, review = { reviewed = it }, dismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-queue-review-run").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertTrue(compose.onAllNodesWithTag("native-queue-start-run").fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithTag("native-queue-pause-run").fetchSemanticsNodes().isEmpty())
        }
        compose.onNodeWithTag("native-queue-review-run").performClick()
        compose.runOnIdle { assertEquals(spec.queueIdentity(), reviewed) }
    }

    @Test fun failedTaskWithBatchesOffersFailureReviewButNoResume() {
        var reviewed: TranslationTaskQueueIdentity? = null
        val failed = snapshot(TranslationTaskQueueState.FAILED)
        compose.setContent {
            NativeTheme {
                NativeTranslationQueueDialog(
                    load = { failed }, ref = ref, resume = {}, cancel = {},
                    start = {}, pause = {}, review = { reviewed = it }, dismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-queue-review-run").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertTrue(compose.onAllNodesWithTag("native-queue-resume-run").fetchSemanticsNodes().isEmpty())
        }
        compose.onNodeWithTag("native-queue-review-run").performClick()
        compose.runOnIdle { assertEquals(spec.queueIdentity(), reviewed) }
    }

    @Test fun reviewIsNotOfferedWithoutAFailureBatch() {
        val queued = snapshot(TranslationTaskQueueState.QUEUED)
        compose.setContent {
            NativeTheme {
                NativeTranslationQueueDialog(
                    load = { queued }, ref = ref, resume = {}, cancel = {},
                    start = {}, pause = {}, review = {}, dismiss = {},
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-queue-start-run").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertTrue(compose.onAllNodesWithTag("native-queue-review-run").fetchSemanticsNodes().isEmpty())
        }
    }

    private fun snapshot(state: TranslationTaskQueueState): TranslationQueueSnapshot {
        val queued = (TranslationTaskQueue.empty().enqueue(spec.queueIdentity())
            as TranslationQueueResult.Applied).queue
        val queue = when (state) {
            TranslationTaskQueueState.QUEUED -> queued
            TranslationTaskQueueState.RUNNING ->
                (queued.dispatchNext() as TranslationQueueResult.Applied).queue
            TranslationTaskQueueState.PAUSED -> {
                val running = (queued.dispatchNext() as TranslationQueueResult.Applied).queue
                (running.pause(spec.queueIdentity(), CommandId("pause")) as TranslationQueueResult.Applied).queue
            }
            TranslationTaskQueueState.NEEDS_REVIEW, TranslationTaskQueueState.FAILED -> {
                val running = (queued.dispatchNext() as TranslationQueueResult.Applied).queue
                val withFailure = (running.recordFailure(spec.queueIdentity(), failureBatch())
                    as TranslationQueueResult.Applied).queue
                if (state == TranslationTaskQueueState.NEEDS_REVIEW) withFailure
                else (withFailure.keepError(spec.queueIdentity(), CommandId("keep-errors"),
                    failureBatch().id, listOf(ItemId(1))) as TranslationQueueResult.Applied).queue
            }
            else -> error("Unsupported fixture state $state")
        }
        return TranslationQueueSnapshot.of(
            ref = ref,
            revision = 1,
            queue = queue,
            specs = listOf(spec),
            metas = listOf(TranslationQueueTaskMeta(1, 1)),
            updatedAtMillis = 1,
        )
    }

    private fun failureBatch() = TranslationTaskFailureBatch(
        TranslationTaskFailureBatchId("batch-1"),
        listOf(TranslationFailureMember(ItemId(1), ObjectFingerprint("a".repeat(64)),
            ModelFailureKind.NETWORK, TranslationFailureReceipt.RECEIVED, UsageSource.ACTUAL)),
    )
}
