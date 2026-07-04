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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
 * Bottom toolbar shown in the editor for flip-side pages.
 *
 * Flip purpose: "To text" runs HWR over the sketch and previews the result with
 * replace-or-append into the linked note (the sketch itself auto-exports to the
 * .excalidraw.md sidecar when the editor closes).
 *
 * Insert purpose (handwritten entry into an existing note): "Save to note" appends the
 * recognized markdown and discards the scratch page.
 */
@Composable
fun FlipSideToolbar(
    appRepository: AppRepository,
    pageId: String,
    noteRelativePath: String,
    isInsertMode: Boolean,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRecognizing by remember { mutableStateOf(false) }
    var previewText by remember { mutableStateOf<String?>(null) }

    fun snack(text: String, duration: Int = 4000) {
        SnackState.globalSnackFlow.tryEmit(SnackConf(text = text, duration = duration))
    }

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
                text = (if (isInsertMode) "Write into: " else "Flip side of: ") +
                    flipSideNoteName(noteRelativePath),
                fontSize = 13.sp,
                color = Color.DarkGray
            )
            Spacer(Modifier.width(14.dp))
            if (isInsertMode) {
                ToolbarButton(
                    label = if (isRecognizing) "Recognizing…" else "Save to note",
                    filled = true,
                    enabled = !isRecognizing
                ) {
                    isRecognizing = true
                    scope.launch(Dispatchers.IO) {
                        val message =
                            FlipSideManager.completeInsertPage(appRepository, context, pageId)
                        withContext(Dispatchers.Main) {
                            isRecognizing = false
                            snack(message)
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
            } else {
                ToolbarButton(
                    label = if (isRecognizing) "Recognizing…" else "To text",
                    filled = true,
                    enabled = !isRecognizing
                ) {
                    isRecognizing = true
                    scope.launch(Dispatchers.IO) {
                        val text = FlipSideManager.recognizeFlipSide(appRepository, context, pageId)
                        withContext(Dispatchers.Main) {
                            isRecognizing = false
                            if (text == null) snack("Nothing recognized on this flip side")
                            else previewText = text
                        }
                    }
                }
            }
        }
    }

    val preview = previewText
    if (preview != null) {
        Dialog(onDismissRequest = { previewText = null }) {
            Column(
                Modifier
                    .border(2.dp, Color.Black, RoundedCornerShape(8.dp))
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Text(
                    "Recognized text",
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    preview,
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
                    ToolbarButton(label = "Replace note body", filled = false) {
                        previewText = null
                        scope.launch(Dispatchers.IO) {
                            val message = FlipSideManager.applyTextToNote(
                                appRepository, pageId, preview,
                                FlipSideManager.HwrApplyMode.REPLACE
                            )
                            snack(message)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    ToolbarButton(label = "Append to note", filled = true) {
                        previewText = null
                        scope.launch(Dispatchers.IO) {
                            val message = FlipSideManager.applyTextToNote(
                                appRepository, pageId, preview,
                                FlipSideManager.HwrApplyMode.APPEND
                            )
                            snack(message)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    ToolbarButton(label = "Cancel", filled = false) { previewText = null }
                }
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
