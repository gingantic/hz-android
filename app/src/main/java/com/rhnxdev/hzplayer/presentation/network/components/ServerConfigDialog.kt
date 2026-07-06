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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.core.util.defaultPort
import com.rhnxdev.hzplayer.domain.model.NetworkProtocol
import com.rhnxdev.hzplayer.domain.model.ServerConfig

/** Groups of related protocols (unsecure + secure). */
private enum class ProtocolGroup(val label: String) {
    FTP("FTP"),
    WEBDAV("WebDAV"),
    SMB("SMB"),
}

/** One selectable option inside a [ProtocolGroup]. */
private data class ProtocolOption(
    val label: String,
    val port: Int,
    val isSecure: Boolean,
    val protocol: NetworkProtocol,
)

private fun optionsForGroup(group: ProtocolGroup): List<ProtocolOption> = when (group) {
    ProtocolGroup.FTP -> listOf(
        ProtocolOption("Unsecure", 21, isSecure = false, NetworkProtocol.FTP),
        ProtocolOption("Secure", 22, isSecure = true, NetworkProtocol.SFTP),
    )
    ProtocolGroup.WEBDAV -> listOf(
        ProtocolOption("Unsecure", 80, isSecure = false, NetworkProtocol.WEBDAV),
        ProtocolOption("Secure", 443, isSecure = true, NetworkProtocol.WEBDAVS),
    )
    ProtocolGroup.SMB -> listOf(
        ProtocolOption("SMB", 445, isSecure = false, NetworkProtocol.SMB),
    )
}

private fun groupForProtocol(protocol: NetworkProtocol): ProtocolGroup = when (protocol) {
    NetworkProtocol.FTP, NetworkProtocol.SFTP -> ProtocolGroup.FTP
    NetworkProtocol.WEBDAV, NetworkProtocol.WEBDAVS -> ProtocolGroup.WEBDAV
    NetworkProtocol.SMB -> ProtocolGroup.SMB
}

private fun optionIndexForProtocol(protocol: NetworkProtocol): Int = optionsForGroup(
    groupForProtocol(protocol)
).indexOfFirst { it.protocol == protocol }.coerceAtLeast(0)

@Composable
fun ServerConfigDialog(
    initialServer: ServerConfig?,
    onSave: (ServerConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val isEditing = initialServer != null

    val initialProtocol = initialServer?.protocol ?: NetworkProtocol.FTP
    val initialGroup = groupForProtocol(initialProtocol)

    var name by remember { mutableStateOf(initialServer?.name ?: "") }
    var host by remember { mutableStateOf(initialServer?.host ?: "") }
    var port by remember {
        mutableStateOf(
            initialServer?.port?.toString()
                ?: defaultPort(initialProtocol).toString(),
        )
    }
    var username by remember { mutableStateOf(initialServer?.username ?: "") }
    var password by remember { mutableStateOf(initialServer?.password ?: "") }
    var basePath by remember { mutableStateOf(initialServer?.basePath ?: "/") }
    var showPassword by remember { mutableStateOf(false) }

    // Protocol group + option state
    var selectedGroup by remember { mutableStateOf(initialGroup) }
    var selectedOptionIdx by remember { mutableIntStateOf(optionIndexForProtocol(initialProtocol)) }
    var currentProtocol by remember { mutableStateOf(initialProtocol) }

    fun selectOption(group: ProtocolGroup, optionIndex: Int) {
        val options = optionsForGroup(group)
        if (optionIndex !in options.indices) return
        val opt = options[optionIndex]
        selectedGroup = group
        selectedOptionIdx = optionIndex
        currentProtocol = opt.protocol
        port = opt.port.toString()
    }

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
                // ── Name ──────────────────────────────────────────
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

                // ── Protocol groups ───────────────────────────────
                Text(
                    text = "Protocol",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    ProtocolGroup.entries.forEach { group ->
                        FilterChip(
                            selected = selectedGroup == group,
                            onClick = { selectOption(group, 0) },
                            label = { Text(group.label) },
                        )
                    }
                }

                // ── Port options for the selected group ───────────
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        optionsForGroup(selectedGroup).forEachIndexed { index, opt ->
                            FilterChip(
                                selected = selectedGroup == selectedGroup && selectedOptionIdx == index,
                                onClick = { selectOption(selectedGroup, index) },
                                label = { Text(opt.label) },
                            )
                        }
                    }
                }

                // ── Host ──────────────────────────────────────────
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

                // ── Port (manual override) ────────────────────────
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── Username ──────────────────────────────────────
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    placeholder = { Text("anonymous") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── Password ──────────────────────────────────────
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

                // ── Base Path ─────────────────────────────────────
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
                            protocol = currentProtocol,
                            host = host.trim(),
                            port = port.toIntOrNull() ?: defaultPort(currentProtocol),
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
