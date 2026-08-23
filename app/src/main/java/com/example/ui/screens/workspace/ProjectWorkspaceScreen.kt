package com.example.ui.screens.workspace

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.core.llm.TokenCalculator
import com.example.core.parser.TxtParser
import com.example.data.model.ChapterStatus
import com.example.ui.components.ChapterItemCard
import com.example.ui.components.MetricStatCard
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.PrimaryIndigo
import com.example.ui.theme.SecondaryCyan
import com.example.ui.theme.TertiaryAmber
import com.example.ui.viewmodel.AppViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectWorkspaceScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onNavigateToTranslation: () -> Unit,
    onNavigateToGlossary: () -> Unit,
    onNavigateToReader: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val project by viewModel.activeProject.collectAsState()
    val chapters by viewModel.activeChapters.collectAsState()
    val providers by viewModel.allProviders.collectAsState()
    val defaultProvider = providers.firstOrNull { it.id == project?.defaultProviderId }
        ?: providers.firstOrNull { it.isDefault }
        ?: providers.firstOrNull()

    var selectedFilter by remember { mutableStateOf<ChapterStatus?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    var showSplitDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (project == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val currentProject = project!!
    val filteredChapters = chapters.filter { ch ->
        (selectedFilter == null || ch.status == selectedFilter) &&
                (searchQuery.isBlank() || ch.title.contains(searchQuery, ignoreCase = true))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentProject.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                        Text(
                            text = "${currentProject.sourceLanguage} ➔ ${currentProject.targetLanguage} • ${currentProject.fileType}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("workspace_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.cancel)
                    }
                },
                actions = {
                    if (currentProject.fileType == "TXT") {
                        IconButton(
                            onClick = { showSplitDialog = true },
                            modifier = Modifier.testTag("open_splitter_button")
                        ) {
                            Icon(Icons.Default.ContentCut, contentDescription = strings.openChapterSplitter)
                        }
                    }
                    IconButton(
                        onClick = onNavigateToGlossary,
                        modifier = Modifier.testTag("open_glossary_button")
                    ) {
                        Icon(Icons.Default.Book, contentDescription = strings.openGlossary)
                    }
                    IconButton(
                        onClick = { showExportDialog = true },
                        modifier = Modifier.testTag("open_export_button")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = strings.openExport)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            if (defaultProvider != null) {
                                viewModel.translateNextPendingChapter(defaultProvider)
                                onNavigateToTranslation()
                            } else {
                                viewModel.showMessage(strings.noProvidersConfigured)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).testTag("translate_next_button")
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.translateNext)
                    }

                    Button(
                        onClick = onNavigateToTranslation,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1.3f).testTag("translation_cockpit_button")
                    ) {
                        Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.translationCockpit)
                    }
                }
            }
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Metrics Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricStatCard(
                    title = strings.chaptersCount,
                    value = "${currentProject.translatedChapters} / ${currentProject.totalChapters}",
                    subtitle = "${(if (currentProject.totalChapters > 0) (currentProject.translatedChapters * 100 / currentProject.totalChapters) else 0)}% ${strings.donePercent}",
                    icon = Icons.Default.FormatListNumbered,
                    accentColor = EmeraldAccent,
                    modifier = Modifier.weight(1f)
                )

                MetricStatCard(
                    title = "${strings.tokensLabel} & ${strings.totalCostLabel}",
                    value = TokenCalculator.formatCost(currentProject.totalCost, currentProject.costCurrency),
                    subtitle = "${TokenCalculator.formatTokenCount(currentProject.totalPromptTokens + currentProject.totalCompletionTokens)} tok",
                    icon = Icons.Default.AttachMoney,
                    accentColor = TertiaryAmber,
                    modifier = Modifier.weight(1f)
                )
            }

            // Search and Status Filters
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(strings.searchChapterPlaceholder) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilter == null,
                    onClick = { selectedFilter = null },
                    label = { Text("${strings.filterAll} (${chapters.size})") }
                )
                FilterChip(
                    selected = selectedFilter == ChapterStatus.PENDING,
                    onClick = { selectedFilter = ChapterStatus.PENDING },
                    label = { Text("${strings.filterPending} (${chapters.count { it.status == ChapterStatus.PENDING }})") }
                )
                FilterChip(
                    selected = selectedFilter == ChapterStatus.COMPLETED,
                    onClick = { selectedFilter = ChapterStatus.COMPLETED },
                    label = { Text("${strings.filterDone} (${chapters.count { it.status == ChapterStatus.COMPLETED }})") }
                )
                FilterChip(
                    selected = selectedFilter == ChapterStatus.ERROR,
                    onClick = { selectedFilter = ChapterStatus.ERROR },
                    label = { Text("${strings.filterError} (${chapters.count { it.status == ChapterStatus.ERROR }})") }
                )
            }

            // Chapters List
            if (filteredChapters.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = strings.noMatchingChapters,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(filteredChapters, key = { it.id }) { chapter ->
                        ChapterItemCard(
                            chapter = chapter,
                            isSelected = false,
                            onSelect = { onNavigateToReader(chapter.id) },
                            onTranslate = {
                                if (defaultProvider != null) {
                                    viewModel.translateSingleChapter(chapter.id, defaultProvider)
                                    onNavigateToTranslation()
                                } else {
                                    viewModel.showMessage(strings.noProvidersConfigured)
                                }
                            },
                            onPreview = { onNavigateToReader(chapter.id) },
                            costCurrency = currentProject.costCurrency
                        )
                    }
                }
            }
        }
    }

    if (showSplitDialog) {
        ChapterSplitDialog(
            currentRegex = TxtParser.REGEX_CHINESE,
            onDismiss = { showSplitDialog = false },
            onReSplitWithRegex = { regex ->
                viewModel.reSplitChapters(currentProject.id, regex)
                showSplitDialog = false
            },
            onRunAgentSplit = {
                if (defaultProvider != null) {
                    viewModel.runAgentChapterSplit(currentProject.id, defaultProvider)
                    showSplitDialog = false
                } else {
                    viewModel.showMessage(strings.noProvidersConfigured)
                }
            }
        )
    }

    if (showExportDialog) {
        ExportNovelDialog(
            onDismiss = { showExportDialog = false },
            onExportTxt = { includeGlossary, includeParallel ->
                viewModel.exportToTxt(currentProject.id, includeGlossary, includeParallel) { exportedFile ->
                    shareExportedFile(context, exportedFile, "text/plain")
                }
                showExportDialog = false
            },
            onExportEpub = {
                viewModel.exportToEpub(currentProject.id) { exportedFile ->
                    shareExportedFile(context, exportedFile, "application/epub+zip")
                }
                showExportDialog = false
            }
        )
    }
}

