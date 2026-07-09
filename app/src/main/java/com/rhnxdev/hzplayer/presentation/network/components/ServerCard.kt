package com.rhnxdev.hzplayer.presentation.network.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.core.designsystem.HzPlayerIcons
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.domain.model.NetworkProtocol
import com.rhnxdev.hzplayer.domain.model.ServerConfig

@Composable
fun ServerCard(
    server: ServerConfig,
    onClick: () -> Unit,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    showMenu: Boolean = true,
    dense: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var showMenuExpanded by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(Spacing.md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        if (dense) {
            Row(
                modifier = Modifier.padding(Spacing.md).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HzPlayerIcons.Server,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${server.protocol.name} • ${server.host}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showMenu) {
                    IconButton(onClick = { showMenuExpanded = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Options", modifier = Modifier.size(18.dp))
                        DropdownMenu(expanded = showMenuExpanded, onDismissRequest = { showMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text("Edit") }, onClick = { showMenuExpanded = false; onEdit() })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { showMenuExpanded = false; onDelete() })
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.padding(Spacing.md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = HzPlayerIcons.Server,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )

                    if (showMenu) {
                        IconButton(
                            onClick = { showMenuExpanded = true },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "Options",
                                modifier = Modifier.size(18.dp),
                            )
                            DropdownMenu(
                                expanded = showMenuExpanded,
                                onDismissRequest = { showMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Edit") },
                                    onClick = { showMenuExpanded = false; onEdit() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = { showMenuExpanded = false; onDelete() },
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.size(Spacing.sm))

                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = "${server.protocol.name} • ${server.host}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
