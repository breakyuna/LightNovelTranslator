package com.breakyuna.noveltranslator.ui.screens.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.breakyuna.noveltranslator.core.translation.TranslationContinuity
import com.breakyuna.noveltranslator.data.model.TranslationMode
import com.breakyuna.noveltranslator.data.model.TranslationProjectV2Entity
import com.breakyuna.noveltranslator.ui.components.apple.AppDivider
import com.breakyuna.noveltranslator.ui.components.apple.AppPrimaryButton
import com.breakyuna.noveltranslator.ui.components.apple.AppSection
import com.breakyuna.noveltranslator.ui.components.apple.AppSegmentedControl
import com.breakyuna.noveltranslator.ui.components.apple.AppSettingsRow
import com.breakyuna.noveltranslator.ui.i18n.EngineConfigStrings
import com.breakyuna.noveltranslator.ui.theme.AccentPurple
import com.breakyuna.noveltranslator.ui.theme.Spacing
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel

/** Maximum in-book concurrency; the engine clamps anything above this back to serial. */
const val ENGINE_MAX_PARALLEL_CHAPTERS = 5

/**
 * The four engine switches of a translation project, as an editable draft.
 *
 * Kept as a plain value object so the workbench can hand the same selection to
 * [AppViewModel.configureEditionTranslation] when the project does not exist yet
 * and to [AppViewModel.updateTranslationProjectConfig] when it does.
 */
data class EngineConfigDraft(
    val parallelChapters: Int = 1,
    val continuityMode: String = TranslationContinuity.SOURCE_TAIL,
    val metadataEnabled: Boolean = false,
    val promptCacheEnabled: Boolean = true
) {
    companion object {
        fun fromProject(project: TranslationProjectV2Entity?): EngineConfigDraft = EngineConfigDraft(
            parallelChapters = (project?.parallelChapters ?: 1).coerceIn(1, ENGINE_MAX_PARALLEL_CHAPTERS),
            continuityMode = project?.continuityMode ?: TranslationContinuity.SOURCE_TAIL,
            metadataEnabled = project?.metadataEnabled ?: false,
            promptCacheEnabled = project?.promptCacheEnabled ?: true
        )
    }
}

/**
 * "Translation engine" section of the workbench configuration area.
 *
 * The four flags used to exist only in the database and in the agent tool, with
 * no UI at all. Every control is a design-system component (AppSection +
 * AppSettingsRow + AppSegmentedControl + AppPrimaryButton) and every string
 * comes from [EngineConfigStrings], so the section follows the global language.
 *
 * The section owns only the draft state; persistence is delegated:
 * - existing project -> [AppViewModel.updateTranslationProjectConfig] (engine fields only,
 *   the remaining fields fall back to the stored values);
 * - no project yet -> the draft is reported through [onDraftChange] so the caller can
 *   pass it to [AppViewModel.configureEditionTranslation] when the task is created.
 */
