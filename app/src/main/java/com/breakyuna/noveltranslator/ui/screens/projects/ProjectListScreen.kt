package com.breakyuna.noveltranslator.ui.screens.projects

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.data.model.ProjectEntity
import com.breakyuna.noveltranslator.ui.components.apple.*
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.theme.*
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    viewModel: AppViewModel,
    onSelectProject: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val projects by viewModel.allProjects.collectAsState()
    var showImportDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppLargeTitle(
                title = strings.projectsHeader,
                subtitle = "管理小说与翻译工程",
                trailingContent = {
                    // Demo sample button
                    IconButton(
                        onClick = { viewModel.createProjectFromSample() },
                        modifier = Modifier.testTag("load_demo_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = strings.loadDemo,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Add / Import Novel button (+)
                    IconButton(
                        onClick = { showImportDialog = true },
                        modifier = Modifier.testTag("import_novel_fab")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddCircleOutline,
                            contentDescription = strings.importNovel,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (projects.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = Spacing.compactHorizontalPadding),
                contentAlignment = Alignment.Center
            ) {
                AppGroupedSurface(
                    contentPadding = PaddingValues(32.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.LibraryBooks,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = strings.noProjectsTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = strings.noProjectsDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AppPrimaryButton(
                                text = strings.importNovel,
                                onClick = { showImportDialog = true },
                                icon = Icons.Default.Add,
                                modifier = Modifier.widthIn(max = 160.dp)
                            )
                            AppSecondaryButton(
                                text = strings.loadDemo,
                                onClick = { viewModel.createProjectFromSample() },
                                icon = Icons.Default.Bolt,
                                modifier = Modifier.widthIn(max = 160.dp)
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(
                    horizontal = Spacing.compactHorizontalPadding,
                    vertical = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectCardApple(
                        project = project,
                        onClick = { onSelectProject(project.id) },
                        onDelete = { viewModel.deleteProject(project.id) }
                    )
                }
            }
        }
    }

    if (showImportDialog) {
        ImportNovelDialog(
            onDismiss = { showImportDialog = false },
            onImport = { name, uri, bytes, srcLang, tgtLang, style, regex, cropTableOfContents ->
                if (uri != null) {
                    viewModel.importFileFromUri(
                        uri = uri,
                        fileName = name,
                        sourceLang = srcLang,
                        targetLang = tgtLang,
                        style = style,
                        customRegex = regex,
                        cropTableOfContents = cropTableOfContents
                    )
                } else if (bytes != null) {
                    viewModel.importFile(
                        fileName = name,
                        fileBytes = bytes,
                        sourceLang = srcLang,
                        targetLang = tgtLang,
                        style = style,
                        customRegex = regex,
                        cropTableOfContents = cropTableOfContents
                    )
                }
                showImportDialog = false
            }
        )
    }
}

@Composable
fun ProjectCardApple(
    project: ProjectEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val progress = if (project.totalChapters > 0) {
        project.translatedChapters.toFloat() / project.totalChapters.toFloat()
    } else 0f
    val percentInt = (progress * 100).toInt()

    AppGroupedSurface(
        modifier = modifier.testTag("project_card_${project.id}"),
        onClick = onClick,
        contentPadding = PaddingValues(16.dp)
    ) {
        // Header line: Format badge, Language pair, and Delete icon
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (project.fileType == "EPUB") AccentBlue.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = project.fileType,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (project.fileType == "EPUB") AccentBlue else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "${project.sourceLanguage} → ${project.targetLanguage}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.weight(1f))

            IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = strings.deleteProject,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Novel Title
        Text(
            text = project.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Author & Style
        Text(
            text = "${project.author.ifBlank { "未知作者" }} · ${project.translationStyle}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Progress Stats Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${project.translatedChapters} / ${project.totalChapters} 章 (${project.totalOriginalWords} 字)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$percentInt%",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = if (percentInt == 100) StatusSuccess else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Progress bar
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape),
            color = if (percentInt == 100) StatusSuccess else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Token & Cost row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${TokenCalculator.formatTokenCount(project.totalPromptTokens + project.totalCompletionTokens)} Tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Text(
                text = TokenCalculator.formatCost(project.totalCost, project.costCurrency),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(strings.deleteProject) },
            text = { Text(String.format(strings.deleteProjectConfirm, project.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = StatusError)
                ) {
                    Text(strings.delete)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(strings.cancel)
                }
            },
            shape = DialogShape
        )
    }
}

@Composable
fun ImportNovelDialog(
    onDismiss: () -> Unit,
    onImport: (
        name: String,
        uri: Uri?,
        bytes: ByteArray?,
        srcLang: String,
        tgtLang: String,
        style: String,
        regex: String?,
        cropTableOfContents: Boolean
    ) -> Unit
) {
    val strings = LocalAppStrings.current
    var fileName by remember { mutableStateOf("") }
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var pastedText by remember { mutableStateOf("") }
    var isPasteMode by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var isReadingFile by remember { mutableStateOf(false) }

    var sourceLang by remember { mutableStateOf("English") }
    var targetLang by remember { mutableStateOf("Chinese") }
    var translationStyle by remember { mutableStateOf("Literary Novel") }
    var customRegex by remember { mutableStateOf("") }
    var cropTableOfContents by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isReadingFile = true
                importError = null
                runCatching {
                    withContext(Dispatchers.IO) {
                        var displayName = "imported_novel.txt"
                        var declaredSize: Long? = null
                        context.contentResolver.query(
                            uri,
                            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                                    displayName = cursor.getString(it) ?: displayName
                                }
                                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let {
                                    declaredSize = cursor.getLong(it)
                                }
                            }
                        }
                        require(declaredSize == null || declaredSize!! <= MAX_IMPORT_BYTES) {
                            "File exceeds the 100 MB import limit"
                        }
                        displayName
                    }
                }.onSuccess { name ->
                    fileUri = uri
                    fileName = name
                }.onFailure { error ->
                    fileUri = null
                    fileName = ""
                    importError = error.localizedMessage ?: "Unable to read the selected file"
                }
                isReadingFile = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = strings.importDialogTitle,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Segmented tab for File / Paste
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !isPasteMode,
                        onClick = { isPasteMode = false },
                        label = { Text(strings.fileUploadTab) },
                        shape = SmallControlShape
                    )
                    FilterChip(
                        selected = isPasteMode,
                        onClick = { isPasteMode = true },
                        label = { Text(strings.pasteTextTab) },
                        shape = SmallControlShape
                    )
                }

                if (!isPasteMode) {
                    OutlinedButton(
                        onClick = { launcher.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) },
                        enabled = !isReadingFile,
                        shape = ButtonShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            when {
                                isReadingFile -> "Reading file…"
                                fileName.isNotBlank() -> "${strings.selectedFilePrefix}$fileName"
                                else -> strings.chooseFileBtn
                            }
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = pastedText,
                        onValueChange = { pastedText = it },
                        label = { Text(strings.pastePlaceholder) },
                        shape = SmallControlShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp),
                        maxLines = 5
                    )
                }

                importError?.let {
                    Text(it, color = StatusError, style = MaterialTheme.typography.bodySmall)
                }

                // Source Language
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = sourceLang,
                        onValueChange = { sourceLang = it },
                        label = { Text(strings.sourceLangLabel) },
                        shape = SmallControlShape,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val presetLangs = listOf(
                        "source-en" to "English", "source-zh" to "中文", "source-ja" to "日本語",
                        "source-ko" to "한국어", "source-fr" to "Français", "source-de" to "Deutsch",
                        "source-es" to "Español", "source-ru" to "Русский", "source-auto" to "Auto"
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(presetLangs, key = { it.first }) { (_, lang) ->
                            FilterChip(
                                selected = sourceLang.equals(lang, ignoreCase = true),
                                onClick = { sourceLang = lang },
                                label = { Text(lang, style = MaterialTheme.typography.labelSmall) },
                                shape = SmallControlShape
                            )
                        }
                    }
                }

                // Target Language
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = targetLang,
                        onValueChange = { targetLang = it },
                        label = { Text(strings.targetLangLabel) },
                        shape = SmallControlShape,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val targetPresetLangs = listOf(
                        "target-zh-cn" to "中文", "target-en" to "English", "target-ja" to "日本語",
                        "target-ko" to "한국어", "target-fr" to "Français", "target-de" to "Deutsch",
                        "target-es" to "Español", "target-ru" to "Русский", "target-zh-tw" to "繁體中文"
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(targetPresetLangs, key = { it.first }) { (_, lang) ->
                            FilterChip(
                                selected = targetLang.equals(lang, ignoreCase = true),
                                onClick = { targetLang = lang },
                                label = { Text(lang, style = MaterialTheme.typography.labelSmall) },
                                shape = SmallControlShape
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = translationStyle,
                    onValueChange = { translationStyle = it },
                    label = { Text(strings.translationStyleLabel) },
                    shape = SmallControlShape,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = customRegex,
                    onValueChange = { customRegex = it },
                    label = { Text(strings.customRegexLabel) },
                    placeholder = { Text(strings.customRegexPlaceholder) },
                    shape = SmallControlShape,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.cropTableOfContentsLabel,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = strings.cropTableOfContentsDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = cropTableOfContents,
                        onCheckedChange = { cropTableOfContents = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalBytes = if (isPasteMode) {
                        pastedText.toByteArray(Charsets.UTF_8)
                    } else null
                    val finalUri = if (isPasteMode) null else fileUri
                    val finalName = if (isPasteMode) "pasted_novel.txt" else fileName.ifBlank { "novel.txt" }

                    if ((finalBytes != null && finalBytes.isNotEmpty()) || finalUri != null) {
                        onImport(
                            finalName,
                            finalUri,
                            finalBytes,
                            sourceLang,
                            targetLang,
                            translationStyle,
                            customRegex.ifBlank { null },
                            cropTableOfContents
                        )
                    }
                },
                enabled = (isPasteMode && pastedText.isNotBlank()) || (!isPasteMode && fileUri != null),
                shape = ButtonShape
            ) {
                Text(strings.createProject)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
            }
        },
        shape = DialogShape
    )
}

private const val MAX_IMPORT_BYTES = 100L * 1024 * 1024
