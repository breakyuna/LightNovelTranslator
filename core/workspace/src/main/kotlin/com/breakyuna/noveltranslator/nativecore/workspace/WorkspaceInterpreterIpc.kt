package com.breakyuna.noveltranslator.nativecore.workspace

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean

/** Only bounded UTF-8 and descriptor handles cross Binder; no project paths or credentials. */
internal object WorkspaceInterpreterIpc {
    const val ENGINE = "lnt.workspace.interpreter.v1"
    const val HOST = "lnt.workspace.host.v1"
    const val EXECUTE = IBinder.FIRST_CALL_TRANSACTION
    const val TERMINATE = IBinder.FIRST_CALL_TRANSACTION + 1
    const val READ = IBinder.FIRST_CALL_TRANSACTION
    const val LOG = READ + 1
    const val CHANGE = READ + 2
    const val ARTIFACT = READ + 3
    const val SEARCH = READ + 4
    const val FETCH = READ + 5
    const val PRINT = READ + 6
    const val MAX_TEXT_BYTES = 128L * 1024 * 1024

    fun readText(parcel: Parcel, limit: Long = MAX_TEXT_BYTES,
                 opened: (ParcelFileDescriptor) -> Unit = {},
                 closed: (ParcelFileDescriptor) -> Unit = {}): String {
        val expectedBytes = parcel.readLong()
        val descriptor = ParcelFileDescriptor.CREATOR.createFromParcel(parcel)
        try {
            require(expectedBytes in 0..limit) { "Workspace IPC payload exceeds budget" }
            opened(descriptor)
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= expectedBytes) { "Workspace IPC payload exceeds budget" }
                    output.write(buffer, 0, count)
                }
                require(total == expectedBytes) { "Incomplete workspace IPC payload" }
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray())).toString()
            }
        } finally {
            runCatching { descriptor.close() }
            closed(descriptor)
        }
    }
}

/** Tracks pipe endpoints so cancellation releases blocked senders and receivers as well. */
internal class WorkspaceInterpreterPipes : AutoCloseable {
    private val closed = AtomicBoolean()
    private val descriptors = HashSet<ParcelFileDescriptor>()

    fun readText(parcel: Parcel, limit: Long = WorkspaceInterpreterIpc.MAX_TEXT_BYTES): String =
        WorkspaceInterpreterIpc.readText(parcel, limit, opened = { descriptor ->
            synchronized(descriptors) {
                check(!closed.get()) { "Workspace IPC closed" }
                check(descriptors.size < 16) { "Too many workspace IPC streams" }
                descriptors.add(descriptor)
            }
        }, closed = { release(it) })

    fun writeText(parcel: Parcel, text: String) {
        requireWellFormedUnicode(text)
        require(text.length.toLong() <= WorkspaceInterpreterIpc.MAX_TEXT_BYTES)
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= WorkspaceInterpreterIpc.MAX_TEXT_BYTES)
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            synchronized(descriptors) {
                check(!closed.get()) { "Workspace IPC closed" }
                check(descriptors.size < 15) { "Too many workspace IPC streams" }
                descriptors.addAll(pipe)
            }
        } catch (failure: Exception) { pipe.forEach { runCatching { it.close() } }; throw failure }
        try {
            parcel.writeLong(bytes.size.toLong())
            pipe[0].writeToParcel(parcel, 0)
        } finally {
            release(pipe[0])
        }
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
            } catch (_: Exception) {
                // The receiving process may have exited; the supervising call owns the error.
            } finally { release(pipe[1]) }
        }, "lnt-workspace-pipe").apply { isDaemon = true; start() }
    }

    private fun release(descriptor: ParcelFileDescriptor) {
        synchronized(descriptors) { descriptors.remove(descriptor) }
        runCatching { descriptor.close() }
    }

    override fun close() {
        synchronized(descriptors) {
            closed.set(true)
            descriptors.forEach { runCatching { it.close() } }
            descriptors.clear()
        }
    }
}
