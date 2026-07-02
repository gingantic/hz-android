package com.rhnxdev.hzplayer.presentation.network.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.domain.model.NetworkProtocol
import com.rhnxdev.hzplayer.domain.model.ServerConfig

@Composable
fun ServerConfigDialog(
    initialServer: ServerConfig?,
    onSave: (ServerConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val isEditing = initialServer != null

    var name by remember { mutableStateOf(initialServer?.name ?: "") }
    var protocol by remember { mutableStateOf(initialServer?.protocol ?: NetworkProtocol.FTP) }
    var host by remember { mutableStateOf(initialServer?.host ?: "") }
    var port by remember {
        mutableStateOf(
            initialServer?.port?.toString() ?: defaultPort(NetworkProtocol.FTP).toString(),
        )
    }
    var username by remember { mutableStateOf(initialServer?.username ?: "") }
    var password by remember { mutableStateOf(initialServer?.password ?: "") }
    var basePath by remember { mutableStateOf(initialServer?.basePath ?: "/") }
    var showPassword by remember { mutableStateOf(false) }

    var nameError by remember { mutableStateOf(false) }
    var hostError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Edit Server" else "Add Server") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameError = false },
                    label = { Text("Name") },
                    placeholder = { Text("My Server") },
                    isError = nameError,
                    supportingText = if (nameError) {{ Text("Name is required") }} else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    NetworkProtocol.entries.forEach { proto ->
                        FilterChip(
                            selected = protocol == proto,
                            onClick = {
                                protocol = proto
                                port = defaultPort(proto).toString()
                            },
                            label = { Text(proto.name) },
                        )
                    }
                }

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; hostError = false },
                    label = { Text("Host") },
                    placeholder = { Text("192.168.1.100") },
                    isError = hostError,
                    supportingText = if (hostError) {{ Text("Host is required") }} else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    placeholder = { Text("anonymous") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (showPassword) "Hide" else "Show",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = basePath,
                    onValueChange = { basePath = it },
                    label = { Text("Base Path") },
                    placeholder = { Text("/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(Spacing.xs))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    nameError = name.isBlank()
                    hostError = host.isBlank()
                    if (nameError || hostError) return@TextButton

                    onSave(
                        ServerConfig(
                            id = initialServer?.id ?: 0,
                            name = name.trim(),
                            protocol = protocol,
                            host = host.trim(),
                            port = port.toIntOrNull() ?: defaultPort(protocol),
                            username = username.trim(),
                            password = password,
                            basePath = basePath.trim().ifEmpty { "/" },
                            createdAt = initialServer?.createdAt ?: System.currentTimeMillis(),
                        ),
                    )
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun defaultPort(protocol: NetworkProtocol): Int = when (protocol) {
    NetworkProtocol.FTP -> 21
    NetworkProtocol.SFTP -> 22
    NetworkProtocol.SMB -> 445
}
