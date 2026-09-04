package com.ethran.notable.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ethran.notable.R

/** Shared icon for switching between vault text view and flip-side drawing. */
@Composable
fun SingularityToggleIcon(
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    tint: Color = Color.Black
) {
    Icon(
        painter = painterResource(R.drawable.ic_singularity_toggle),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}
