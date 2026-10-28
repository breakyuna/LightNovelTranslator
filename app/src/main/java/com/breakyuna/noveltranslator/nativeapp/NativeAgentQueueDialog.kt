package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.AgentInputQueue
import com.breakyuna.noveltranslator.nativecore.domain.CommandId

/**
 * Durable pending messages for the current Chat; independent of the automatic composer draft. "Use" moves one draft back into the
 * composer; it never appends history or starts a paid request. Pausing only blocks the future
 * automatic queue consumer, not manual editing/deletion.
 */
@Composable
internal fun NativeAgentQueueDialog(queue: AgentInputQueue, busy: Boolean, dismiss: () -> Unit,
                                    use: (CommandId) -> Unit, delete: (CommandId) -> Unit,
                                    togglePause: () -> Unit, sendNext: () -> Unit) {
    NativeContentDialog(onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.native_agent_queue_title)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.native_agent_queue_count, queue.drafts.size), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = togglePause, enabled = !busy,
                        modifier = Modifier.testTag("native-agent-queue-pause")) {
                        Text(stringResource(if (queue.paused) R.string.native_agent_queue_resume else R.string.native_agent_queue_pause))
                    }
                    TextButton(onClick = sendNext,
                        enabled = !busy && !queue.paused && queue.pendingAppend == null && queue.drafts.isNotEmpty(),
                        modifier = Modifier.testTag("native-agent-queue-send")) {
                        Text(stringResource(R.string.native_agent_queue_send))
                    }
                }
                if (queue.drafts.isEmpty()) Text(stringResource(R.string.native_agent_queue_empty))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(queue.drafts, key = { it.id.value }) { draft ->
                        Card(Modifier.fillMaxWidth().testTag("native-agent-queue-${draft.id.value}")) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(draft.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { use(draft.id) }, enabled = !busy) {
                                        Text(stringResource(R.string.native_agent_queue_use))
                                    }
                                    TextButton(onClick = { delete(draft.id) }, enabled = !busy) {
                                        Text(stringResource(R.string.native_agent_queue_delete))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) } })
}
