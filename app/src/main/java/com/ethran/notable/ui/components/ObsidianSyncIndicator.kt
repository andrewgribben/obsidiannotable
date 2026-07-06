package com.ethran.notable.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.ethran.notable.ui.noRippleClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.RefreshCcw
import kotlinx.coroutines.delay

/**
 * Home-header sync control: spins in discrete steps while [syncing] (e-ink friendly),
 * tap triggers a manual sync.
 */
@Composable
fun ObsidianSyncIndicator(
    syncing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var rotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(syncing) {
        if (!syncing) {
            rotation = 0f
            return@LaunchedEffect
        }
        while (true) {
            delay(200)
            rotation = (rotation + 45f) % 360f
        }
    }
    Icon(
        imageVector = FeatherIcons.RefreshCcw,
        contentDescription = if (syncing) "Syncing" else "Sync vaults",
        tint = if (syncing) Color.Black else Color.Gray,
        modifier = modifier
            .graphicsLayer { rotationZ = rotation }
            .noRippleClickable(onClick = onClick)
            .padding(8.dp)
    )
}
