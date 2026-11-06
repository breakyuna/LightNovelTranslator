package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R

private val LocalNativeInlinePanel = staticCompositionLocalOf { false }

/** Page-owned content retains navigation and does not create another window. */
@Composable
internal fun NativePanelHost(inline: Boolean, onDismissRequest: () -> Unit,
    content: @Composable () -> Unit) {
    if (inline) {
        BackHandler(onBack = onDismissRequest)
        CompositionLocalProvider(LocalNativeInlinePanel provides true) { content() }
    } else {
        Dialog(onDismissRequest = onDismissRequest,
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            CompositionLocalProvider(LocalNativeInlinePanel provides false) { content() }
        }
    }
}

internal enum class NativeTone {
    NEUTRAL,
    PRIMARY,
    SECONDARY,
    SUCCESS,
    WARNING,
    ERROR,
}

/** Content only: the caller keeps one Dialog window alive while changing its active page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeDialogSurface(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    val inline = LocalNativeInlinePanel.current
    Box(if (inline) Modifier.fillMaxWidth() else Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = modifier
                .padding(if (inline) 0.dp else 16.dp)
                .widthIn(max = 580.dp)
                .fillMaxWidth()
                .heightIn(max = if (inline) 440.dp else 640.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (inline) 12.dp else 20.dp, vertical = if (inline) 8.dp else 16.dp),
                ) {
                    ProvideTextStyle(if (inline) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall, title)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(
                    Modifier
                        .weight(1f, fill = false)
                        .padding(horizontal = if (inline) 12.dp else 20.dp, vertical = if (inline) 8.dp else 14.dp),
                ) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium, text)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FlowRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

/** Shared bounded window for content-heavy dialogs. Footer actions never join the scroller. */
@Composable
internal fun NativeContentDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    inline: Boolean = false,
) {
    NativePanelHost(inline, onDismissRequest) {
        NativeDialogSurface(modifier = modifier, title = { title?.invoke() }, text = { text?.invoke() },
            confirmButton = confirmButton, dismissButton = dismissButton)
    }
}

@Composable
internal fun NativeBusyIndicator(modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
        Text(stringResource(R.string.native_working), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun NativeStatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: NativeTone = NativeTone.NEUTRAL,
) {
    val scheme = MaterialTheme.colorScheme
    val statusColors = LocalNativeStatusColors.current
    val (bg, fg, border) = when (tone) {
        NativeTone.PRIMARY -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary.copy(alpha = 0.35f))
        NativeTone.SECONDARY -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.secondary.copy(alpha = 0.35f))
        NativeTone.SUCCESS -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary.copy(alpha = 0.35f))
        NativeTone.WARNING -> Triple(statusColors.warningContainer, statusColors.onWarningContainer, statusColors.warning.copy(alpha = 0.4f))
        NativeTone.ERROR -> Triple(scheme.errorContainer, scheme.onErrorContainer, scheme.error.copy(alpha = 0.4f))
        NativeTone.NEUTRAL -> Triple(scheme.surfaceContainerHigh, scheme.onSurfaceVariant, scheme.outlineVariant)
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = bg,
        contentColor = fg,
        border = BorderStroke(1.dp, border),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
internal fun NativeSectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    badge: String? = null,
    badgeTone: NativeTone = NativeTone.NEUTRAL,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title != null || subtitle != null || badge != null || headerAction != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (title != null) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    headerAction?.invoke()
                }
            }
            if (badge != null) NativeStatusBadge(text = badge, tone = badgeTone)
            content()
        }
    }
}

@Composable
internal fun NativeBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: NativeTone = NativeTone.SECONDARY,
    action: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val statusColors = LocalNativeStatusColors.current
    val (bg, fg, border) = when (tone) {
        NativeTone.PRIMARY -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary.copy(alpha = 0.4f))
        NativeTone.SECONDARY -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.secondary.copy(alpha = 0.4f))
        NativeTone.SUCCESS -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary.copy(alpha = 0.4f))
        NativeTone.WARNING -> Triple(statusColors.warningContainer, statusColors.onWarningContainer, statusColors.warning.copy(alpha = 0.4f))
        NativeTone.ERROR -> Triple(scheme.errorContainer, scheme.onErrorContainer, scheme.error.copy(alpha = 0.45f))
        NativeTone.NEUTRAL -> Triple(scheme.surfaceContainer, scheme.onSurfaceVariant, scheme.outlineVariant)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = bg,
        contentColor = fg,
        border = BorderStroke(1.dp, border),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = fg,
                modifier = Modifier.fillMaxWidth(),
            )
            action?.invoke()
        }
    }
}

@Composable
internal fun NativeSubpageHeader(
    title: String,
    subtitle: String? = null,
    backTag: String? = null,
    backEnabled: Boolean = true,
    onBack: () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = onBack,
                enabled = backEnabled,
                modifier = if (backTag != null) Modifier.testTag(backTag) else Modifier,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.native_back),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
    }
}