private fun shareExportedFile(context: android.content.Context, file: File, mimeType: String) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Translated Novel"))
    } catch (e: Exception) {
        // Share error fallback
    }
}

@Composable
fun ChapterSplitDialog(
    currentRegex: String,
    onDismiss: () -> Unit,
    onReSplitWithRegex: (String) -> Unit,
    onRunAgentSplit: () -> Unit
) {
    val strings = LocalAppStrings.current
    var selectedPreset by remember { mutableStateOf("chinese") }
    var customRegexText by remember { mutableStateOf(currentRegex) }
    var showDestructiveConfirm by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun requestDestructiveAction(action: () -> Unit) {
        pendingAction = action
        showDestructiveConfirm = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.splitterTitle) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = strings.splitterDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Presets
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = selectedPreset == "chinese",
                        onClick = {
                            selectedPreset = "chinese"
                            customRegexText = TxtParser.REGEX_CHINESE
                        },
                        label = { Text(strings.presetChinese) }
                    )
                    FilterChip(
                        selected = selectedPreset == "english",
                        onClick = {
                            selectedPreset = "english"
                            customRegexText = TxtParser.REGEX_ENGLISH
                        },
                        label = { Text(strings.presetEnglish) }
                    )
                    FilterChip(
                        selected = selectedPreset == "markdown",
                        onClick = {
                            selectedPreset = "markdown"
                            customRegexText = TxtParser.REGEX_MARKDOWN
                        },
                        label = { Text(strings.presetMarkdown) }
                    )
                }

                OutlinedTextField(
                    value = customRegexText,
                    onValueChange = {
                        customRegexText = it
                        selectedPreset = "custom"
                    },
                    label = { Text(strings.regexPatternLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall
                )

                HorizontalDivider()

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = strings.aiAgentSlicerTitle,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = strings.aiAgentSlicerDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { requestDestructiveAction(onRunAgentSplit) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier.fillMaxWidth().testTag("run_agent_split_button")
                        ) {
                            Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.runAiAgentSlicer)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { requestDestructiveAction { onReSplitWithRegex(customRegexText) } }) {
                Text(strings.resliceByRegex)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
            }
        }
    )

    if (showDestructiveConfirm) {
        AlertDialog(
            onDismissRequest = {
                showDestructiveConfirm = false
                pendingAction = null
            },
            title = { Text(strings.confirmDestructiveAction) },
            text = { Text(strings.destructiveSplitWarning) },
            confirmButton = {
                Button(onClick = {
                    val action = pendingAction
                    showDestructiveConfirm = false
                    pendingAction = null
                    action?.invoke()
                }) {
                    Text(strings.confirmDestructiveAction)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDestructiveConfirm = false
                    pendingAction = null
                }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}

@Composable
fun ExportNovelDialog(
    onDismiss: () -> Unit,
    onExportTxt: (includeGlossary: Boolean, includeParallel: Boolean) -> Unit,
    onExportEpub: () -> Unit
) {
    val strings = LocalAppStrings.current
    var includeGlossary by remember { mutableStateOf(true) }
    var includeParallel by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.exportTitle) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = strings.epubExportTitle,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = strings.epubExportDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onExportEpub,
                            colors = ButtonDefaults.buttonColors(containerColor = SecondaryCyan),
                            modifier = Modifier.fillMaxWidth().testTag("export_epub_button")
                        ) {
                            Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.exportEpubBtn)
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = strings.txtExportTitle,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = includeGlossary,
                                onCheckedChange = { includeGlossary = it }
                            )
                            Text(strings.includeGlossaryOption, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = includeParallel,
                                onCheckedChange = { includeParallel = it }
                            )
                            Text(strings.bilingualComparisonOption, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = { onExportTxt(includeGlossary, includeParallel) },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                            modifier = Modifier.fillMaxWidth().testTag("export_txt_button")
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.exportTxtBtn)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.close)
            }
        }
    )
}
