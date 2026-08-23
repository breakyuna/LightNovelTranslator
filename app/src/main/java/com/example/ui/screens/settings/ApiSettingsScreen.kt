package com.example.ui.screens.settings

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.llm.TokenCalculator
import com.example.data.model.ApiProviderEntity
import com.example.data.model.PresetModels
import com.example.data.model.ProviderType
import com.example.ui.i18n.AppLanguage
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.theme.EmeraldAccent
import com.example.ui.theme.PrimaryIndigo
import com.example.ui.theme.TertiaryAmber
import com.example.ui.viewmodel.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiSettingsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val currentLang by viewModel.currentLanguage.collectAsState()
    val providers by viewModel.allProviders.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ApiProviderEntity?>(null) }
    var testingProviderId by remember { mutableStateOf<Long?>(null) }
    var testResultMessage by remember { mutableStateOf<Pair<Long, String>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(strings.settingsTitle, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            text = strings.settingsSubtitle,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.cancel)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(strings.addProvider) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_provider_fab")
            )
        },
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
        ) {
            // Theme Mode Card
            item {
                val themeMode by viewModel.themeMode.collectAsState()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = strings.themeSettingsTitle,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            FilterChip(
                                selected = themeMode == com.example.ui.theme.AppThemeMode.SYSTEM,
                                onClick = { viewModel.setThemeMode(com.example.ui.theme.AppThemeMode.SYSTEM) },
                                label = { Text(strings.themeFollowSystem) },
                                leadingIcon = { Icon(Icons.Default.BrightnessAuto, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = themeMode == com.example.ui.theme.AppThemeMode.LIGHT,
                                onClick = { viewModel.setThemeMode(com.example.ui.theme.AppThemeMode.LIGHT) },
                                label = { Text(strings.themeLightMode) },
                                leadingIcon = { Icon(Icons.Default.LightMode, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = themeMode == com.example.ui.theme.AppThemeMode.DARK,
                                onClick = { viewModel.setThemeMode(com.example.ui.theme.AppThemeMode.DARK) },
                                label = { Text(strings.themeDarkMode) },
                                leadingIcon = { Icon(Icons.Default.DarkMode, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Language Selection Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = strings.languageSettingTitle,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = currentLang == AppLanguage.CHINESE,
                                onClick = { viewModel.setLanguage(AppLanguage.CHINESE) },
                                label = { Text(strings.chineseDefault) }
                            )
                            FilterChip(
                                selected = currentLang == AppLanguage.ENGLISH,
                                onClick = { viewModel.setLanguage(AppLanguage.ENGLISH) },
                                label = { Text(strings.english) }
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Calculate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = strings.calculatorEngineTitle,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = strings.calculatorEngineDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(providers, key = { it.id }) { provider ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("provider_card_${provider.name.lowercase().replace(" ", "_")}"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(
                        if (provider.isDefault) 1.5.dp else 1.dp,
                        if (provider.isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                    )
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
                            if (provider.isDefault) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = EmeraldAccent.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = strings.defaultLabel,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = EmeraldAccent
                                        )
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }

                            Text(
                                text = provider.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.weight(1f))

                            IconButton(onClick = { editingProvider = provider }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = strings.editProvider, modifier = Modifier.size(18.dp))
                            }

                            if (!provider.isDefault) {
                                IconButton(onClick = { viewModel.deleteProvider(provider.id) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = strings.delete, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "${strings.modelIdLabel}: ${provider.selectedModel} (${provider.providerType.displayName})",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary
                        )

                        Text(
                            text = "${strings.baseUrlLabel}: ${provider.baseUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Pricing Pill
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = "Prompt: $${provider.inputPricePerMillion}/M • Completion: $${provider.outputPricePerMillion}/M",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TertiaryAmber
                                    )
                                )
                            }

                            if (!provider.isDefault) {
                                TextButton(onClick = { viewModel.setDefaultProvider(provider.id) }) {
                                    Text(strings.setAsDefaultBtn, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        // Test Connection Result / Button
                        if (testResultMessage?.first == provider.id) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = testResultMessage!!.second,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    color = if (testResultMessage!!.second.startsWith("Success")) EmeraldAccent else MaterialTheme.colorScheme.error
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                testingProviderId = provider.id
                                viewModel.testProviderConnection(provider) { success, msg ->
                                    testResultMessage = Pair(provider.id, msg)
                                    testingProviderId = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("test_provider_${provider.id}"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (testingProviderId == provider.id) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(strings.testingStatus)
                            } else {
                                Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.testConnectionBtn)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        ProviderEditDialog(
            provider = null,
            onDismiss = { showAddDialog = false },
            onSave = { newProvider ->
                viewModel.saveProvider(newProvider)
                showAddDialog = false
            }
        )
    }

    if (editingProvider != null) {
        ProviderEditDialog(
            provider = editingProvider,
            onDismiss = { editingProvider = null },
            onSave = { updatedProvider ->
                viewModel.saveProvider(updatedProvider)
                editingProvider = null
            }
        )
    }
}

@Composable
fun ProviderEditDialog(
    provider: ApiProviderEntity?,
    onDismiss: () -> Unit,
    onSave: (ApiProviderEntity) -> Unit
) {
    val strings = LocalAppStrings.current
    var name by remember { mutableStateOf(provider?.name ?: "Custom OpenAI-Compatible") }
    var providerType by remember { mutableStateOf(provider?.providerType ?: ProviderType.OPENAI_COMPATIBLE) }
    var baseUrl by remember { mutableStateOf(provider?.baseUrl ?: "https://api.deepseek.com/v1") }
    var apiKey by remember { mutableStateOf(provider?.apiKey ?: "") }
    var isApiKeyVisible by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(provider?.selectedModel ?: "deepseek-chat") }
    var inputPrice by remember { mutableStateOf("${provider?.inputPricePerMillion ?: 0.14}") }
    var outputPrice by remember { mutableStateOf("${provider?.outputPricePerMillion ?: 0.28}") }
    var currency by remember { mutableStateOf(provider?.currency ?: "USD") }
    var maxContextTokens by remember { mutableStateOf("${provider?.maxContextTokens ?: 8192}") }
    var temperature by remember { mutableStateOf(provider?.temperature ?: 0.3f) }
    var isDefault by remember { mutableStateOf(provider?.isDefault ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (provider == null) strings.addProviderDialogTitle else strings.editProviderDialogTitle) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Preset Selector
                item {
                    Text(strings.quickPresetsLabel, style = MaterialTheme.typography.labelSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(PresetModels.presets) { preset ->
                            FilterChip(
                                selected = selectedModel == preset.defaultModel && baseUrl == preset.defaultBaseUrl,
                                onClick = {
                                    name = preset.name
                                    providerType = preset.providerType
                                    baseUrl = preset.defaultBaseUrl
                                    selectedModel = preset.defaultModel
                                    inputPrice = "${preset.defaultInputPrice}"
                                    outputPrice = "${preset.defaultOutputPrice}"
                                    currency = preset.currency
                                },
                                label = { Text(preset.name, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(strings.providerNameLabel) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(strings.baseUrlLabel) },
                        placeholder = { Text("https://api.deepseek.com/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(strings.apiKeyLabel) },
                        placeholder = { Text("sk-...") },
                        visualTransformation = if (isApiKeyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                                Icon(
                                    imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (isApiKeyVisible) "Hide API Key" else "Show API Key"
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = selectedModel,
                        onValueChange = { selectedModel = it },
                        label = { Text(strings.modelIdLabel) },
                        placeholder = { Text(strings.modelPlaceholder) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = inputPrice,
                            onValueChange = { inputPrice = it },
                            label = { Text(strings.inputPriceLabel) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = outputPrice,
                            onValueChange = { outputPrice = it },
                            label = { Text(strings.outputPriceLabel) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = maxContextTokens,
                            onValueChange = { maxContextTokens = it },
                            label = { Text("Max Context Tokens") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = currency == "USD",
                                onClick = { currency = "USD" },
                                label = { Text("USD ($)") }
                            )
                            FilterChip(
                                selected = currency == "CNY",
                                onClick = { currency = "CNY" },
                                label = { Text("CNY (¥)") }
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isDefault,
                            onCheckedChange = { isDefault = it }
                        )
                        Text(strings.setDefaultProviderCheck, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val inP = inputPrice.toDoubleOrNull() ?: 0.0
                    val outP = outputPrice.toDoubleOrNull() ?: 0.0
                    val maxCtx = maxContextTokens.toIntOrNull() ?: 8192
                    val entity = provider?.copy(
                        name = name.trim(),
                        providerType = providerType,
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        selectedModel = selectedModel.trim(),
                        inputPricePerMillion = inP,
                        outputPricePerMillion = outP,
                        currency = currency,
                        maxContextTokens = maxCtx,
                        temperature = temperature,
                        isDefault = isDefault
                    ) ?: ApiProviderEntity(
                        name = name.trim(),
                        providerType = providerType,
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        selectedModel = selectedModel.trim(),
                        inputPricePerMillion = inP,
                        outputPricePerMillion = outP,
                        currency = currency,
                        maxContextTokens = maxCtx,
                        temperature = temperature,
                        isDefault = isDefault
                    )
                    onSave(entity)
                },
                enabled = name.isNotBlank() && selectedModel.isNotBlank()
            ) {
                Text(strings.saveProvider)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
            }
        }
    )
}

