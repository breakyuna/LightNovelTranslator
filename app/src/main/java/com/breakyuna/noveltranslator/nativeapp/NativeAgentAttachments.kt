package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.AgentAttachmentLimits
import com.breakyuna.noveltranslator.nativecore.domain.AttachmentId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** One saved attachment waiting for the next send; the URI is only a local preview source. */
data class PendingAgentAttachment(
    val id: AttachmentId,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
)

enum class NativeAgentAttachmentFailureReason {
    UNSUPPORTED_TYPE,
    TOO_LARGE,
    LIMIT,
    UNREADABLE,
    SAVE_FAILED,
}

data class NativeAgentAttachmentFailure(
    val displayName: String,
    val reason: NativeAgentAttachmentFailureReason,
)

/** Implemented by the project ViewModel; kept out of Global state and discarded with the project. */
interface NativeAgentAttachmentActions {
    fun add(uris: List<Uri>)
    fun remove(id: AttachmentId)
    fun clearFailures()
}

/** Pure send gate shared by the input bar and ViewModel: text-only or image-only is valid. */
internal fun canSendAgentMessage(
    ready: Boolean,
    hasApproval: Boolean,
    text: String,
    attachmentCount: Int,
): Boolean = ready && !hasApproval && text.length <= 8000 &&
    (text.isNotBlank() || attachmentCount > 0)

/** Pure admission check for the per-message count and the protocol's total payload budget. */
internal fun agentAttachmentAdmissionFailure(
    currentCount: Int,
    currentBytes: Long,
    candidateBytes: Int,
): NativeAgentAttachmentFailureReason? = when {
    candidateBytes <= 0 -> NativeAgentAttachmentFailureReason.UNSUPPORTED_TYPE
    currentCount >= AgentAttachmentLimits.MAX_PER_MESSAGE -> NativeAgentAttachmentFailureReason.LIMIT
    currentBytes + candidateBytes.toLong() > AgentAttachmentLimits.MAX_TOTAL_MESSAGE_BYTES ->
        NativeAgentAttachmentFailureReason.LIMIT
    else -> null
}

internal fun formatAgentAttachmentSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1_048_576 -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MiB", bytes / 1_048_576.0)
}

@Composable
internal fun NativeAgentAttachmentButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag("native-agent-attachment-add"),
    ) {
        Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.native_agent_attachment_add))
    }
}

/** Name/size/remove list plus per-item failures; bounded to the four-attachment message limit. */
@Composable
internal fun NativeAgentAttachmentList(
    attachments: List<PendingAgentAttachment>,
    failures: List<NativeAgentAttachmentFailure>,
    enabled: Boolean,
    actions: NativeAgentAttachmentActions?,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty() && failures.isEmpty()) return
    val activeActions = actions
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.native_agent_attachment_count,
                        attachments.size,
                        AgentAttachmentLimits.MAX_PER_MESSAGE,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (failures.isNotEmpty() && activeActions != null) {
                    TextButton(onClick = { activeActions.clearFailures() }, enabled = enabled) {
                        Text(stringResource(R.string.native_agent_attachment_dismiss))
                    }
                }
            }
            attachments.forEach { attachment ->
                key(attachment.id.value) {
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("native-agent-attachment-row"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NativeAgentAttachmentThumbnail(attachment)
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = attachment.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatAgentAttachmentSize(attachment.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (activeActions != null) {
                            IconButton(
                                onClick = { activeActions.remove(attachment.id) },
                                enabled = enabled,
                                modifier = Modifier.testTag("native-agent-attachment-remove"),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(
                                        R.string.native_agent_attachment_remove,
                                        attachment.displayName,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            failures.forEach { failure ->
                Text(
                    text = stringResource(failureText(failure.reason), failure.displayName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun NativeAgentAttachmentThumbnail(attachment: PendingAgentAttachment) {
    val context = LocalContext.current
    val preview by produceState<ImageBitmap?>(initialValue = null, key1 = attachment.uri) {
        value = withContext(Dispatchers.IO) { loadAttachmentThumbnail(context, attachment.uri) }
    }
    val shape = RoundedCornerShape(8.dp)
    val image = preview
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = stringResource(
                R.string.native_agent_attachment_preview,
                attachment.displayName,
            ),
            modifier = Modifier.size(44.dp).clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier.size(44.dp).clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.AttachFile,
                contentDescription = stringResource(
                    R.string.native_agent_attachment_preview,
                    attachment.displayName,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Bounded local preview only; decode failures show the generic attachment glyph. */
private fun loadAttachmentThumbnail(context: Context, uri: Uri): ImageBitmap? = try {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth > 0 && bounds.outHeight > 0 &&
        (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256)) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
} catch (_: Exception) {
    null
}

private fun failureText(reason: NativeAgentAttachmentFailureReason): Int = when (reason) {
    NativeAgentAttachmentFailureReason.UNSUPPORTED_TYPE -> R.string.native_agent_attachment_error_type
    NativeAgentAttachmentFailureReason.TOO_LARGE -> R.string.native_agent_attachment_error_size
    NativeAgentAttachmentFailureReason.LIMIT -> R.string.native_agent_attachment_error_limit
    NativeAgentAttachmentFailureReason.UNREADABLE -> R.string.native_agent_attachment_error_unreadable
    NativeAgentAttachmentFailureReason.SAVE_FAILED -> R.string.native_agent_attachment_error_save
}
