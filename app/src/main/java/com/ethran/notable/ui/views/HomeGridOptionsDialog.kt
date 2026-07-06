package com.ethran.notable.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ethran.notable.data.datastore.VaultConfig
import com.ethran.notable.ui.noRippleClickable
import com.ethran.notable.ui.viewmodels.HomeSort

@Composable
fun HomeGridOptionsDialog(
    vaults: List<VaultConfig>,
    currentSort: String,
    vaultFilterIds: Set<String>,
    onApply: (sortMode: String, vaultFilterIds: Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var sortMode by remember(currentSort) { mutableStateOf(currentSort) }
    var filterIds by remember(vaultFilterIds) { mutableStateOf(vaultFilterIds) }
    val allVaults = filterIds.isEmpty()
    val sortModes = HomeSort.modes

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(2.dp, Color.Black, RoundedCornerShape(8.dp))
                .background(Color.White, RoundedCornerShape(8.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Home grid", style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("Sort by", fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            sortModes.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .noRippleClickable { sortMode = mode }
                        .padding(vertical = 8.dp)
                ) {
                    OptionRadio(selected = mode == sortMode)
                    Spacer(Modifier.width(12.dp))
                    Text(HomeSort.label(mode), fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Vaults", fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .noRippleClickable { filterIds = emptySet() }
                    .padding(vertical = 8.dp)
            ) {
                OptionRadio(selected = allVaults)
                Spacer(Modifier.width(12.dp))
                Text("All vaults", fontSize = 16.sp)
            }
            vaults.forEach { vault ->
                val selected = !allVaults && vault.id in filterIds
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .noRippleClickable {
                            val next = filterIds.toMutableSet()
                            if (allVaults) {
                                filterIds = setOf(vault.id)
                            } else if (vault.id in next) {
                                next.remove(vault.id)
                                filterIds = if (next.isEmpty()) emptySet() else next
                            } else {
                                next.add(vault.id)
                                filterIds = next
                            }
                        }
                        .padding(vertical = 8.dp)
                ) {
                    OptionCheckbox(selected = selected || allVaults)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(vault.displayName, fontSize = 16.sp)
                        if (vault.inboxPath.isNotBlank()) {
                            Text(vault.inboxPath, fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
            ) {
                Text(
                    "Cancel",
                    modifier = Modifier
                        .noRippleClickable(onClick = onDismiss)
                        .padding(12.dp),
                    color = Color.DarkGray
                )
                Text(
                    "Apply",
                    modifier = Modifier
                        .noRippleClickable {
                            onApply(sortMode, filterIds)
                            onDismiss()
                        }
                        .padding(12.dp),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun OptionRadio(selected: Boolean) {
    Box(
        Modifier
            .size(14.dp)
            .border(1.dp, Color.Black, RoundedCornerShape(7.dp))
            .background(
                if (selected) Color.Black else Color.White,
                RoundedCornerShape(7.dp)
            )
    )
}

@Composable
private fun OptionCheckbox(selected: Boolean) {
    Box(
        Modifier
            .size(14.dp)
            .border(1.dp, Color.Black, RoundedCornerShape(2.dp))
            .background(if (selected) Color.Black else Color.White)
    )
}
