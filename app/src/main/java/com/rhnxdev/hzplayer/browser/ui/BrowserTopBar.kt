package com.rhnxdev.hzplayer.browser.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rhnxdev.hzplayer.core.designsystem.Spacing

@Composable
fun BrowserTopBar(
    url: String,
    currentTabUrl: String,
    isLoading: Boolean,
    progress: Int,
    onUrlChange: (String) -> Unit,
    onUrlSubmit: () -> Unit,
    onReload: () -> Unit,
    onStopLoading: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val urlChanged = url.trim() != currentTabUrl.trim()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val submitUrl = {
        keyboardController?.hide()
        focusManager.clearFocus()
        onUrlSubmit()
    }

    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(top = 4.dp, bottom = 4.dp, start = 8.dp, end = 8.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .onKeyEvent { event ->
                    if ((event.key == Key.Enter || event.key == Key.NumPadEnter) && event.type == KeyEventType.KeyUp) {
                        submitUrl()
                        true
                    } else false
                },
        ) {
            val fontSize = (maxWidth.value / 20f).coerceIn(14f, 17f).sp

            val isHttps = url.startsWith("https://", ignoreCase = true)
            val isHttp = url.startsWith("http://", ignoreCase = true)

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
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        isFocused = focusState.isFocused
                        onFocusChanged(focusState.isFocused)
                    },
                singleLine = true,
                placeholder = {
                    Text(
                        "Search or enter URL",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isFocused && url.isNotBlank()) {
                            IconButton(
                                onClick = { onUrlChange("") },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear URL",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
                            !isFocused && url.isNotBlank() -> IconButton(
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
                    }
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
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

        // Top loading progress bar
        val targetProgress = if (isLoading) (progress.coerceAtLeast(10) / 100f) else 1f
        val animatedProgress by animateFloatAsState(
            targetValue = targetProgress,
            animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing),
            label = "PageLoadingProgress",
        )

        if (isLoading && animatedProgress < 1f) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .height(2.5.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
        }
    }
}
