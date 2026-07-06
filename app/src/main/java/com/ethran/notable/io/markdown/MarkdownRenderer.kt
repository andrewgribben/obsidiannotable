package com.ethran.notable.io.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.front.matter.YamlFrontMatterBlock
import org.commonmark.ext.front.matter.YamlFrontMatterExtension
import org.commonmark.ext.front.matter.YamlFrontMatterVisitor
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/** A tappable link in the rendered text. */
data class MarkdownLink(
    val displayStart: Int,
    val displayEnd: Int,
    val target: String,
    val isWikilink: Boolean
)

/** An inline image placeholder in the rendered text. */
data class MarkdownImage(
    val inlineId: String,
    val destination: String,
    val displayStart: Int,
    val displayEnd: Int,
    /** Obsidian wiki-embed width hint in pixels, when provided. */
    val widthHintPx: Int? = null
)

/** Result of rendering a markdown document for the reader. */
data class RenderedMarkdown(
    val text: AnnotatedString,
    val sourceMap: MarkdownSourceMap,
    val links: List<MarkdownLink>,
    val images: List<MarkdownImage> = emptyList(),
    val frontmatter: Map<String, List<String>>,
    /** Source offset where the body (after frontmatter) starts. */
    val bodySourceStart: Int,
    /** Theme (and font scale) this document was rendered with. */
    val theme: MarkdownTheme
)

/**
 * Bear-style e-ink theme: high contrast, typographic hierarchy, no color noise.
 * Tags and wikilinks stay visible but styled. [scale] multiplies every font size —
 * it backs the reader's font-size control (bigger text is also easier to hit with
 * annotation gestures).
 */
class MarkdownTheme(val scale: Float = 1f) {
    val bodySize = 17.sp * scale
    val lineHeight = (26f * scale).sp

    val h1 = SpanStyle(fontSize = 28.sp * scale, fontWeight = FontWeight.Bold, color = ink)
    val h2 = SpanStyle(fontSize = 24.sp * scale, fontWeight = FontWeight.Bold, color = ink)
    val h3 = SpanStyle(fontSize = 20.sp * scale, fontWeight = FontWeight.Bold, color = ink)
    val h4plus = SpanStyle(fontSize = 18.sp * scale, fontWeight = FontWeight.SemiBold, color = ink)
    val body = SpanStyle(fontSize = bodySize, color = ink)
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    val italic = SpanStyle(fontStyle = FontStyle.Italic)

    // E-ink: keep struck text full black — a gray line-through dithers away on the panel.
    val strikethrough = SpanStyle(textDecoration = TextDecoration.LineThrough, color = ink)

    // E-ink: mid-gray background so the highlight survives 16-level grayscale.
    val highlight = SpanStyle(background = Color(0xFFBDBDBD), color = ink)
    val code = SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 15.sp * scale,
        background = Color(0xFFE8E8E8)
    )
    val tag = SpanStyle(color = subtle, background = chipBackground, fontWeight = FontWeight.Medium)
    val wikilink = SpanStyle(color = ink, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)
    val mdLink = SpanStyle(color = ink, textDecoration = TextDecoration.Underline)
    val quote = SpanStyle(fontStyle = FontStyle.Italic, color = subtle)
    val listMarker = SpanStyle(color = subtle)

    fun headingStyle(level: Int): SpanStyle = when (level) {
        1 -> h1
        2 -> h2
        3 -> h3
        else -> h4plus
    }

    companion object {
        val ink = Color.Black
        val subtle = Color(0xFF555555)
        val faint = Color(0xFF999999)
        val chipBackground = Color(0xFFDDDDDD)
    }
}

private val WIKILINK_REGEX = Regex("""\[\[([^\[\]]+)]]""")
private val WIKI_IMAGE_REGEX = Regex("""!\[\[([^\]]+)]]""")
private val TAG_REGEX = Regex("""(?<=^|[\s(])#([\p{L}\p{N}_/-]+)""")
private val HIGHLIGHT_REGEX = Regex("""==([^=\n]+)==""")

private fun parseWikiImageInner(inner: String): Pair<String, Int?> {
    val pipe = inner.indexOf('|')
    if (pipe < 0) return inner.trim() to null
    val target = inner.substring(0, pipe).trim()
    val suffix = inner.substring(pipe + 1).trim()
    return target to suffix.toIntOrNull()
}

