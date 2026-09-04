package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ethran.notable.ui.noRippleClickable

@Composable
fun VaultEntryPopupMenu(
    onDismiss: () -> Unit,
    alignment: Alignment = Alignment.TopStart,
    content: @Composable ColumnScope.() -> Unit
) {
    Popup(
        alignment = alignment,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            Modifier
                .border(1.dp, Color.Black, RectangleShape)
                .background(Color.White)
                .width(IntrinsicSize.Max),
            content = content
        )
    }
}

@Composable
fun VaultEntryMenuItem(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(10.dp)
            .noRippleClickable(onClick = onClick)
    ) {
        Text(label)
    }
}
