package com.breakyuna.noveltranslator.ui.screens.glossary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.data.model.GlossaryEntity
import com.breakyuna.noveltranslator.data.model.LegacyGlossaryCandidateVoting
import com.breakyuna.noveltranslator.data.model.LexiconSource
import com.breakyuna.noveltranslator.data.model.ReviewStatus
import com.breakyuna.noveltranslator.data.model.TermCategory
import com.breakyuna.noveltranslator.ui.components.apple.*
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.theme.*
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val project by viewModel.activeProject.collectAsState()
    val glossary by viewModel.activeGlossary.collectAsState()
    val providers by viewModel.allProviders.collectAsState()
    val defaultProvider = providers.firstOrNull { it.id == project?.defaultProviderId }
        ?: providers.firstOrNull { it.isDefault }
        ?: providers.firstOrNull()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<TermCategory?>(null) }

    var showAddDialog by remember { mutableStateOf(false) }
    var showExtractionDialog by remember { mutableStateOf(false) }
    var editingTerm by remember { mutableStateOf<GlossaryEntity?>(null) }

    val visibleGlossary = glossary.filterNot(LegacyGlossaryCandidateVoting::isIgnored)
    val filteredGlossary = visibleGlossary.filter { term ->
        (selectedCategory == null || term.category == selectedCategory) &&
                (searchQuery.isBlank() ||
                        term.originalTerm.contains(searchQuery, ignoreCase = true) ||
                        term.translatedTerm.contains(searchQuery, ignoreCase = true) ||
                        LegacyGlossaryCandidateVoting.displayNotes(term).contains(searchQuery, ignoreCase = true))
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = strings.glossaryTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "《${project?.title ?: ""}》 · ${glossary.size} 词条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("glossary_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.cancel,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (project != null && defaultProvider != null) {
                                showExtractionDialog = true
                            } else {
                                viewModel.showMessage(strings.noProvidersConfigured)
                            }
                        },
                        modifier = Modifier.testTag("ai_extract_terms_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = strings.aiExtractTermsBtn,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(strings.addTermBtn) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = ButtonShape,
                modifier = Modifier.testTag("add_term_fab")
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = Spacing.compactHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. AI Term Miner Hero (Apple Grouped Surface)
            AppGroupedSurface(contentPadding = PaddingValues(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.aiMinerTitle,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = strings.aiMinerDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    AppSecondaryButton(
                        text = strings.extractBtn,
                        onClick = {
                            if (project != null && defaultProvider != null) {
                                showExtractionDialog = true
                            } else {
                                viewModel.showMessage(strings.noProvidersConfigured)
                            }
                        },
                        icon = Icons.Outlined.AutoAwesome
                    )
                }
            }

            // 2. Search Field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(strings.searchTermsPlaceholder) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                shape = SmallControlShape,
                modifier = Modifier.fillMaxWidth()
            )

            // 3. Category Filter Chips
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    CategoryFilterChipApple(
                        label = "全部 (${visibleGlossary.size})",
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null }
                    )
                }
                items(TermCategory.values(), key = { it.name }) { category ->
                    val count = visibleGlossary.count { it.category == category }
                    val catLabel = when (category) {
                        TermCategory.CHARACTER -> strings.catCharacter
                        TermCategory.LOCATION -> strings.catLocation
                        TermCategory.LORE -> strings.catLore
                        TermCategory.SKILL -> strings.catSkill
                        TermCategory.ITEM -> strings.catItem
                        TermCategory.HONORIFIC -> strings.catHonorific
                        TermCategory.CUSTOM -> strings.catCustom
                    }
                    CategoryFilterChipApple(
                        label = "$catLabel ($count)",
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category }
                    )
                }
            }

            // 4. Term Items List
            if (filteredGlossary.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank()) strings.noMatchingTerms else strings.noTermsAdded,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                AppGroupedSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 72.dp)
                    ) {
                        items(filteredGlossary, key = { it.id }) { term ->
                            GlossaryRowApple(
                                term = term,
                                onEdit = { editingTerm = term },
                                onApprove = { viewModel.approveGlossaryTerm(term) },
                                onDelete = { viewModel.deleteGlossaryTerm(term.id) }
                            )
                            AppDivider(startIndent = 16.dp)
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog && project != null) {
        TermEditDialog(
            term = null,
            projectId = project!!.id,
            onDismiss = { showAddDialog = false },
            onSave = { newTerm ->
                viewModel.addGlossaryTerm(newTerm)
                showAddDialog = false
            }
        )
    }

    if (editingTerm != null && project != null) {
        TermEditDialog(
            term = editingTerm,
            projectId = project!!.id,
            onDismiss = { editingTerm = null },
            onSave = { updatedTerm ->
                if (updatedTerm.reviewStatus == ReviewStatus.CANDIDATE.name) {
                    viewModel.approveGlossaryTerm(updatedTerm)
                } else {
                    viewModel.updateGlossaryTerm(updatedTerm)
                }
                editingTerm = null
            }
        )
    }

    if (showExtractionDialog && project != null && defaultProvider != null) {
        TermExtractionDialog(
            viewModel = viewModel,
            projectId = project!!.id,
            provider = defaultProvider,
            onDismiss = { showExtractionDialog = false }
        )
    }
}

