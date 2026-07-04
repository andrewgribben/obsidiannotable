package com.ethran.notable.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import com.ethran.notable.editor.utils.InkGestureClassifier
import com.ethran.notable.io.markdown.MarkdownEdit
import com.ethran.notable.io.markdown.MarkdownEdits
import com.ethran.notable.io.markdown.RenderedMarkdown

/** What a recognized annotation gesture will do to the markdown source on save. */
enum class AnnotationKind { HIGHLIGHT, BOLD, STRIKETHROUGH, DELETE }

/** A staged (not yet saved) ink annotation over the rendered note. */
class PendingAnnotation(
    val kind: AnnotationKind,
    /** Range in the display text (AnnotatedString offsets). */
    val displayRange: IntRange,
    /** The raw ink points, for drawing the pending mark. */
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
                AnnotationKind.DELETE -> MarkdownEdit.Delete(sourceRange)
            }
        }
    }
}

/** Where within a text line's box a horizontal stroke flips from strike to underline. */
private const val STRIKE_UNDERLINE_BOUNDARY = 0.68f

/**
 * Classifies a completed ink stroke against the text layout and produces the staged
 * annotation, or null when the gesture doesn't map to any text.
 *
 * Gesture mapping: circle → highlight, underline → bold (markdown has no underline),
 * strike through the x-height band → strikethrough, dense scrawl → delete.
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
        InkGestureClassifier.InkGesture.SCRAWL -> {
            kind = AnnotationKind.DELETE
            probeY = (classification.top + classification.bottom) / 2f
        }
        InkGestureClassifier.InkGesture.HORIZONTAL_LINE -> {
            val strokeY = points.map { it.y }.average().toFloat()
            val line = layout.getLineForVerticalPosition(strokeY)
            val lineTop = layout.getLineTop(line)
            val lineBottom = layout.getLineBottom(line)
            val relative =
                if (lineBottom > lineTop) (strokeY - lineTop) / (lineBottom - lineTop) else 0.5f
            kind = if (relative < STRIKE_UNDERLINE_BOUNDARY) AnnotationKind.STRIKETHROUGH
            else AnnotationKind.BOLD
            probeY = (lineTop + lineBottom) / 2f
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
 * and pending-mark overlay drawn in the same coordinate space as the text.
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

    Box(Modifier.fillMaxWidth()) {
        Text(
            text = rendered.text,
            lineHeight = 26.sp,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
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

        if (annotationMode) {
            val currentPoints = remember { mutableStateListOf<Offset>() }
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
                                        stroke, textLayout, rendered.text.text
                                    )
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