@Composable
fun EngineConfigurationSection(
    project: TranslationProjectV2Entity?,
    viewModel: AppViewModel,
    strings: EngineConfigStrings,
    canEdit: Boolean,
    onDraftChange: (EngineConfigDraft) -> Unit,
    modifier: Modifier = Modifier
) {
    var parallelChapters by rememberSaveable(project?.id, project?.updatedAt) {
        mutableIntStateOf((project?.parallelChapters ?: 1).coerceIn(1, ENGINE_MAX_PARALLEL_CHAPTERS))
    }
    var continuityMode by rememberSaveable(project?.id, project?.updatedAt) {
        mutableStateOf(project?.continuityMode ?: TranslationContinuity.SOURCE_TAIL)
    }
    var metadataEnabled by rememberSaveable(project?.id, project?.updatedAt) {
        mutableStateOf(project?.metadataEnabled ?: false)
    }
    var promptCacheEnabled by rememberSaveable(project?.id, project?.updatedAt) {
        mutableStateOf(project?.promptCacheEnabled ?: true)
    }

    // The creation flow reads the draft, so mirror it upwards on every change.
    LaunchedEffect(parallelChapters, continuityMode, metadataEnabled, promptCacheEnabled) {
        onDraftChange(
            EngineConfigDraft(
                parallelChapters = parallelChapters,
                continuityMode = continuityMode,
                metadataEnabled = metadataEnabled,
                promptCacheEnabled = promptCacheEnabled
            )
        )
    }

    val forcedSerial = continuityMode == TranslationContinuity.TRANSLATION_TAIL
    val footer = when {
        project == null -> strings.applyOnCreateHint
        !canEdit -> strings.lockedHint
        else -> strings.sectionFooter
    }

    AppSection(
        modifier = modifier,
        header = strings.sectionTitle,
        footer = footer
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = strings.sectionSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)
            )
        }

        AppDivider()

        EngineControlBlock(
            title = strings.parallelTitle,
            subtitle = strings.parallelSubtitle,
            description = strings.parallelDescription,
            valueText = strings.parallelValue(parallelChapters),
            icon = Icons.Outlined.Layers,
            iconTint = MaterialTheme.colorScheme.primary
        ) {
            AppSegmentedControl(
                items = (1..ENGINE_MAX_PARALLEL_CHAPTERS).map { it.toString() },
                selectedIndex = parallelChapters - 1,
                onItemSelected = { index -> if (canEdit) parallelChapters = index + 1 },
                modifier = Modifier.padding(horizontal = Spacing.l)
            )
            if (forcedSerial) {
                Text(
                    text = strings.continuityForcedSerialNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)
                )
            }
        }

        AppDivider()

        EngineControlBlock(
            title = strings.continuityTitle,
            subtitle = strings.continuitySubtitle,
            description = if (forcedSerial) {
                strings.continuityTranslationTailHint
            } else {
                strings.continuitySourceTailHint
            },
            valueText = if (forcedSerial) {
                strings.continuityTranslationTail
            } else {
                strings.continuitySourceTail
            },
            icon = Icons.Outlined.Autorenew,
            iconTint = MaterialTheme.colorScheme.primary
        ) {
            AppSegmentedControl(
                items = listOf(strings.continuitySourceTail, strings.continuityTranslationTail),
                selectedIndex = if (forcedSerial) 1 else 0,
                onItemSelected = { index ->
                    if (canEdit) {
                        continuityMode = if (index == 1) {
                            TranslationContinuity.TRANSLATION_TAIL
                        } else {
                            TranslationContinuity.SOURCE_TAIL
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = Spacing.l)
            )
        }

        AppDivider()

        AppSettingsRow(
            title = strings.promptCacheTitle,
            subtitle = if (promptCacheEnabled) {
                strings.promptCacheOnSubtitle
            } else {
                strings.promptCacheOffSubtitle
            },
            leadingIcon = Icons.Outlined.Bolt,
            iconTint = MaterialTheme.colorScheme.tertiary,
            iconBackground = MaterialTheme.colorScheme.tertiaryContainer,
            showChevron = false,
            onClick = { if (canEdit) promptCacheEnabled = !promptCacheEnabled },
            trailingContent = {
                Switch(
                    checked = promptCacheEnabled,
                    onCheckedChange = { enabled -> if (canEdit) promptCacheEnabled = enabled },
                    enabled = canEdit
                )
            }
        )

        // 工程已存在时本分区自己保存；未建工程时由创建任务入口一并写入（见 applyOnCreateHint）。
        project?.let { activeProject ->
            AppDivider()
            Box(modifier = Modifier.padding(Spacing.l)) {
                AppPrimaryButton(
                    text = strings.save,
                    onClick = {
                        val mode = runCatching { TranslationMode.valueOf(activeProject.translationMode) }
                            .getOrDefault(TranslationMode.FULL_BOOK)
                        viewModel.updateTranslationProjectConfig(
                            projectId = activeProject.id,
                            providerId = activeProject.providerId,
                            modelName = activeProject.modelName,
                            mode = mode,
                            maxBatchChapters = activeProject.maxBatchChapters,
                            rangeStart = activeProject.rangeStart,
                            rangeEnd = activeProject.rangeEnd,
                            seamlessAheadChapters = activeProject.seamlessAheadChapters,
                            styleGuide = activeProject.styleGuide,
                            highQualityReview = activeProject.highQualityReview,
                            parallelChapters = parallelChapters,
                            continuityMode = continuityMode,
                            metadataEnabled = metadataEnabled,
                            promptCacheEnabled = promptCacheEnabled
                        )
                    },
                    enabled = canEdit,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Label row + control + hint. The row uses the shared settings row so the icon
 * bubble, title and subtitle match every other configuration row.
 */
@Composable
private fun EngineControlBlock(
    title: String,
    subtitle: String,
    description: String,
    valueText: String,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        AppSettingsRow(
            title = title,
            subtitle = subtitle,
            leadingIcon = icon,
            iconTint = iconTint,
            iconBackground = iconTint.copy(alpha = 0.12f),
            valueText = valueText,
            showChevron = false
        )
        control()
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = Spacing.l,
                end = Spacing.l,
                top = Spacing.s,
                bottom = Spacing.m
            )
        )
    }
}
