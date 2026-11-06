package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R

@Composable
internal fun NativeAgentActivityIndicator(tone: NativeTone, description: String) {
    val color = when (tone) {
        NativeTone.SUCCESS -> Color(0xff22c55e)
        NativeTone.ERROR -> MaterialTheme.colorScheme.error
        NativeTone.PRIMARY -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    when (tone) {
        NativeTone.SUCCESS -> Box(Modifier.size(9.dp).background(color, CircleShape)
            .semantics { contentDescription = description })
        NativeTone.ERROR -> Icon(Icons.Default.ErrorOutline, description, Modifier.size(15.dp), tint = color)
        else -> Box(Modifier.size(9.dp).border(1.5.dp, color, CircleShape)
            .semantics { contentDescription = description })
    }
}

/** Thinking is a shaded disclosure; tool receipts remain plain rows on the conversation surface. */
@Composable
internal fun NativeAgentActivityHeader(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tool: Boolean = false,
    tone: NativeTone = NativeTone.NEUTRAL,
    status: String? = null,
) {
    val expansion = stringResource(if (expanded) R.string.native_agent_details_expanded else R.string.native_agent_details_collapsed)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (tool) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { stateDescription = expansion }
                .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (tool) Icons.Default.Build else if (expanded) Icons.Default.ArrowDropDown else Icons.Default.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = if (tool) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    else MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (status != null) {
                if (tool && tone == NativeTone.ERROR) Text(status, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
                NativeAgentActivityIndicator(tone, status)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeAgentMessageActions(
    copied: Boolean,
    copyEnabled: Boolean,
    copy: () -> Unit,
    modifier: Modifier = Modifier,
    branch: (() -> Unit)? = null,
    branchEnabled: Boolean = true,
    alignEnd: Boolean = false,
    detailsLabel: String? = null,
    detailsEnabled: Boolean = true,
    details: () -> Unit = {},
) {
    val colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp, if (alignEnd) Alignment.End else Alignment.Start)) {
        TextButton(onClick = copy, enabled = copyEnabled, colors = colors,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(if (copied) R.string.native_copied else R.string.native_copy_message))
        }
        if (branch != null) TextButton(onClick = branch, enabled = branchEnabled, colors = colors,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            Icon(Icons.Default.CallSplit, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.native_agent_branch_action))
        }
        if (detailsLabel != null) TextButton(onClick = details, enabled = detailsEnabled, colors = colors,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            Text(detailsLabel)
        }
    }
}

@Composable
internal fun NativeAgentMessageFoldout(title: String, modifier: Modifier = Modifier, stateKey: Any? = title,
    forceExpanded: Boolean = false, tone: NativeTone = NativeTone.NEUTRAL, status: String? = null,
    content: @Composable () -> Unit) {
    var expanded by rememberSaveable(stateKey, title) { mutableStateOf(false) }
    LaunchedEffect(forceExpanded) { if (forceExpanded) expanded = true }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NativeAgentActivityHeader(title, expanded, { expanded = !expanded }, tone = tone, status = status)
        if (expanded) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) { content() }
    }
}