@Composable
private fun CategoryFilterChipApple(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = SmallControlShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        modifier = Modifier.height(30.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun GlossaryRowApple(
    term: GlossaryEntity,
    onEdit: () -> Unit,
    onApprove: () -> Unit,
    onDelete: () -> Unit
) {
    val categoryColor = when (term.category) {
        TermCategory.CHARACTER -> AccentBlue
        TermCategory.LOCATION -> StatusSuccess
        TermCategory.LORE -> AccentPurple
        TermCategory.SKILL -> StatusWarning
        TermCategory.ITEM -> AccentTeal
        TermCategory.HONORIFIC -> MaterialTheme.colorScheme.primary
        TermCategory.CUSTOM -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = categoryColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = term.category.name,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = categoryColor
                        )
                    )
                }

                if (term.source == LexiconSource.AI.name || term.isAutoExtracted) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = StatusWarning.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = if (term.reviewStatus == ReviewStatus.CANDIDATE.name) "AI 提取待确认" else "AI 来源",
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = StatusWarning
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = term.originalTerm,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = " ➔ ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = term.translatedTerm,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            LegacyGlossaryCandidateVoting.evidenceSummary(term)?.let { summary ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusWarning,
                    maxLines = 2
                )
            }
            if (LegacyGlossaryCandidateVoting.hasConflict(term)) {
                Text(
                    text = "存在译名或类别冲突",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.error
                )
            }

            val displayNotes = LegacyGlossaryCandidateVoting.displayNotes(term)
            if (displayNotes.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = displayNotes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        if (term.reviewStatus == ReviewStatus.CANDIDATE.name) {
            IconButton(onClick = onApprove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Check, contentDescription = "采纳", tint = StatusSuccess, modifier = Modifier.size(16.dp))
            }
        }

        IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.Edit, contentDescription = "编辑", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}

@Composable
fun TermEditDialog(
    term: GlossaryEntity?,
    projectId: Long,
    onDismiss: () -> Unit,
    onSave: (GlossaryEntity) -> Unit
) {
    val strings = LocalAppStrings.current
    var original by remember { mutableStateOf(term?.originalTerm ?: "") }
    var translated by remember { mutableStateOf(term?.translatedTerm ?: "") }
    var category by remember { mutableStateOf(term?.category ?: TermCategory.CHARACTER) }
    var notes by remember { mutableStateOf(term?.let(LegacyGlossaryCandidateVoting::displayNotes) ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when {
                    term == null -> strings.addTermBtn
                    term.reviewStatus == ReviewStatus.CANDIDATE.name -> "编辑并确认候选"
                    else -> "编辑专有名词"
                },
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = original,
                    onValueChange = { original = it },
                    label = { Text(strings.originalTermLabel) },
                    shape = SmallControlShape,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = translated,
                    onValueChange = { translated = it },
                    label = { Text(strings.translatedTermLabel) },
                    shape = SmallControlShape,
                    modifier = Modifier.fillMaxWidth()
                )

                // Category selector
                Text(strings.categoryLabel, style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(TermCategory.values(), key = { it.name }) { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.name) },
                            shape = SmallControlShape
                        )
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(strings.notesLabel) },
                    shape = SmallControlShape,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val entity = term?.copy(
                        originalTerm = original,
                        translatedTerm = translated,
                        category = category,
                        notes = notes
                    ) ?: GlossaryEntity(
                        projectId = projectId,
                        originalTerm = original,
                        translatedTerm = translated,
                        category = category,
                        notes = notes,
                        source = LexiconSource.MANUAL.name,
                        reviewStatus = ReviewStatus.CONFIRMED.name
                    )
                    onSave(entity)
                },
                enabled = original.isNotBlank() && translated.isNotBlank(),
                shape = ButtonShape
            ) {
                Text(if (term?.reviewStatus == ReviewStatus.CANDIDATE.name) "确认并保存" else strings.save)
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

@Composable
fun TermExtractionDialog(
    viewModel: AppViewModel,
    projectId: Long,
    provider: com.breakyuna.noveltranslator.data.model.ApiProviderEntity,
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current
    var isExtracting by remember { mutableStateOf(false) }
    var sampleChapterCount by remember { mutableStateOf("3") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.aiMinerTitle, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "AI 将扫描前几章原文，提取出人名、地名、功法、武器与特殊世界观专有名词并生成参考译名。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = sampleChapterCount,
                    onValueChange = { sampleChapterCount = it },
                    label = { Text("扫描章节数 (前 N 章)") },
                    shape = SmallControlShape,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isExtracting) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("AI 正在深度解析章节术语...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isExtracting = true
                    val count = sampleChapterCount.toIntOrNull() ?: 3
                    viewModel.extractGlossaryWithAi(projectId, provider, count) {
                        isExtracting = false
                        onDismiss()
                    }
                },
                enabled = !isExtracting,
                shape = ButtonShape
            ) {
                Text(strings.extractBtn)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isExtracting) {
                Text(strings.cancel)
            }
        },
        shape = DialogShape
    )
}
