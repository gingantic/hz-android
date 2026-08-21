package com.rhnxdev.hzplayer.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhnxdev.hzplayer.core.designsystem.Spacing

import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox

import androidx.compose.material.icons.filled.PlayArrow

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.font.FontWeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserBottomBar(
    url: String,
    currentTabUrl: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    tabCount: Int,
    isLoading: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onStopLoading: () -> Unit,
    onUrlChange: (String) -> Unit,
    onUrlSubmit: () -> Unit,
    onTabsClick: () -> Unit,
    onMenuClick: () -> Unit,
    onNewTab: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onPlayerClick: () -> Unit = {},
    mediaCount: Int = 0,
    onMediaGrabberClick: () -> Unit = {},
    isDesktopSite: Boolean = false,
    onToggleDesktopSite: () -> Unit = {},
    modifier: Modifier = Modifier,
) {

    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Back button
        IconButton(
            onClick = onBack,
            enabled = canGoBack,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = if (canGoBack) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        }

        // Forward button
        IconButton(
            onClick = onForward,
            enabled = canGoForward,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Forward",
                tint = if (canGoForward) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        }

        // New Tab button
        IconButton(
            onClick = onNewTab,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "New Tab",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Tab count button
        IconButton(
            onClick = onTabsClick,
            modifier = Modifier.size(40.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(5.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$tabCount",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Media Grabber button
        IconButton(
            onClick = onMediaGrabberClick,
            modifier = Modifier.size(40.dp),
        ) {
            if (mediaCount > 0) {
                BadgedBox(
                    badge = {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Text("$mediaCount")
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Movie,
                        contentDescription = "Media Grabber",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = "Media Grabber",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }

        // Menu button
        IconButton(
            onClick = { showMenu = true },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Menu",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

        // Floating Modal Bottom Sheet rising from bottom bar
        if (showMenu) {
            ModalBottomSheet(
                onDismissRequest = { showMenu = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tonalElevation = 0.dp,
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    // Navigation controls pill bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                showMenu = false
                                onBack()
                            },
                            enabled = canGoBack,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = if (canGoBack) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            )
                        }

                        IconButton(
                            onClick = {
                                showMenu = false
                                onForward()
                            },
                            enabled = canGoForward,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Forward",
                                tint = if (canGoForward) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            )
                        }

                        IconButton(
                            onClick = {
                                showMenu = false
                                if (isLoading) onStopLoading() else onReload()
                            },
                        ) {
                            Icon(
                                imageVector = if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = if (isLoading) "Stop" else "Reload",
                                tint = if (isLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Menu items
                    BrowserMenuItemRow(
                        icon = Icons.Default.Add,
                        title = "New tab",
                        onClick = {
                            showMenu = false
                            onNewTab()
                        },
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    )

                    BrowserMenuItemRow(
                        icon = Icons.Default.History,
                        title = "History",
                        onClick = {
                            showMenu = false
                            onHistoryClick()
                        },
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    )

                    // Desktop site toggle (Chrome-style) — reloads the page in desktop mode
                    BrowserMenuItemRow(
                        icon = Icons.Default.Computer,
                        title = "Desktop site",
                        onClick = {
                            showMenu = false
                            onToggleDesktopSite()
                        },
                        trailing = {
                            Checkbox(
                                checked = isDesktopSite,
                                onCheckedChange = {
                                    showMenu = false
                                    onToggleDesktopSite()
                                },
                            )
                        },
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    )

                    BrowserMenuItemRow(
                        icon = Icons.Default.Settings,
                        title = "Settings",
                        onClick = {
                            showMenu = false
                            onSettingsClick()
                        },
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    )

                    BrowserMenuItemRow(
                        icon = Icons.Default.PlayArrow,
                        title = "Exit to player",
                        onClick = {
                            showMenu = false
                            onPlayerClick()
                        },
                    )

                }
            }
        }
    }

@Composable
private fun BrowserMenuItemRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (trailing != null) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}
