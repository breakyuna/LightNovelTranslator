package com.breakyuna.noveltranslator.nativeapp

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.breakyuna.noveltranslator.TranslatorApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Durable wake-up for the translation queue.
 *
 * The receiver no longer waits inside `goAsync`; boot/package-replace, queue writes, recovery and
 * continuation only enqueue this single persisted job. The job takes the same OS file lease as an
 * in-process drain, replays durable control intents through recovery, and claims only explicit
 * QUEUED tasks. A lease held by another process is a successful skip. The job reschedules itself
 * only while a QUEUED task remains and no execution is active.
 */
class NativeTranslationQueueWake : JobService() {
    private var scope = newScope()
    private val stopped = AtomicBoolean(false)

    override fun onStartJob(params: JobParameters): Boolean {
        stopped.set(false)
        if (!scope.isActive) scope = newScope()
        val app = applicationContext as? TranslatorApplication ?: return false
        scope.launch {
            var retry = false
            try {
                val result = app.nativeGraph.translationQueueScheduler.wake()
                if (result == NativeTranslationQueueWakeResult.QUEUED) {
                    schedule(this@NativeTranslationQueueWake, RESCHEDULE_DELAY_MILLIS)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A corrupt/unreadable queue is retried with the platform's backoff instead of
                // being treated as an idle queue. No paid request is started by this retry.
                retry = true
            } finally {
                if (!stopped.get()) jobFinished(params, retry)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopped.set(true)
        scope.cancel()
        // The platform stopped this pass before it could prove completion. Ask for a bounded
        // retry; recovery still only classifies durable facts and never replays old authorization.
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val JOB_ID = 6104
        internal const val RESCHEDULE_DELAY_MILLIS = 15_000L

        /** Idempotent: the platform keeps one pending job per id and replaces its parameters. */
        fun schedule(context: Context) {
            schedule(context, 0L)
        }

        fun schedule(context: Context, delayMillis: Long) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val component = ComponentName(context, NativeTranslationQueueWake::class.java)
            try {
                val job = JobInfo.Builder(JOB_ID, component)
                    .setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .setMinimumLatency(delayMillis.coerceAtLeast(0L))
                    .build()
                scheduler.schedule(job)
            } catch (_: Exception) {
                // Scheduling is a durability aid; the foreground drain remains authoritative.
            }
        }

        fun cancel(context: Context) {
            (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler)?.cancel(JOB_ID)
        }
    }
}

/**
 * Pure wake policy shared with tests: only an explicit QUEUED task can start work, and another
 * scheduled pass is useful only while such a task remains with no live execution.
 */
internal object NativeTranslationQueueWakePolicy {
    fun isClaimableState(state: com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState): Boolean =
        state == com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState.QUEUED

    fun hasQueued(tasks: Iterable<com.breakyuna.noveltranslator.nativecore.domain.TranslationQueuedTask>): Boolean =
        tasks.any { isClaimableState(it.state) }

    fun hasQueuedStates(
        states: Iterable<com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState>,
    ): Boolean = states.any(::isClaimableState)

    fun shouldReschedule(queuedRemaining: Boolean, activeExecution: Boolean): Boolean =
        queuedRemaining && !activeExecution
}
