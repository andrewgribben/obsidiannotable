package com.ethran.notable.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.ethran.notable.editor.utils.InkGestureClassifier
import com.ethran.notable.io.markdown.MarkdownEdit
import com.ethran.notable.io.markdown.MarkdownEdits
import com.ethran.notable.io.markdown.RenderedMarkdown

/** What a recognized annotation gesture will do to the markdown source on save. */
enum class AnnotationKind { HIGHLIGHT, BOLD, STRIKETHROUGH }

/** A staged (not yet saved) ink annotation over the rendered note. */
class PendingAnnotation(
    val kind: AnnotationKind,
    /** Range in the display text (AnnotatedString offsets). */
    val displayRange: IntRange,
    /** The raw ink points (overlay coordinates), for drawing the pending mark. */
    val inkPoints: List<Offset>,
    /** The affected display text, for showing what will change. */
    val affectedText: String
)

/** Staged annotations for the currently open note. */
class ReaderAnnotationState {
    val pending = mutableStateListOf<PendingAnnotation>()
    var inProgressStroke by mutableStateOf<List<Offset>>(emptyList())

    fun undo() {
        if (pending.isNotEmpty()) pending.removeAt(pending.lastIndex)
    }

    fun clear() {
        pending.clear()
        inProgressStroke = emptyList()
    }

    /** Converts staged annotations to source edits via the note's source map. */
    fun toEdits(rendered: RenderedMarkdown): List<MarkdownEdit> {
        return pending.mapNotNull { annotation ->
            val sourceRange = rendered.sourceMap.displayRangeToSource(
                annotation.displayRange.first, annotation.displayRange.last + 1
            ) ?: return@mapNotNull null
            when (annotation.kind) {
                AnnotationKind.HIGHLIGHT -> MarkdownEdit.Highlight(sourceRange)
                AnnotationKind.BOLD -> MarkdownEdit.Bold(sourceRange)
                AnnotationKind.STRIKETHROUGH -> MarkdownEdit.Strikethrough(sourceRange)
            }
        }
    }
}

/**
 * A horizontal stroke in the top part of a line's box (the spacing above its glyphs)
 * is treated as an underline of the line *above* — that's where you naturally draw
 * when underlining "too far" below the text.
 */
private const val UNDERLINE_PREV_LINE_BAND = 0.25f

/** Extra ink-capture space below the last line so it can be underlined/circled. */
private val CAPTURE_OVERSCAN_BOTTOM = 90.dp

/** Horizontal text inset inside the full-width capture area. */
private val TEXT_HORIZONTAL_PADDING = 20.dp

/**
 * Classifies a completed ink stroke against the text layout and produces the staged
 * annotation, or null when the gesture doesn't map to any text. [points] must be in
 * the text layout's coordinate space.
 *
 * Gesture mapping: circle → highlight, underline → bold (markdown has no underline),
 * strike through the letters → strikethrough. (Scribble-to-delete was removed: a
 * misread circle deleting words is far worse than no gesture at all.)
 */
fun buildPendingAnnotation(
    points: List<Offset>,
    layout: TextLayoutResult,
    displayText: String
): PendingAnnotation? {
    val classification = InkGestureClassifier.classify(
        points.map { InkGestureClassifier.Point(it.x, it.y) }
    )

    val kind: AnnotationKind
    val probeY: Float
    when (classification.gesture) {
        InkGestureClassifier.InkGesture.CIRCLE -> {
            kind = AnnotationKind.HIGHLIGHT
            probeY = (classification.top + classification.bottom) / 2f
        }
        InkGestureClassifier.InkGesture.HORIZONTAL_LINE -> {
            val strokeY = points.map { it.y }.average().toFloat()
            var line = layout.getLineForVerticalPosition(strokeY)
            val lineTop = layout.getLineTop(line)
            val lineBottom = layout.getLineBottom(line)
            val relative =
                if (lineBottom > lineTop) (strokeY - lineTop) / (lineBottom - lineTop) else 0.5f
            if (relative < UNDERLINE_PREV_LINE_BAND && line > 0) {
                // Drawn in the gap below the previous line's text: underline that line.
                line -= 1
                kind = AnnotationKind.BOLD
            } else {
                // Above the baseline = through the letters = strike; below = underline.
                kind = if (strokeY <= layout.getLineBaseline(line)) AnnotationKind.STRIKETHROUGH
                else AnnotationKind.BOLD
            }
            probeY = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        }
        InkGestureClassifier.InkGesture.OTHER -> return null
    }

    val line = layout.getLineForVerticalPosition(probeY)
    val lineCenterY = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
    val startOffset = layout.getOffsetForPosition(Offset(classification.left, lineCenterY))
    val endOffset = layout.getOffsetForPosition(Offset(classification.right, lineCenterY))
    if (endOffset <= startOffset) return null

    val expanded = MarkdownEdits.expandToWordBounds(displayText, startOffset, endOffset)
    if (expanded.isEmpty()) return null
    val affected = displayText.substring(
        expanded.first, (expanded.last + 1).coerceAtMost(displayText.length)
    ).trim()
    if (affected.isBlank()) return null

    return PendingAnnotation(
        kind = kind,
        displayRange = expanded,
        inkPoints = points,
        affectedText = affected
    )
}

