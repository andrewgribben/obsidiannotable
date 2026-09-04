package com.ethran.notable.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import compose.icons.FeatherIcons
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.FileText
import com.ethran.notable.io.vault.VaultIndex
import com.ethran.notable.io.vault.VaultNote

/**
 * Obsidian-style quick switcher: recents first, fuzzy filename search as you type.
 * Selection navigates immediately; callers save the outgoing note in the background.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickSwitcher(
    index: VaultIndex,
    recentPaths: List<String>,
    onSelect: (VaultNote) -> Unit,
    onDismiss: () -> Unit,
    onAddToBookshelf: ((String) -> Unit)? = null
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val results = remember(query, index) {
        if (query.isBlank()) {
            val all = index.allNotes().ifEmpty { index.refresh() }
            val byPath = all.associateBy { it.relativePath }
            val recents = recentPaths.mapNotNull { byPath[it] }
            val rest = all.filter { it.relativePath !in recentPaths.toSet() }
            (recents + rest).take(30)
        } else {
            index.search(query)
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .border(2.dp, Color.Black, RoundedCornerShape(8.dp))
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 18.sp, color = Color.Black),
                cursorBrush = SolidColor(Color.Black),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.Gray, RoundedCornerShape(6.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        if (query.isEmpty()) {
                            Text("Find a note…", color = Color.Gray, fontSize = 18.sp)
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.height(420.dp)) {
                items(results, key = { it.relativePath }) { note ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onSelect(note) },
                                onLongClick = { onAddToBookshelf?.invoke(note.relativePath) }
                            )
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (note.hasInk) FeatherIcons.Edit3 else FeatherIcons.FileText,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.width(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                note.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black,
                                maxLines = 1
                            )
                            if (note.relativePath.contains('/')) {
                                Text(
                                    note.relativePath.substringBeforeLast('/'),
                                    fontSize = 12.sp,
                                    color = Color.Gray,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
                if (results.isEmpty()) {
                    item {
                        Text(
                            "No matching notes",
                            color = Color.Gray,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}
