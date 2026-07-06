package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.ObsidianRemoteVaultRef

@Composable
fun ObsidianSyncSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onSignIn: (email: String, password: String, mfa: String, onResult: (Result<Unit>) -> Unit) -> Unit,
    onSignOut: (onComplete: () -> Unit) -> Unit,
    onSaveE2ePassword: (vaultConfigId: String, password: String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var emailInput by remember(settings.obsidianSyncEmail) {
        mutableStateOf(settings.obsidianSyncEmail)
    }
    var passwordInput by remember { mutableStateOf("") }
    var mfaInput by remember { mutableStateOf("") }
    var signingIn by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text(
            "Obsidian Sync",
            style = MaterialTheme.typography.h6,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Sign in once with your Obsidian account, then bind each local vault to a remote vault below.",
            style = MaterialTheme.typography.body2,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (settings.obsidianSyncSignedIn) {
            Text(
                "Signed in as ${settings.obsidianSyncEmail}",
                style = MaterialTheme.typography.body1,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "${settings.obsidianRemoteVaults.size} remote vault(s) available",
                style = MaterialTheme.typography.caption,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .border(1.dp, Color.Gray, RoundedCornerShape(6.dp))
                    .clickable {
                        onSignOut {
                            passwordInput = ""
                            mfaInput = ""
                            statusText = null
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Sign out", fontSize = 14.sp)
            }
        } else {
            ObsidianSyncField(label = "Email", value = emailInput, onValueChange = { emailInput = it })
            Spacer(modifier = Modifier.height(8.dp))
            ObsidianSyncField(
                label = "Password",
                value = passwordInput,
                onValueChange = { passwordInput = it },
                password = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            ObsidianSyncField(label = "MFA code (optional)", value = mfaInput, onValueChange = { mfaInput = it })
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .border(1.dp, Color.Black, RoundedCornerShape(6.dp))
                    .clickable(enabled = !signingIn) {
                        signingIn = true
                        statusText = "Signing in…"
                        onSignIn(emailInput.trim(), passwordInput, mfaInput.trim()) { result ->
                            signingIn = false
                            result.onSuccess {
                                statusText = "Signed in"
                                passwordInput = ""
                                mfaInput = ""
                            }.onFailure { e ->
                                statusText = e.message ?: "Sign-in failed"
                            }
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(if (signingIn) "Signing in…" else "Sign in", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        statusText?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.caption, color = Color.Gray)
        }
    }

    SettingsDivider()
}

@Composable
fun ObsidianVaultSyncRow(
    remoteVaults: List<ObsidianRemoteVaultRef>,
    syncEnabled: Boolean,
    selectedRemoteId: String,
    selectedRemoteName: String,
    e2ePassword: String,
    onSyncEnabledChange: (Boolean) -> Unit,
    onRemoteVaultSelected: (ObsidianRemoteVaultRef) -> Unit,
    onE2ePasswordChange: (String) -> Unit,
) {
    if (remoteVaults.isEmpty()) return

    Spacer(modifier = Modifier.height(10.dp))
    Text("Obsidian Sync", style = MaterialTheme.typography.caption, color = Color.Gray, fontWeight = FontWeight.Bold)

    Spacer(modifier = Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .border(1.dp, if (syncEnabled) Color.Black else Color.Gray, RoundedCornerShape(6.dp))
                .background(if (syncEnabled) Color.Black else Color.White, RoundedCornerShape(6.dp))
                .clickable { onSyncEnabledChange(!syncEnabled) }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                if (syncEnabled) "Sync on" else "Sync off",
                color = if (syncEnabled) Color.White else Color.Black,
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.padding(horizontal = 6.dp))
        Text(
            selectedRemoteName.ifBlank { "No remote vault selected" },
            style = MaterialTheme.typography.body2,
            color = if (selectedRemoteId.isBlank()) Color.Gray else Color.Black
        )
    }

    if (syncEnabled) {
        Spacer(modifier = Modifier.height(8.dp))
        Text("Remote vault", style = MaterialTheme.typography.caption, color = Color.Gray)
        remoteVaults.forEach { remote ->
            val selected = remote.id == selectedRemoteId
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .border(
                        1.dp,
                        if (selected) Color.Black else Color.LightGray,
                        RoundedCornerShape(6.dp)
                    )
                    .clickable { onRemoteVaultSelected(remote) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    remote.name + if (remote.encryptionVersion in 2..3) " (E2E)" else "",
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                )
            }
        }

        val needsE2e = remoteVaults.find { it.id == selectedRemoteId }?.encryptionVersion in 2..3
        if (needsE2e) {
            Spacer(modifier = Modifier.height(8.dp))
            ObsidianSyncField(
                label = "E2E password",
                value = e2ePassword,
                onValueChange = onE2ePasswordChange,
                password = true
            )
        }
    }
}

@Composable
private fun ObsidianSyncField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    password: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    Text(label, style = MaterialTheme.typography.caption, color = Color.Gray)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(fontSize = 16.sp, color = Color.Black),
        singleLine = true,
        cursorBrush = SolidColor(Color.Black),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.Gray, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
