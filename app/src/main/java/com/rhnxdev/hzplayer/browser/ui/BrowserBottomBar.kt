package com.rhnxdev.hzplayer.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BottomSheetDefaults
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
    modifier: Modifier = Modifier,
) {

    // True when the typed text differs from what is actually loaded
    val urlChanged = url.trim() != currentTabUrl.trim()
    var showMenu by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val submitUrl = {
        keyboardController?.hide()
        focusManager.clearFocus()
        onUrlSubmit()
    }

    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Tab count button — LEFT side
        IconButton(
            onClick = onTabsClick,
            modifier = Modifier.size(40.dp),
        ) {
            Text(
                text = "$tabCount",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // URL bar — auto-sizing text proportional to bar width
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .onKeyEvent { event ->
                    if ((event.key == Key.Enter || event.key == Key.NumPadEnter) && event.type == KeyEventType.KeyUp) {
                        submitUrl()
                        true
                    } else false
                },
        ) {
            val fontSize = (maxWidth.value / 21f).coerceIn(11f, 15f).sp

            val isHttps = url.startsWith("https://", ignoreCase = true)
            val isHttp = url.startsWith("http://", ignoreCase = true)

            // Display URL purging http:// and https:// prefixes
            val displayUrl = when {
                url == "about:blank" -> ""
                isHttps -> url.substring(8)
                isHttp -> url.substring(7)
                else -> url
            }

            OutlinedTextField(
                value = displayUrl,
                onValueChange = { newDisplay: String ->
                    val updatedUrl = when {
                        newDisplay.isBlank() -> ""
                        newDisplay.startsWith("https://", ignoreCase = true) ||
                            newDisplay.startsWith("http://", ignoreCase = true) -> newDisplay
                        isHttps -> "https://$newDisplay"
                        isHttp -> "http://$newDisplay"
                        else -> newDisplay
                    }
                    onUrlChange(updatedUrl)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        "Search or enter URL",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = fontSize),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    val leadingIcon = when {
                        isHttps -> Icons.Default.Lock
                        isHttp -> Icons.Default.LockOpen
                        else -> Icons.Default.Search
                    }
                    val leadingIconTint = when {
                        isHttps -> MaterialTheme.colorScheme.primary
                        isHttp -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val leadingIconDesc = when {
                        isHttps -> "Secure connection (HTTPS)"
                        isHttp -> "Insecure connection (HTTP)"
                        else -> "Search or enter URL"
                    }
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = leadingIconDesc,
                        tint = leadingIconTint,
                        modifier = Modifier.size(16.dp),
                    )
                },
                trailingIcon = {
                    when {
                        isLoading -> IconButton(
                            onClick = onStopLoading,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Stop",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        urlChanged && url.isNotBlank() -> IconButton(
                            onClick = submitUrl,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Go",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        url.isNotBlank() -> IconButton(
                            onClick = onReload,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reload",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },

                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = fontSize,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = { submitUrl() },
                    onDone = { submitUrl() },
                    onSearch = { submitUrl() },
                    onSend = { submitUrl() },
                ),
                shape = RoundedCornerShape(Spacing.sm),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            )
        }

        // Media Grabber button — RIGHT side bar
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

        // 3-bar Menu button — RIGHT side bar
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

        // Floating Modal Bottom Sheet rising from bottom bar
        if (showMenu) {
            ModalBottomSheet(
                onDismissRequest = { showMenu = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                dragHandle = { BottomSheetDefaults.DragHandle() },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
                        title = "Player",
                        onClick = {
                            showMenu = false
                            onPlayerClick()
                        },
                    )

                }
            }
        }
    }
}

@Composable
private fun BrowserMenuItemRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
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
        )
    }
}
