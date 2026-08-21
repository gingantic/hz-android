package com.rhnxdev.hzplayer.presentation.browse.components

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.presentation.browse.FileClipboard

/**
 * Compact bottom bar shown while a file/folder is staged for cut or copy.
 * Single line: operation icon, item name, cancel, and a Paste button.
 */
@Composable
fun PasteActionBar(
    clipboard: FileClipboard,
    canPaste: Boolean,
    isPasting: Boolean,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.md, end = Spacing.sm, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (clipboard.isCut) Icons.Default.ContentCut else Icons.Default.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text(
                text = clipboard.item.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            if (isPasting) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(horizontal = Spacing.md)
                        .size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.dialog_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onPaste, enabled = canPaste) {
                    Text(stringResource(R.string.paste_here))
                }
            }
        }
    }
}

/**
 * Destructive-action confirmation shown before permanently deleting a file or folder.
 */
@Composable
fun DeleteConfirmDialog(
    itemName: String,
    isDirectory: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.delete_confirm_title)) },
        text = {
            Text(
                stringResource(
                    if (isDirectory) R.string.delete_confirm_message_folder
                    else R.string.delete_confirm_message_file,
                    itemName,
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.menu_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

/**
 * Explains that pasting needs "All files access" on Android 11+ and deep-links
 * to the system settings page where the user can grant it.
 */
@Composable
fun AllFilesAccessDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.all_files_access_title)) },
        text = { Text(stringResource(R.string.all_files_access_message)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val appIntent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                        try {
                            context.startActivity(appIntent)
                        } catch (_: Exception) {
                            // Some OEMs don't expose the per-app screen; fall back to the global one.
                            try {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            } catch (_: Exception) {
                                // No settings screen available — nothing else we can do.
                            }
                        }
                    }
                },
            ) { Text(stringResource(R.string.all_files_access_grant)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
