package com.breakyuna.noveltranslator.ui.screens.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.core.parser.TxtParser
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.i18n.platformUiStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderChapterResplitDialog(
    currentChapterCount: Int,
    english: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (regexPattern: String, cropTableOfContents: Boolean) -> Unit
) {
    val strings = platformUiStrings()
    val appStrings = LocalAppStrings.current
    var selectedPreset by remember { mutableStateOf("default") }
    var regexText by remember { mutableStateOf("") }
    var cropTableOfContents by remember { mutableStateOf(false) }
    val regexError = remember(regexText) {
        regexText.takeIf(String::isNotBlank)?.let { candidate ->
            runCatching { TxtParser.validateChapterRegex(candidate) }
                .exceptionOrNull()
                ?.localizedMessage
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (english) "Chapter parsing" else "重新分章") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    if (english) {
                        "Adjust the TXT chapter heading rule or remove a leading table of contents."
                    } else {
                        "可以调整 TXT 章节标题正则，或裁剪开头目录。修改后会重建本书的章节与阅读位置。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (english) "$currentChapterCount chapters currently detected" else "当前识别到 $currentChapterCount 章",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedPreset == "default",
                        onClick = {
                            selectedPreset = "default"
                            regexText = ""
                        },
                        label = { Text(appStrings.presetChinese) }
                    )
                    FilterChip(
                        selected = selectedPreset == "english",
                        onClick = {
                            selectedPreset = "english"
                            regexText = TxtParser.REGEX_ENGLISH
                        },
                        label = { Text(appStrings.presetEnglish) }
                    )
                    FilterChip(
                        selected = selectedPreset == "markdown",
                        onClick = {
                            selectedPreset = "markdown"
                            regexText = TxtParser.REGEX_MARKDOWN
                        },
                        label = { Text(appStrings.presetMarkdown) }
                    )
                }
                OutlinedTextField(
                    value = regexText,
                    onValueChange = {
                        regexText = it
                        selectedPreset = "custom"
                    },
                    label = { Text(if (english) "Custom chapter regex" else strings.customChapterRegex) },
                    placeholder = { Text(strings.customChapterRegexHint) },
                    supportingText = {
                        Text(
                            regexError ?: strings.customChapterRegexHint,
                            color = if (regexError == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                        )
                    },
                    isError = regexError != null,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings.cropTableOfContents, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            strings.cropTableOfContentsHint,
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
                enabled = regexError == null,
                onClick = { onConfirm(regexText, cropTableOfContents) }
            ) {
                Text(strings.resplitChaptersConfirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } }
    )
}
