package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.parser.IncludeSourceSpans

internal data class NativeMarkdownBlock(
    val kind: String, val text: AnnotatedString = AnnotatedString(""),
    val level: Int = 0, val children: List<NativeMarkdownBlock> = emptyList(),
)

/** No HTML execution, remote images or synchronous parsing on the UI thread. */
private fun markdownParser(): Parser = Parser.builder()
    .includeSourceSpans(IncludeSourceSpans.BLOCKS)
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create())).build()

internal fun parseNativeMarkdown(source: String, parser: Parser = markdownParser(),
    cache: MutableMap<String, NativeMarkdownBlock> = mutableMapOf()): List<NativeMarkdownBlock> {
    fun inline(root: Node): AnnotatedString = buildAnnotatedString {
        fun visit(node: Node) {
            when (node) {
                is org.commonmark.node.Text -> append(node.literal)
                is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(node.literal) }
                is SoftLineBreak -> append(" ")
                is HardLineBreak -> append("\n")
                is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { walk(node) { visit(it) } }
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { walk(node) { visit(it) } }
                is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { walk(node) { visit(it) } }
                is Link -> {
                    pushStringAnnotation("url", node.destination)
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { walk(node) { visit(it) } }
                    pop()
                }
                is HtmlInline -> append(node.literal)
                else -> walk(node) { visit(it) }
            }
        }
        walk(root) { visit(it) }
    }
    fun block(node: Node): NativeMarkdownBlock = when (node) {
        is FencedCodeBlock -> NativeMarkdownBlock("code", AnnotatedString(node.literal.removeSuffix("\n")))
        is IndentedCodeBlock -> NativeMarkdownBlock("code", AnnotatedString(node.literal.removeSuffix("\n")))
        is Heading -> NativeMarkdownBlock("heading", inline(node), node.level)
        is ThematicBreak -> NativeMarkdownBlock("divider")
        is HtmlBlock -> NativeMarkdownBlock("text", AnnotatedString(node.literal))
        is BulletList -> NativeMarkdownBlock("list", children = children(node).mapIndexed { i, child ->
            block(child).copy(text = AnnotatedString("•"), level = i)
        })
        is OrderedList -> NativeMarkdownBlock("list", children = children(node).mapIndexed { i, child ->
            block(child).copy(text = AnnotatedString("${(node.markerStartNumber ?: 1) + i}."), level = i)
        })
        is ListItem -> NativeMarkdownBlock("item", children = children(node).map { block(it) })
        is BlockQuote -> NativeMarkdownBlock("quote", children = children(node).map { block(it) })
        is TableBlock -> NativeMarkdownBlock("table", children = children(node).flatMap { children(it) }.map { block(it) })
        is TableRow -> NativeMarkdownBlock("row", children = children(node).map { NativeMarkdownBlock("text", inline(it)) })
        else -> NativeMarkdownBlock("text", inline(node))
    }
    val retained = mutableSetOf<String>()
    val reusable = !source.contains("]:")
    if (!reusable) cache.clear()
    val result = children(parser.parse(source)).filterNot { it is LinkReferenceDefinition }.map { node ->
        val spans = node.sourceSpans
        val raw = if (spans.isEmpty()) null else source.substring(spans.first().inputIndex,
            spans.last().inputIndex + spans.last().length)
        if (raw == null || !reusable) block(node) else {
            retained.add(raw)
            cache[raw] ?: block(node).also { cache[raw] = it }
        }
    }
    // Old versions of an unfinished tail must not accumulate throughout a long generation.
    cache.keys.retainAll(retained)
    return result
}

private fun walk(node: Node, action: (Node) -> Unit) { children(node).forEach(action) }
private fun children(node: Node): List<Node> = buildList {
    var child = node.firstChild
    while (child != null) { add(child); child = child.next }
}

@Composable
internal fun NativeMarkdown(text: String, modifier: Modifier = Modifier) {
    val latest by rememberUpdatedState(text)
    var blocks by remember { mutableStateOf<List<NativeMarkdownBlock>?>(null) }
    // Parsing is serial and conflated, so a fast stream cannot continually cancel the parser.
    LaunchedEffect(Unit) {
        val parser = markdownParser()
        val cache = mutableMapOf<String, NativeMarkdownBlock>()
        snapshotFlow { latest }.conflate().collect { value ->
            blocks = withContext(Dispatchers.Default) { parseNativeMarkdown(value, parser, cache) }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val parsed = blocks
        if (parsed == null) Text(text, style = MaterialTheme.typography.bodyMedium)
        else parsed.forEachIndexed { index, block -> key(index) { MarkdownBlock(block) } }
    }
}

@Composable
private fun MarkdownBlock(block: NativeMarkdownBlock) {
    when (block.kind) {
        "divider" -> HorizontalDivider()
        "code" -> NativeCodeBlock(block.text.text)
        "list" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { block.children.forEach { MarkdownBlock(it) } }
        "item" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(block.text, style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { block.children.forEach { MarkdownBlock(it) } }
        }
        "quote" -> Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                block.children.forEach { MarkdownBlock(it) }
            }
        }
        "table" -> Column(Modifier.horizontalScroll(rememberScrollState())) {
            block.children.forEachIndexed { index, row ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    row.children.forEach { cell ->
                        Surface(color = if (index == 0) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow) {
                            Box(Modifier.width(180.dp).fillMaxHeight().padding(8.dp)) { MarkdownInline(cell.text, index == 0) }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
        else -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.bodyMedium
            }
            MarkdownInline(block.text, block.kind == "heading", style)
        }
    }
}

@Composable
private fun MarkdownInline(text: AnnotatedString, bold: Boolean = false, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val uri = LocalUriHandler.current
    val resolved = style.copy(color = MaterialTheme.colorScheme.onSurface,
        fontWeight = if (bold) FontWeight.SemiBold else style.fontWeight)
    if (text.getStringAnnotations("url", 0, text.length).isEmpty()) Text(text, style = resolved)
    else ClickableText(text, style = resolved, onClick = { offset ->
        text.getStringAnnotations("url", offset, offset).firstOrNull()?.item?.let { target ->
            if (target.startsWith("https://", true) || target.startsWith("http://", true) || target.startsWith("mailto:", true))
                runCatching { uri.openUri(target) }
        }
    })
}

@Composable
internal fun NativeCodeBlock(text: String) {
    val clipboard = LocalClipboardManager.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text(stringResource(R.string.native_copy_code)) }
            }
            SelectionContainer { Text(text, Modifier.horizontalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
        }
    }
}
