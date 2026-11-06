package com.breakyuna.noveltranslator.ui.screens.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.core.agent.koog.AgentMessage
import com.breakyuna.noveltranslator.core.agent.koog.AgentRole

/** 展开原文时的最大高度，超出后内部滚动，避免把对话流撑爆。 */
private val RawMaxHeight = 180.dp

/**
 * 工具调用 / 工具回执的紧凑摘要行。
 *
 * 协议原文（含 JSON）只以一行摘要呈现（"调用 list_books" / "list_books 已返回"），
 * 点击后才展开等宽小字的原文（限高 + 内部滚动 + 可复制）；失败回执使用 error 色调。
 * 这样对话流里不会出现大段原始 JSON，模型不配合时也能看清是哪一步出了问题。
 */
@Composable
fun AgentToolRow(
    message: AgentMessage,
    strings: AgentChatText,
    onCopyRaw: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isCall = message.role == AgentRole.TOOL_CALL
    val isError = !isCall && message.isError
    val toolName = message.toolName?.takeIf { it.isNotBlank() } ?: strings.toolUnknownLabel
    val summary = when {
        isCall -> strings.toolCallSummaryTemplate.replace("{tool}", toolName)
        isError -> strings.toolResultFailedSummaryTemplate.replace("{tool}", toolName)
        else -> strings.toolResultSummaryTemplate.replace("{tool}", toolName)
    }
    // 协议块原文只在展开时读取；持久化记录优先用结构化字段，旧记录回退到 content。
    val rawText = if (isCall) {
        message.toolArgsJson ?: message.content
    } else {
        message.toolResultJson ?: message.content
    }
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }

    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = containerColor
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        isCall -> Icons.Outlined.Build
                        isError -> Icons.Outlined.ErrorOutline
                        else -> Icons.Outlined.CheckCircle
                    },
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) strings.collapseRaw else strings.expandRaw,
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isCall) strings.toolArgsTitle else strings.toolResultTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.8f)
                )
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = rawText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .heightIn(max = RawMaxHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = strings.rawCharsTemplate.replace("{count}", rawText.length.toString()),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onCopyRaw(rawText) }) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = strings.copyRaw, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
