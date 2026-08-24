package com.breakyuna.noveltranslator.ui.screens.projects

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.core.llm.TokenCalculator
import com.breakyuna.noveltranslator.data.model.ProjectEntity
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.theme.EmeraldAccent
import com.breakyuna.noveltranslator.ui.theme.PrimaryIndigo
import com.breakyuna.noveltranslator.ui.theme.SecondaryCyan
import com.breakyuna.noveltranslator.ui.theme.TertiaryAmber
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    viewModel: AppViewModel,
    onSelectProject: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val projects by viewModel.allProjects.collectAsState()
    var showImportDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onOpenDrawer != null) {
                        IconButton(
                            onClick = onOpenDrawer,
                            modifier = Modifier.testTag("open_drawer_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = strings.navMenuDesc
                            )
                        }
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(PrimaryIndigo),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoStories,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = strings.appTitle,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = strings.appSubtitle,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("open_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = strings.openSettings
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showImportDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = strings.importNovel) },
                text = { Text(strings.importNovel) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("import_novel_fab")
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Quick Action Card / Demo Banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.getStartedTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = strings.getStartedDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { viewModel.createProjectFromSample() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("load_demo_button")
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.loadDemo)
                    }
                }
            }

            Text(
                text = "${strings.projectsHeader} (${projects.size})",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            if (projects.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.LibraryBooks,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = strings.noProjectsTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = strings.noProjectsDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onClick = { onSelectProject(project.id) },
                            onDelete = { viewModel.deleteProject(project.id) }
                        )
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        ImportNovelDialog(
            onDismiss = { showImportDialog = false },
            onImport = { name, bytes, srcLang, tgtLang, style, regex ->
                viewModel.importFile(
                    fileName = name,
                    fileBytes = bytes,
                    sourceLang = srcLang,
                    targetLang = tgtLang,
                    style = style,
                    customRegex = regex
                )
                showImportDialog = false
            }
        )
    }
}

@Composable
fun ProjectCard(
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

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("project_card_${project.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (project.fileType == "EPUB") SecondaryCyan.copy(alpha = 0.15f) else PrimaryIndigo.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = project.fileType,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (project.fileType == "EPUB") SecondaryCyan else PrimaryIndigo
                        )
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "${project.sourceLanguage} ➔ ${project.targetLanguage}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = strings.deleteProject,
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = project.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "${strings.authorLabel}: ${project.author} • ${strings.styleLabel}: ${project.translationStyle}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Word Count & Progress Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${strings.progressLabel}: ${project.translatedChapters} / ${project.totalChapters} ${strings.chaptersCount} • ${project.totalOriginalWords} ${strings.wordsUnit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = EmeraldAccent
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = EmeraldAccent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Token and Cost Stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${strings.tokensLabel}: ${TokenCalculator.formatTokenCount(project.totalPromptTokens + project.totalCompletionTokens)}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${strings.totalCostLabel}: ${TokenCalculator.formatCost(project.totalCost, project.costCurrency)}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TertiaryAmber
                    )
                )
            }
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
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(strings.delete)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(strings.cancel)
                }
            }
        )
    }
}

@Composable
fun ImportNovelDialog(
    onDismiss: () -> Unit,
    onImport: (name: String, bytes: ByteArray, srcLang: String, tgtLang: String, style: String, regex: String?) -> Unit
) {
    val strings = LocalAppStrings.current
    var fileName by remember { mutableStateOf("") }
    var fileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pastedText by remember { mutableStateOf("") }
    var isPasteMode by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var isReadingFile by remember { mutableStateOf(false) }

    var sourceLang by remember { mutableStateOf("English") }
    var targetLang by remember { mutableStateOf("Chinese") }
    var translationStyle by remember { mutableStateOf("Literary Novel") }
    var customRegex by remember { mutableStateOf("") }

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
                        val bytes = context.contentResolver.openInputStream(uri)?.use(::readLimited)
                            ?: error("Unable to open the selected file")
                        displayName to bytes
                    }
                }.onSuccess { (name, bytes) ->
                    fileBytes = bytes
                    fileName = name
                }.onFailure { error ->
                    fileBytes = null
                    fileName = ""
                    importError = error.localizedMessage ?: "Unable to read the selected file"
                }
                isReadingFile = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.importDialogTitle) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = !isPasteMode,
                        onClick = { isPasteMode = false },
                        label = { Text(strings.fileUploadTab) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = isPasteMode,
                        onClick = { isPasteMode = true },
                        label = { Text(strings.pasteTextTab) }
                    )
                }

                if (!isPasteMode) {
                    OutlinedButton(
                        onClick = { launcher.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) },
                        enabled = !isReadingFile,
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        maxLines = 6
                    )
                }

                importError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                // Source Language Input & Quick Selection Scroll Window
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = sourceLang,
                        onValueChange = { sourceLang = it },
                        label = { Text(strings.sourceLangLabel) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${strings.presetLanguagesLabel} (${strings.sourceLangLabel}):",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val presetLangs = listOf("English", "中文", "日本語", "한국어", "Français", "Deutsch", "Español", "Русский", "Auto")
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(presetLangs) { lang ->
                            FilterChip(
                                selected = sourceLang.equals(lang, ignoreCase = true),
                                onClick = { sourceLang = lang },
                                label = { Text(lang, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                // Target Language Input & Quick Selection Scroll Window
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = targetLang,
                        onValueChange = { targetLang = it },
                        label = { Text(strings.targetLangLabel) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${strings.presetLanguagesLabel} (${strings.targetLangLabel}):",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val targetPresetLangs = listOf("中文", "English", "日本語", "한국어", "Français", "Deutsch", "Español", "Русский", "繁體中文")
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(targetPresetLangs) { lang ->
                            FilterChip(
                                selected = targetLang.equals(lang, ignoreCase = true),
                                onClick = { targetLang = lang },
                                label = { Text(lang, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = translationStyle,
                    onValueChange = { translationStyle = it },
                    label = { Text(strings.translationStyleLabel) },
                    placeholder = { Text(strings.translationStylePlaceholder) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = customRegex,
                    onValueChange = { customRegex = it },
                    label = { Text(strings.customRegexLabel) },
                    placeholder = { Text(strings.customRegexPlaceholder) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalBytes = if (isPasteMode) {
                        pastedText.toByteArray(Charsets.UTF_8)
                    } else {
                        fileBytes
                    }
                    val finalName = if (isPasteMode) "pasted_novel.txt" else fileName.ifBlank { "novel.txt" }

                    if (finalBytes != null && finalBytes.isNotEmpty()) {
                        onImport(
                            finalName,
                            finalBytes,
                            sourceLang,
                            targetLang,
                            translationStyle,
                            customRegex.ifBlank { null }
                        )
                    }
                },
                enabled = (isPasteMode && pastedText.isNotBlank()) || (!isPasteMode && fileBytes != null)
            ) {
                Text(strings.createProject)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
            }
        }
    )
}

private const val MAX_IMPORT_BYTES = 100L * 1024 * 1024

private fun readLimited(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= MAX_IMPORT_BYTES) { "File exceeds the 100 MB import limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