/**
 * The reader body: rendered markdown text plus, in annotation mode, an ink capture
 * and pending-mark overlay. The capture area spans the full width (screen edge to
 * edge, the text itself is inset) and extends below the last line, so marks that
 * start in the margins or under the final line still register. Stroke points are
 * translated into the text layout's coordinate space before hit-testing.
 */
@Composable
fun AnnotatableReaderBody(
    rendered: RenderedMarkdown,
    annotationMode: Boolean,
    annotationState: ReaderAnnotationState,
    onLinkTap: (com.ethran.notable.io.markdown.MarkdownLink) -> Unit,
    onGestureRejected: () -> Unit
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var boxOrigin by remember { mutableStateOf(Offset.Zero) }
    var textOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { boxOrigin = it.positionInRoot() }
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = rendered.text,
                lineHeight = rendered.theme.lineHeight,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TEXT_HORIZONTAL_PADDING)
                    .onGloballyPositioned { textOrigin = it.positionInRoot() }
                    .pointerInput(rendered, annotationMode) {
                        if (annotationMode) return@pointerInput
                        detectTapGestures { position ->
                            val textLayout = layout ?: return@detectTapGestures
                            val offset = textLayout.getOffsetForPosition(position)
                            rendered.links
                                .firstOrNull { offset >= it.displayStart && offset < it.displayEnd }
                                ?.let(onLinkTap)
                        }
                    }
            )
            if (annotationMode) Spacer(Modifier.height(CAPTURE_OVERSCAN_BOTTOM))
        }

        if (annotationMode) {
            val currentPoints = remember { mutableStateListOf<Offset>() }
            // Overlay coords → text layout coords (text is inset within the overlay)
            val textOffset = textOrigin - boxOrigin
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(rendered) {
                        detectDragGestures(
                            onDragStart = { start ->
                                currentPoints.clear()
                                currentPoints.add(start)
                            },
                            onDrag = { change, _ ->
                                currentPoints.add(change.position)
                                annotationState.inProgressStroke = currentPoints.toList()
                            },
                            onDragEnd = {
                                val stroke = currentPoints.toList()
                                currentPoints.clear()
                                annotationState.inProgressStroke = emptyList()
                                val textLayout = layout
                                if (textLayout != null) {
                                    val annotation = buildPendingAnnotation(
                                        stroke.map { it - textOffset },
                                        textLayout,
                                        rendered.text.text
                                    )?.let { translated ->
                                        // Keep the raw overlay points for drawing
                                        PendingAnnotation(
                                            kind = translated.kind,
                                            displayRange = translated.displayRange,
                                            inkPoints = stroke,
                                            affectedText = translated.affectedText
                                        )
                                    }
                                    if (annotation != null) {
                                        annotationState.pending.add(annotation)
                                    } else {
                                        onGestureRejected()
                                    }
                                }
                            },
                            onDragCancel = {
                                currentPoints.clear()
                                annotationState.inProgressStroke = emptyList()
                            }
                        )
                    }
            ) {
                val inkStroke = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                for (annotation in annotationState.pending) {
                    drawPath(pathOf(annotation.inkPoints), Color(0xFF444444), style = inkStroke)
                }
                if (annotationState.inProgressStroke.size > 1) {
                    drawPath(
                        pathOf(annotationState.inProgressStroke),
                        Color.Black,
                        style = inkStroke
                    )
                }
            }
        }
    }
}

private fun pathOf(points: List<Offset>): Path {
    val path = Path()
    points.firstOrNull()?.let { path.moveTo(it.x, it.y) }
    for (point in points.drop(1)) path.lineTo(point.x, point.y)
    return path
}
