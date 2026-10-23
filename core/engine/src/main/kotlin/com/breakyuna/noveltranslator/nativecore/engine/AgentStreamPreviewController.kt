package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Ephemeral, bounded presentation facts; never put this object into saved state or a database. */
enum class AgentPreviewPhase { RECEIVING, RECORDING, INTERRUPTED }
class AgentStreamPreview(val ref: LocalProjectRef, val key: AgentRequestKey, val text: String,
    val thinking: String, val toolCalls: Int, val clipped: Boolean, val phase: AgentPreviewPhase, val messageId: MessageId?)
class AgentStreamPreviewState(val revision: Long, val preview: AgentStreamPreview?)

/** One active execution token; stale deltas/cleanup cannot replace a subsequent execution's preview. */
class AgentStreamPreviewController(private val clock: () -> Long = System::nanoTime) {
    private val monitor = Any()
    private var revision = 0L
    private var active: Session? = null
    private val mutable = MutableStateFlow(AgentStreamPreviewState(0, null))
    val state = mutable.asStateFlow()
    private fun next(preview: AgentStreamPreview?) = AgentStreamPreviewState(++revision, preview)
    private fun publish(value: AgentStreamPreviewState) { mutable.update { if (value.revision > it.revision) value else it } }

    fun open(ref: LocalProjectRef, run: RunIdentity): Session {
        require(ref.projectId == run.projectId && run.chatId != null)
        val session = Session(ref, run)
        val value = synchronized(monitor) { active = session; next(null) }
        publish(value)
        return session
    }
    fun clear() { val value = synchronized(monitor) { active = null; next(null) }; publish(value) }
    private fun change(session: Session, block: () -> AgentStreamPreviewState?) {
        val value = synchronized(monitor) { if (active === session) block() else null }
        if (value != null) publish(value)
    }

    inner class Session internal constructor(private val ref: LocalProjectRef, private val run: RunIdentity) : AgentEventSink {
        private var key: AgentRequestKey? = null
        private val finished = mutableSetOf<AgentRequestKey>()
        private val text = StringBuilder()
        private val thinking = StringBuilder()
        private val tools = mutableSetOf<Int>()
        private var clipped = false
        private var accepting = false
        private var message: MessageId? = null
        private var phase = AgentPreviewPhase.RECEIVING
        private var lastPublished: Long? = null
        private fun snapshot() = AgentStreamPreview(ref, requireNotNull(key), text.toString(), thinking.toString(), tools.size, clipped, phase, message)
        private fun append(builder: StringBuilder, value: String) {
            if (value.length > 8192) { builder.setLength(0); builder.append(value.takeLast(8192)); clipped = true }
            else builder.append(value)
            if (builder.length > 8192) { builder.delete(0, builder.length - 8192); clipped = true }
            if (builder.isNotEmpty() && builder[0].isLowSurrogate()) { builder.deleteCharAt(0); clipped = true }
        }
        override fun previewStarted(key: AgentRequestKey) = change(this) {
            if (key.run != run || key in finished || this.key != null) return@change null
            this.key = key; text.setLength(0); thinking.setLength(0); tools.clear()
            clipped = false; message = null; accepting = true; phase = AgentPreviewPhase.RECEIVING; lastPublished = null
            next(snapshot())
        }
        override fun preview(key: AgentRequestKey, event: AgentModelEvent) = change(this) {
            if (this.key != key || !accepting) return@change null
            when (event) {
                is AgentModelEvent.TextDelta -> append(text, event.text)
                is AgentModelEvent.ThinkingDelta -> append(thinking, event.text)
                is AgentModelEvent.ToolCallDelta -> if (event.index in 0..7) tools.add(event.index)
            }
            val now = clock(); val previous = lastPublished
            if (previous != null && now - previous < 50_000_000) return@change null
            lastPublished = now
            next(snapshot())
        }
        override fun previewCompleted(key: AgentRequestKey, message: MessageId) = change(this) {
            if (this.key != key) return@change null
            this.message = message; accepting = false; phase = AgentPreviewPhase.RECORDING
            next(snapshot())
        }
        override fun previewFinished(key: AgentRequestKey) = change(this) {
            finished.add(key)
            if (this.key != key) return@change null
            accepting = false
            phase = if (message == null) AgentPreviewPhase.INTERRUPTED else AgentPreviewPhase.RECORDING
            next(snapshot())
        }
        override fun previewCommitted(key: AgentRequestKey) = change(this) {
            if (this.key != key) return@change null
            finished.add(key); this.key = null; accepting = false
            text.setLength(0); thinking.setLength(0); tools.clear()
            next(null)
        }
        fun close(keepPartial: Boolean = false) = change(this) {
            active = null; accepting = false
            if (keepPartial && key != null && (text.isNotEmpty() || thinking.isNotEmpty() || tools.isNotEmpty())) {
                phase = AgentPreviewPhase.INTERRUPTED
                next(snapshot())
            } else next(null)
        }
    }
}
