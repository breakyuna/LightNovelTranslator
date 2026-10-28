package com.breakyuna.noveltranslator.nativeapp

import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Ordered private draft IO. Draft persistence never enqueues or dispatches a model request. */
internal class NativeAgentComposerDrafts(root: File, scope: CoroutineScope, failed: (LocalProjectRef, ChatId) -> Unit) {
    private sealed interface Command {
        data object Flush : Command
        data class Save(val ref: LocalProjectRef, val chat: ChatId, val text: String) : Command
        data class Load(val ref: LocalProjectRef, val chat: ChatId, val result: CompletableDeferred<String>) : Command
    }
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val pending = linkedMapOf<Pair<LocalProjectRef, ChatId>, Command.Save>()
    private val monitor = Any()
    init {
        scope.launch(Dispatchers.IO) {
            for (next in commands) {
                val batch = when (next) {
                    Command.Flush -> synchronized(monitor) { pending.values.toList().also { pending.clear() } }
                    else -> listOf(next)
                }
                for (command in batch) {
                    val ref = when (command) { is Command.Save -> command.ref; is Command.Load -> command.ref; Command.Flush -> error("Unexpected flush") }
                    val chat = when (command) { is Command.Save -> command.chat; is Command.Load -> command.chat; Command.Flush -> error("Unexpected flush") }
                    val identity = listOf(ref.projectId.value, ref.instanceId.value, ref.generation.value.toString(), chat.value)
                        .joinToString("") { "${it.length}:$it" }
                    val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    val file = AtomicFile(File(root, "$hash.txt"))
                    when (command) {
                        Command.Flush -> error("Unexpected flush")
                        is Command.Save -> try {
                            require(command.text.length <= 8000)
                            check(root.isDirectory || root.mkdirs())
                            if (command.text.isEmpty()) file.delete() else {
                                val output = file.startWrite()
                                try { output.write(TextProjectFingerprint.utf8(command.text)); file.finishWrite(output) }
                                catch (error: Exception) { file.failWrite(output); throw error }
                            }
                        } catch (_: Exception) { withContext(Dispatchers.Main.immediate) { failed(ref, chat) } }
                        is Command.Load -> {
                            val text = try {
                                file.openRead().use { input ->
                                    val bytes = input.readBytesBounded(32_000)
                                    bytes.toString(Charsets.UTF_8).takeIf { it.length <= 8000 }.orEmpty()
                                }
                            } catch (_: Exception) { "" }
                            command.result.complete(text)
                        }
                    }
                }
            }
        }.invokeOnCompletion { commands.cancel() }
    }
    fun save(ref: LocalProjectRef, chat: ChatId, text: String) {
        require(text.length <= 8000)
        synchronized(monitor) {
            val wake = pending.isEmpty()
            pending[ref to chat] = Command.Save(ref, chat, text)
            if (wake) commands.trySend(Command.Flush)
        }
    }
    suspend fun load(ref: LocalProjectRef, chat: ChatId): String {
        val result = CompletableDeferred<String>()
        synchronized(monitor) {
            check(commands.trySend(Command.Load(ref, chat, result)).isSuccess)
        }
        return result.await()
    }
}

private fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        require(output.size() + count <= max)
        output.write(buffer, 0, count)
    }
}
