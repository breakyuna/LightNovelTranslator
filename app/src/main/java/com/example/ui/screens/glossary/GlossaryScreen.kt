package com.example.ui.screens.glossary

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GlossaryEntity
import com.example.data.model.TermCategory
import com.example.ui.components.TermItemCard
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.viewmodel.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val project by viewModel.activeProject.collectAsState()
    val glossary by viewModel.activeGlossary.collectAsState()
    val providers by viewModel.allProviders.collectAsState()
    val defaultProvider = providers.firstOrNull { it.isDefault } ?: providers.firstOrNull()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<TermCategory?>(null) }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingTerm by remember { mutableStateOf<GlossaryEntity?>(null) }

    val filteredGlossary = glossary.filter { term ->
        (selectedCategory == null || term.category == selectedCategory) &&
                (searchQuery.isBlank() ||
                        term.originalTerm.contains(searchQuery, ignoreCase = true) ||
                        term.translatedTerm.contains(searchQuery, ignoreCase = true) ||
                        term.notes.contains(searchQuery, ignoreCase = true))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(strings.glossaryTitle, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            text = "${strings.glossarySubtitle} • ${glossary.size} ${strings.termsCount}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("glossary_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.cancel)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (project != null && defaultProvider != null) {
                                viewModel.runAutoExtractTerms(project!!.id, defaultProvider)
                            } else {
                                viewModel.showMessage(strings.noProvidersConfigured)
                            }
                        },
                        modifier = Modifier.testTag("ai_extract_terms_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
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
                modifier = Modifier.testTag("add_term_fab")
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
            // AI Extraction Helper Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.aiMinerTitle,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = strings.aiMinerDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (project != null && defaultProvider != null) {
                                viewModel.runAutoExtractTerms(project!!.id, defaultProvider)
                            } else {
                                viewModel.showMessage(strings.noProvidersConfigured)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(strings.extractBtn)
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(strings.searchTermsPlaceholder) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp)
            )

            // Category Chips Row
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("${strings.filterAll} (${glossary.size})") }
                    )
                }
                items(TermCategory.values()) { category ->
                    val count = glossary.count { it.category == category }
                    val catLabel = when (category) {
                        TermCategory.CHARACTER -> strings.catCharacter
                        TermCategory.LOCATION -> strings.catLocation
                        TermCategory.LORE -> strings.catLore
                        TermCategory.SKILL -> strings.catSkill
                        TermCategory.ITEM -> strings.catItem
                        TermCategory.HONORIFIC -> strings.catHonorific
                        TermCategory.CUSTOM -> strings.catCustom
                    }
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text("$catLabel ($count)") }
                    )
                }
            }

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
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 80.dp, top = 6.dp)
                ) {
                    items(filteredGlossary, key = { it.id }) { term ->
                        TermItemCard(
                            term = term,
                            onEdit = { editingTerm = term },
                            onDelete = { viewModel.deleteGlossaryTerm(term.id) }
                        )
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
                viewModel.updateGlossaryTerm(updatedTerm)
                editingTerm = null
            }
        )
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
    var notes by remember { mutableStateOf(term?.notes ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (term == null) strings.addTermDialogTitle else strings.editTermDialogTitle) },
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
                    placeholder = { Text(strings.originalTermPlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = translated,
                    onValueChange = { translated = it },
                    label = { Text(strings.translatedTermLabel) },
                    placeholder = { Text(strings.translatedTermPlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Category Selector
                Text(strings.categoryLabel, style = MaterialTheme.typography.labelSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(TermCategory.values()) { cat ->
                        val catLabel = when (cat) {
                            TermCategory.CHARACTER -> strings.catCharacter
                            TermCategory.LOCATION -> strings.catLocation
                            TermCategory.LORE -> strings.catLore
                            TermCategory.SKILL -> strings.catSkill
                            TermCategory.ITEM -> strings.catItem
                            TermCategory.HONORIFIC -> strings.catHonorific
                            TermCategory.CUSTOM -> strings.catCustom
                        }
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(catLabel, fontSize = 11.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(strings.notesLabel) },
                    placeholder = { Text(strings.notesPlaceholder) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (original.isNotBlank() && translated.isNotBlank()) {
                        val entity = term?.copy(
                            originalTerm = original.trim(),
                            translatedTerm = translated.trim(),
                            category = category,
                            notes = notes.trim()
                        ) ?: GlossaryEntity(
                            projectId = projectId,
                            originalTerm = original.trim(),
                            translatedTerm = translated.trim(),
                            category = category,
                            notes = notes.trim(),
                            isAutoExtracted = false
                        )
                        onSave(entity)
                    }
                },
                enabled = original.isNotBlank() && translated.isNotBlank()
            ) {
                Text(strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
            }
        }
    )
}

