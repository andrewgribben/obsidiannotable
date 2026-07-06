package com.ethran.notable.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.io.flipside.FlipSideManager
import com.ethran.notable.io.flipside.flipSideNoteName
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackState
import com.ethran.notable.ui.noRippleClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom bar for handwritten-entry pages (writing new text into an existing note):
 * "Save to note" appends the recognized markdown and discards the scratch page.
 *
 * Flip-side drawing pages have no bar — the sketch auto-saves to its sidecar, and
 * the optional HWR "To text" action lives in the editor sidebar
 * (with [FlipTextPreviewDialog] for the replace/append choice).
 */
@Composable
fun FlipSideToolbar(
    appRepository: AppRepository,
    pageId: String,
    noteRelativePath: String,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRecognizing by remember { mutableStateOf(false) }
    val recognizingLabel = stringResource(R.string.flip_side_recognizing)
    val saveToNoteLabel = stringResource(R.string.flip_side_save_to_note)

    Box(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .border(1.dp, Color.Black, RoundedCornerShape(8.dp))
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Write into: " + flipSideNoteName(noteRelativePath),
                fontSize = 13.sp,
                color = Color.DarkGray
            )
            Spacer(Modifier.width(14.dp))
            ToolbarButton(
                label = if (isRecognizing) recognizingLabel else saveToNoteLabel,
                filled = true,
                enabled = !isRecognizing
            ) {
                isRecognizing = true
                scope.launch(Dispatchers.IO) {
                    val message =
                        FlipSideManager.completeInsertPage(appRepository, context, pageId)
                    withContext(Dispatchers.Main) {
                        isRecognizing = false
                        SnackState.globalSnackFlow.tryEmit(
                            SnackConf(text = message, duration = 4000)
                        )
                        if (!message.startsWith("Nothing")) onExit()
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            ToolbarButton(label = "Discard", filled = false) {
                scope.launch(Dispatchers.IO) {
                    FlipSideManager.discardInsertPage(appRepository, pageId)
                    withContext(Dispatchers.Main) { onExit() }
                }
            }
        }
    }
}

/**
 * Preview of a flip side's recognized text with the replace/append choice.
 * [onApplied] runs after the text is written to the note (used to navigate back to
 * it); Cancel just closes and stays on the drawing.
 */
@Composable
fun FlipTextPreviewDialog(
    appRepository: AppRepository,
    pageId: String,
    text: String,
    onApplied: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.flip_side_recognized_text_title)
    val replaceLabel = stringResource(R.string.flip_side_replace_note_body)
    val appendLabel = stringResource(R.string.flip_side_append_to_note)
    val cancelLabel = stringResource(android.R.string.cancel)

    fun apply(mode: FlipSideManager.HwrApplyMode) {
        scope.launch(Dispatchers.IO) {
            val message = FlipSideManager.applyTextToNote(appRepository, pageId, text, mode)
            SnackState.globalSnackFlow.tryEmit(SnackConf(text = message, duration = 4000))
            withContext(Dispatchers.Main) { onApplied() }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .border(2.dp, Color.Black, RoundedCornerShape(8.dp))
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState())
                    .border(1.dp, Color.LightGray, RoundedCornerShape(6.dp))
                    .padding(10.dp)
            )
            Spacer(Modifier.height(14.dp))
            Row {
                ToolbarButton(label = replaceLabel, filled = false) {
                    apply(FlipSideManager.HwrApplyMode.REPLACE)
                }
                Spacer(Modifier.width(10.dp))
                ToolbarButton(label = appendLabel, filled = true) {
                    apply(FlipSideManager.HwrApplyMode.APPEND)
                }
                Spacer(Modifier.width(10.dp))
                ToolbarButton(label = cancelLabel, filled = false) { onDismiss() }
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    label: String,
    filled: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .border(1.dp, Color.Black, RoundedCornerShape(6.dp))
            .background(if (filled) Color.Black else Color.White, RoundedCornerShape(6.dp))
            .noRippleClickable { if (enabled) onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color = if (filled) Color.White else Color.Black,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