/**
 * Parses markdown (with source spans) and renders it into an [AnnotatedString] plus a
 * [MarkdownSourceMap] for annotation editing.
 */
object MarkdownRenderer {

    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                StrikethroughExtension.create(),
                TaskListItemsExtension.create(),
                YamlFrontMatterExtension.create(),
                TablesExtension.create(),
                AutolinkExtension.create()
            )
        )
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        .build()

    /** Renders note text for the vault reader (hides `%%` blocks and excalidraw tails). */
    fun renderForReader(source: String, fontScale: Float = 1f): RenderedMarkdown =
        render(MarkdownReaderContent.prepareForTextView(source), fontScale)

    fun render(source: String, fontScale: Float = 1f): RenderedMarkdown {
        val document = parser.parse(source)

        val frontmatterVisitor = YamlFrontMatterVisitor()
        document.accept(frontmatterVisitor)

        val theme = MarkdownTheme(fontScale)
        val builder = Builder(source, theme)
        builder.renderBlocks(document as Document)

        var bodyStart = 0
        var child = document.firstChild
        while (child != null) {
            if (child is YamlFrontMatterBlock) {
                val span = child.sourceSpans.lastOrNull()
                if (span != null) bodyStart = span.inputIndex + span.length
                child = child.next
            } else break
        }

        return RenderedMarkdown(
            text = builder.annotated.toAnnotatedString(),
            sourceMap = MarkdownSourceMap(builder.segments),
            links = builder.links,
            images = builder.images,
            frontmatter = frontmatterVisitor.data,
            bodySourceStart = bodyStart,
            theme = theme
        )
    }

    private class Builder(val source: String, val theme: MarkdownTheme) {
        val annotated = AnnotatedString.Builder()
        val segments = mutableListOf<MarkdownSourceMap.Segment>()
        val links = mutableListOf<MarkdownLink>()
        val images = mutableListOf<MarkdownImage>()

        private var blockCount = 0
        private var imageCounter = 0

        val length get() = annotated.length

        fun appendDecoration(text: String, style: SpanStyle? = null) {
            val start = length
            annotated.append(text)
            if (style != null) annotated.addStyle(style, start, length)
        }

        fun appendMapped(
            displayText: String,
            sourceStart: Int,
            sourceEnd: Int,
            linear: Boolean,
            style: SpanStyle? = null
        ) {
            val start = length
            annotated.append(displayText)
            if (style != null) annotated.addStyle(style, start, length)
            if (sourceEnd > sourceStart) {
                segments.add(
                    MarkdownSourceMap.Segment(start, length, sourceStart, sourceEnd, linear)
                )
            }
        }

        private fun appendImage(
            destination: String,
            sourceStart: Int,
            sourceEnd: Int,
            alt: String = "",
            widthHintPx: Int? = null
        ) {
            val id = "mdimg_${imageCounter++}"
            val start = length
            annotated.appendInlineContent(id, alt.ifBlank { destination })
            val end = length
            if (sourceEnd > sourceStart) {
                segments.add(
                    MarkdownSourceMap.Segment(start, end, sourceStart, sourceEnd, linear = false)
                )
            }
            images.add(
                MarkdownImage(
                    inlineId = id,
                    destination = destination,
                    displayStart = start,
                    displayEnd = end,
                    widthHintPx = widthHintPx
                )
            )
        }

        fun renderBlocks(document: Document) {
            annotated.pushStyle(theme.body)
            var node = document.firstChild
            while (node != null) {
                renderBlock(node)
                node = node.next
            }
            annotated.pop()
        }

        private fun blockSpacing(tight: Boolean = false) {
            if (blockCount > 0) appendDecoration(if (tight) "\n" else "\n\n")
            blockCount++
        }

        private fun renderBlock(node: Node, quoteDepth: Int = 0, indent: Int = 0) {
            when (node) {
                is YamlFrontMatterBlock -> Unit // shown separately as a properties block
                is Heading -> {
                    blockSpacing()
                    val start = length
                    renderInlines(node.firstChild)
                    annotated.addStyle(theme.headingStyle(node.level), start, length)
                }
                is Paragraph -> {
                    blockSpacing()
                    if (quoteDepth > 0) appendDecoration("▎ ", theme.quote)
                    val start = length
                    renderInlines(node.firstChild)
                    if (quoteDepth > 0) annotated.addStyle(theme.quote, start, length)
                }
                is BulletList -> renderList(node, ordered = false, quoteDepth, indent)
                is OrderedList -> renderList(node, ordered = true, quoteDepth, indent)
                is BlockQuote -> {
                    var child = node.firstChild
                    while (child != null) {
                        renderBlock(child, quoteDepth + 1, indent)
                        child = child.next
                    }
                }
                is FencedCodeBlock -> {
                    blockSpacing()
                    val literal = node.literal.trimEnd('\n')
                    val spans = node.sourceSpans
                    val sourceStart = spans.firstOrNull()?.inputIndex ?: 0
                    val sourceEnd = spans.lastOrNull()?.let { it.inputIndex + it.length } ?: 0
                    appendMapped(literal, sourceStart, sourceEnd, linear = false, theme.code)
                }
                is IndentedCodeBlock -> {
                    blockSpacing()
                    val literal = node.literal.trimEnd('\n')
                    val spans = node.sourceSpans
                    val sourceStart = spans.firstOrNull()?.inputIndex ?: 0
                    val sourceEnd = spans.lastOrNull()?.let { it.inputIndex + it.length } ?: 0
                    appendMapped(literal, sourceStart, sourceEnd, linear = false, theme.code)
                }
                is ThematicBreak -> {
                    blockSpacing()
                    appendDecoration("――――――――――", SpanStyle(color = MarkdownTheme.faint))
                }
                is TableBlock -> renderTable(node)
                is HtmlBlock -> {
                    blockSpacing()
                    val spans = node.sourceSpans
                    val sourceStart = spans.firstOrNull()?.inputIndex ?: 0
                    val sourceEnd = spans.lastOrNull()?.let { it.inputIndex + it.length } ?: 0
                    appendMapped(
                        node.literal.trimEnd('\n'), sourceStart, sourceEnd,
                        linear = false, theme.code
                    )
                }
                else -> {
                    // Unknown block: render children as a paragraph
                    if (node.firstChild != null) {
                        blockSpacing()
                        renderInlines(node.firstChild)
                    }
                }
            }
        }

        /**
         * Renders a GFM table as rows of "cell │ cell" lines: header bold with a rule
         * under it. E-ink friendly (no grid drawing) and each cell keeps its normal
         * inline source mapping so annotations inside cells still work.
         */
        private fun renderTable(table: TableBlock) {
            blockSpacing()
            var firstRow = true
            var section = table.firstChild
            while (section != null) {
                var row = section.firstChild
                while (row != null) {
                    if (row is TableRow) {
                        if (!firstRow) appendDecoration("\n")
                        firstRow = false
                        renderTableRow(row, isHeader = section is TableHead)
                        if (section is TableHead) {
                            appendDecoration("\n")
                            appendDecoration(
                                "─────────────────────────",
                                SpanStyle(color = MarkdownTheme.faint)
                            )
                        }
                    }
                    row = row.next
                }
                section = section.next
            }
        }

        private fun renderTableRow(row: TableRow, isHeader: Boolean) {
            var cell = row.firstChild
            var firstCell = true
            while (cell != null) {
                if (cell is TableCell) {
                    if (!firstCell) {
                        appendDecoration("  │  ", SpanStyle(color = MarkdownTheme.faint))
                    }
                    firstCell = false
                    val start = length
                    renderInlines(cell.firstChild)
                    if (isHeader) annotated.addStyle(theme.bold, start, length)
                }
                cell = cell.next
            }
        }

        private fun renderList(list: Node, ordered: Boolean, quoteDepth: Int, indent: Int) {
            var item = list.firstChild
            var number = (list as? OrderedList)?.markerStartNumber ?: 1
            while (item != null) {
                if (item is ListItem) renderListItem(item, ordered, number, quoteDepth, indent)
                number++
                item = item.next
            }
        }

        private fun renderListItem(
            item: ListItem,
            ordered: Boolean,
            number: Int,
            quoteDepth: Int,
            indent: Int
        ) {
            var child = item.firstChild
            var firstParagraphDone = false
            while (child != null) {
                when (child) {
                    is Paragraph -> {
                        blockSpacing(tight = firstParagraphDone || (item.parent?.previous is ListItem) || item.previous is ListItem)
                        val indentText = "    ".repeat(indent)
                        if (indentText.isNotEmpty()) appendDecoration(indentText)
                        var taskMarker: TaskListItemMarker? = null
                        var inline = child.firstChild
                        if (inline is TaskListItemMarker) {
                            taskMarker = inline
                            inline = inline.next
                        }
                        val marker = when {
                            taskMarker != null && taskMarker.isChecked -> "☑ "
                            taskMarker != null -> "☐ "
                            ordered -> "$number. "
                            else -> "•  "
                        }
                        if (!firstParagraphDone) {
                            appendDecoration(marker, theme.listMarker)
                            firstParagraphDone = true
                        }
                        val start = length
                        renderInlines(inline)
                        if (taskMarker?.isChecked == true) {
                            annotated.addStyle(
                                SpanStyle(color = MarkdownTheme.faint),
                                start, length
                            )
                        }
                        if (quoteDepth > 0) annotated.addStyle(theme.quote, start, length)
                    }
                    is BulletList -> renderList(child, ordered = false, quoteDepth, indent + 1)
                    is OrderedList -> renderList(child, ordered = true, quoteDepth, indent + 1)
                    else -> renderBlock(child, quoteDepth, indent + 1)
                }
                child = child.next
            }
        }

        private fun renderInlines(first: Node?) {
            var node = first
            while (node != null) {
                renderInline(node)
                node = node.next
            }
        }

        private fun sourceRangeOf(node: Node): Pair<Int, Int> {
            val spans = node.sourceSpans
            val start = spans.firstOrNull()?.inputIndex ?: -1
            val end = spans.lastOrNull()?.let { it.inputIndex + it.length } ?: -1
            return start to end
        }

        private fun renderInline(node: Node) {
            when (node) {
                is Text -> renderTextLiteral(node)
                is Emphasis -> renderStyledContainer(node, theme.italic)
                is StrongEmphasis -> renderStyledContainer(node, theme.bold)
                is Strikethrough -> renderStyledContainer(node, theme.strikethrough)
                is Code -> {
                    val (s, e) = sourceRangeOf(node)
                    appendMapped(node.literal, s, e, linear = false, theme.code)
                }
                is Link -> {
                    val start = length
                    val (s, e) = sourceRangeOf(node)
                    // Display only the link text, mapped to the whole [text](target) source
                    annotated.pushStyle(theme.mdLink)
                    renderInlinesNonLinear(node.firstChild, s, e)
                    annotated.pop()
                    links.add(MarkdownLink(start, length, node.destination, isWikilink = false))
                }
                is Image -> {
                    val (s, e) = sourceRangeOf(node)
                    val alt = buildString {
                        var c = node.firstChild
                        while (c != null) {
                            if (c is Text) append(c.literal)
                            c = c.next
                        }
                    }
                    appendImage(node.destination, s, e, alt)
                }
                is SoftLineBreak -> appendDecoration("\n")
                is HardLineBreak -> appendDecoration("\n")
                is HtmlInline -> {
                    val (s, e) = sourceRangeOf(node)
                    appendMapped(node.literal, s, e, linear = false, theme.code)
                }
                else -> renderInlines(node.firstChild)
            }
        }

        /** Renders a styled inline container (bold/italic/strikethrough) with markup-aware mapping. */
        private fun renderStyledContainer(node: Node, style: SpanStyle) {
            val start = length
            val (s, e) = sourceRangeOf(node)
            annotated.pushStyle(style)
            renderInlinesNonLinear(node.firstChild, s, e)
            annotated.pop()
            // Ensure the whole construct maps to its full source range (including markers)
            if (s in 0 until e) {
                segments.add(MarkdownSourceMap.Segment(start, length, s, e, linear = false))
            }
        }

        /** Renders children of a construct whose source includes markup; children are
         * appended without their own linear mapping (parent supplies the range).
         * Links and wikilinks inside styled text still register as tappable. */
        private fun renderInlinesNonLinear(first: Node?, sourceStart: Int, sourceEnd: Int) {
            var node = first
            while (node != null) {
                when (node) {
                    is Text -> appendTextScanningWikilinks(node.literal)
                    is SoftLineBreak, is HardLineBreak -> annotated.append("\n")
                    is Code -> {
                        val cs = length
                        annotated.append(node.literal)
                        annotated.addStyle(theme.code, cs, length)
                    }
                    is Link -> {
                        val start = length
                        annotated.pushStyle(theme.mdLink)
                        renderInlinesNonLinear(node.firstChild, sourceStart, sourceEnd)
                        annotated.pop()
                        links.add(MarkdownLink(start, length, node.destination, isWikilink = false))
                    }
                    else -> renderInlinesNonLinear(node.firstChild, sourceStart, sourceEnd)
                }
                node = node.next
            }
        }

        /** Appends literal text, turning any embedded [[wikilinks]] into tappable links. */
        private fun appendTextScanningWikilinks(literal: String) {
            var pos = 0
            for (match in WIKILINK_REGEX.findAll(literal)) {
                if (match.range.first > pos) {
                    annotated.append(literal.substring(pos, match.range.first))
                }
                val inner = match.groupValues[1]
                val display = inner.substringAfter('|', inner.substringBefore('|'))
                    .ifBlank { inner }
                val target = inner.substringBefore('|').trim()
                val start = length
                annotated.pushStyle(theme.wikilink)
                annotated.append(display)
                annotated.pop()
                links.add(MarkdownLink(start, length, target, isWikilink = true))
                pos = match.range.last + 1
            }
            if (pos < literal.length) annotated.append(literal.substring(pos))
        }

        /**
         * Renders a plain Text node, scanning its literal for wikilinks, tags and
         * ==highlights== (which commonmark does not parse).
         */
        private fun renderTextLiteral(node: Text) {
            val literal = node.literal
            val (sourceStart, sourceEnd) = sourceRangeOf(node)
            if (sourceStart < 0) {
                appendDecoration(literal)
                return
            }

            data class Deco(val range: IntRange, val kind: Int, val inner: String)

            val decos = mutableListOf<Deco>()
            WIKI_IMAGE_REGEX.findAll(literal).forEach {
                decos.add(Deco(it.range, 3, it.groupValues[1]))
            }
            WIKILINK_REGEX.findAll(literal).forEach {
                if (decos.none { existing -> existing.range.intersects(it.range) })
                    decos.add(Deco(it.range, 0, it.groupValues[1]))
            }
            HIGHLIGHT_REGEX.findAll(literal).forEach { m ->
                if (decos.none { it.range.intersects(m.range) })
                    decos.add(Deco(m.range, 1, m.groupValues[1]))
            }
            TAG_REGEX.findAll(literal).forEach { m ->
                if (decos.none { it.range.intersects(m.range) })
                    decos.add(Deco(m.range, 2, m.groupValues[1]))
            }
            decos.sortBy { it.range.first }

            var pos = 0
            for (deco in decos) {
                if (deco.range.first > pos) {
                    val plain = literal.substring(pos, deco.range.first)
                    appendMapped(
                        plain,
                        sourceStart + pos,
                        sourceStart + deco.range.first,
                        linear = true
                    )
                }
                val decoSourceStart = sourceStart + deco.range.first
                val decoSourceEnd = sourceStart + deco.range.last + 1
                when (deco.kind) {
                    3 -> { // ![[wiki image embed]]
                        val (target, widthHint) = parseWikiImageInner(deco.inner)
                        appendImage(target, decoSourceStart, decoSourceEnd, widthHintPx = widthHint)
                    }
                    0 -> { // wikilink — display alias or target, tappable
                        val display = deco.inner.substringAfter('|', deco.inner.substringBefore('|'))
                            .ifBlank { deco.inner }
                        val target = deco.inner.substringBefore('|').trim()
                        val start = length
                        appendMapped(
                            display, decoSourceStart, decoSourceEnd,
                            linear = false, theme.wikilink
                        )
                        links.add(MarkdownLink(start, length, target, isWikilink = true))
                    }
                    1 -> { // ==highlight== — show inner text highlighted
                        appendMapped(
                            deco.inner, decoSourceStart, decoSourceEnd,
                            linear = false, theme.highlight
                        )
                    }
                    else -> { // #tag — keep the # visible, chip style
                        appendMapped(
                            "#${deco.inner}", decoSourceStart, decoSourceEnd,
                            linear = true, theme.tag
                        )
                    }
                }
                pos = deco.range.last + 1
            }
            if (pos < literal.length) {
                appendMapped(
                    literal.substring(pos),
                    sourceStart + pos,
                    sourceEnd,
                    linear = true
                )
            }
        }
    }
}

private fun IntRange.intersects(other: IntRange): Boolean =
    first <= other.last && other.first <= last
