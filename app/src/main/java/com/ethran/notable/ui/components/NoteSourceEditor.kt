package com.ethran.notable.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Multiline source editor for vault note markdown body (Phase 1 Source mode). */
@Composable
fun NoteSourceEditor(
    value: String,
    onValueChange: (String) -> Unit,
    showDrawingPreservedHint: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.Black,
                lineHeight = 22.sp
            ),
            cursorBrush = SolidColor(Color.Black),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )
        if (showDrawingPreservedHint) {
            Text(
                text = "Drawing section preserved",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFDDDDDD), RectangleShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}
