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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.rhnxdev.hzplayer.R
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
    // Anonymous servers are persisted with username="anonymous"; infer the flag on edit
    // (avoids a DB migration for a dedicated column).
    var allowAnonymous by remember {
        mutableStateOf(
            initialServer?.let { it.protocol == NetworkProtocol.FTP && it.username == "anonymous" }
                ?: false,
        )
    }
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
        if (opt.protocol != NetworkProtocol.FTP) {
            allowAnonymous = false
            username = initialServer?.username ?: ""
            password = initialServer?.password ?: ""
        }
    }

    var nameError by remember { mutableStateOf(false) }
    var hostError by remember { mutableStateOf(false) }
    var usernameError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isEditing) R.string.edit_server else R.string.add_server)) },
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
                    label = { Text(stringResource(R.string.label_name)) },
                    placeholder = { Text(stringResource(R.string.placeholder_name)) },
                    isError = nameError,
                    supportingText = if (nameError) {{ Text(stringResource(R.string.error_name_required)) }} else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── Protocol groups ───────────────────────────────
                Text(
                    text = stringResource(R.string.protocol_label),
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
                    label = { Text(stringResource(R.string.label_host)) },
                    placeholder = { Text(stringResource(R.string.placeholder_host)) },
                    isError = hostError,
                    supportingText = if (hostError) {{ Text(stringResource(R.string.error_host_required)) }} else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── Port (manual override) ────────────────────────
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.label_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── Username ──────────────────────────────────────
                OutlinedTextField(
                    value = if (allowAnonymous) "anonymous" else username,
                    onValueChange = { username = it; usernameError = false },
                    label = { Text(stringResource(R.string.label_username)) },
                    placeholder = { Text(stringResource(R.string.placeholder_anonymous)) },
                    enabled = !allowAnonymous,
                    isError = usernameError,
                    supportingText = if (usernameError) {{ Text(stringResource(R.string.error_username_required)) }} else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── Anonymous opt-in (FTP only) ───────────────────
                if (currentProtocol == NetworkProtocol.FTP) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Checkbox(
                            checked = allowAnonymous,
                            onCheckedChange = {
                                allowAnonymous = it
                                usernameError = false
                                if (it) {
                                    username = ""
                                    password = ""
                                }
                            },
                        )
                        Text(
                            text = stringResource(R.string.checkbox_allow_anonymous),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                // ── Password ──────────────────────────────────────
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.label_password)) },
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
                                contentDescription = stringResource(if (showPassword) R.string.hide else R.string.show),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── Base Path ─────────────────────────────────────
                OutlinedTextField(
                    value = basePath,
                    onValueChange = { basePath = it },
                    label = { Text(stringResource(R.string.label_base_path)) },
                    placeholder = { Text(stringResource(R.string.placeholder_base_path)) },
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
                    usernameError = currentProtocol == NetworkProtocol.FTP
                        && !allowAnonymous && username.isBlank()
                    if (nameError || hostError || usernameError) return@TextButton

                    onSave(
                        ServerConfig(
                            id = initialServer?.id ?: 0,
                            name = name.trim(),
                            protocol = currentProtocol,
                            host = host.trim(),
                            port = port.toIntOrNull() ?: defaultPort(currentProtocol),
                            username = if (allowAnonymous) "anonymous" else username.trim(),
                            password = if (allowAnonymous) "" else password,
                            allowAnonymous = allowAnonymous && currentProtocol == NetworkProtocol.FTP,
                            basePath = basePath.trim().ifEmpty { "/" },
                            createdAt = initialServer?.createdAt ?: System.currentTimeMillis(),
                        ),
                    )
                },
            ) {
                Text(stringResource(R.string.dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}
