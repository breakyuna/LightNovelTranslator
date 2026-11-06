package com.breakyuna.noveltranslator.nativeapp

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Cross-process drain lease for the translation queue. The single-process coordinator and its
 * JSON CAS are authoritative inside one process; this advisory file lock prevents a second
 * application process (for example a restored task/activity process) from claiming and launching
 * the same queue head while another process is already draining.
 *
 * The lock is intentionally advisory and short-lived: it covers only one recover/drain/start
 * pass. It carries no paid-request authorization and is released before the process can sleep.
 *
 * Ownership is explicit: [tryAcquire] returns an opaque [Token], and only that token can release
 * the lease. A failed acquisition returns null and must never call [release], otherwise a second
 * caller would drop the live holder's file lock (the earlier boolean API made that mistake
 * unavoidable). The monitor below is not thread-owned, so acquisition and release may run on
 * different dispatchers.
 */
internal class TranslationQueueProcessLease(private val file: File) {
    /** Opaque proof that the caller owns the lease; [generation] rejects a stale token. */
    class Token internal constructor(internal val generation: Long)

    private val gate = Any()
    private var generation = 0L
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /** Non-blocking: null means another process or another drain in this process owns the pass. */
    fun tryAcquire(): Token? = synchronized(gate) {
        if (lock != null) return null
        val opened = try {
            // RandomAccessFile is API 1; java.nio.file.FileChannel.open is API 26+ and this app
            // still supports API 24.
            RandomAccessFile(file, "rw").channel
        } catch (_: Exception) {
            return null
        }
        val acquired = try {
            opened.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: Exception) {
            null
        }
        if (acquired == null) {
            try { opened.close() } catch (_: Exception) { /* Acquisition already failed. */ }
            return null
        }
        generation += 1
        channel = opened
        lock = acquired
        Token(generation)
    }

    /** Releases only the lease identified by [token]; a stale or foreign token is ignored. */
    fun release(token: Token) {
        synchronized(gate) {
            if (token.generation != generation || lock == null) return
            try { lock?.release() } catch (_: Exception) { /* The process may already be exiting. */ }
            try { channel?.close() } catch (_: Exception) { /* Best effort at process death. */ }
            lock = null
            channel = null
        }
    }
}
