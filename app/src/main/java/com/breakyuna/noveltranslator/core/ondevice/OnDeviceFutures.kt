package com.breakyuna.noveltranslator.core.ondevice

import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

private val DIRECT_EXECUTOR = Executor { command -> command.run() }

/**
 * 将 ML Kit 的 [ListenableFuture] 桥接为挂起函数。
 *
 * ML Kit GenAI 的 Summarization / Proofreading 是 ListenableFuture 风格 API；
 * Kotlin 文档中的 `.await()` 来自传递依赖 kotlinx-coroutines-guava，这里自建桥接，
 * 避免直接依赖传递依赖，并保证协程取消时取消底层 future。
 */
internal suspend fun <T> ListenableFuture<T>.awaitOnDevice(): T = suspendCancellableCoroutine { continuation ->
    addListener(
        {
            try {
                val value = get()
                if (value == null) {
                    continuation.resumeWithException(IllegalStateException("ML Kit GenAI returned a null result"))
                } else {
                    continuation.resume(value)
                }
            } catch (e: CancellationException) {
                continuation.cancel(e)
            } catch (e: Throwable) {
                continuation.resumeWithException(e)
            }
        },
        DIRECT_EXECUTOR
    )
    continuation.invokeOnCancellation { cancel(false) }
}

/**
 * 确保特性可用：AVAILABLE 直接返回；DOWNLOADABLE 时先下载再返回 AVAILABLE；
 * DOWNLOADING 时直接返回（官方文档：下载中发起推理会在下载完成后自动继续执行）；
 * UNAVAILABLE 原样返回，由调用方分类处理。
 *
 * @param checkStatus 挂起获取当前特性状态
 * @param startDownload 触发下载（非挂起，通过 [DownloadCallback] 回调完成/失败）
 */
internal suspend fun ensureFeatureReady(
    checkStatus: suspend () -> Int,
    startDownload: (DownloadCallback) -> Unit
): Int {
    val status = checkStatus()
    if (status != FeatureStatus.DOWNLOADABLE) return status
    return suspendCancellableCoroutine { continuation ->
        val callback = object : DownloadCallback {
            override fun onDownloadStarted(bytesToDownload: Long) = Unit

            override fun onDownloadProgress(totalBytesDownloaded: Long) = Unit

            override fun onDownloadCompleted() {
                if (continuation.isActive) continuation.resume(FeatureStatus.AVAILABLE)
            }

            override fun onDownloadFailed(e: GenAiException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
        try {
            startDownload(callback)
        } catch (t: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(t)
        }
    }
}
