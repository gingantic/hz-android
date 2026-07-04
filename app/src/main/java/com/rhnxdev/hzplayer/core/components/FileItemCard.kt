package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.core.util.formatFileSize
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

/**
 * Unified file/directory item card used by both local and remote file browsers.
 *
 * Replace both [com.rhnxdev.hzplayer.presentation.browse.components.FileListItem]
 * and [com.rhnxdev.hzplayer.presentation.network.components.RemoteFileListItem].
 */
@Composable
fun FileItemCard(
    name: String,
    isDirectory: Boolean,
    fileSize: Long,
    childCount: Int,
    mimeType: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon: ImageVector = when {
        isDirectory -> Icons.Filled.Folder
        mimeType?.startsWith("video") == true -> Icons.Filled.Movie
        mimeType?.startsWith("audio") == true -> Icons.Filled.Audiotrack
        else -> Icons.Filled.Description
    }

    val subtitle = when {
        isDirectory && childCount >= 0 -> "$childCount items"
        isDirectory -> "- items"
        mimeType != null -> "$mimeType • ${formatFileSize(fileSize)}"
        fileSize > 0 -> formatFileSize(fileSize)
        else -> "- bytes"
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Spacing.sm),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (isDirectory) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun FileItemCardPreview() {
    HzPlayerTheme {
        FileItemCard(
            name = "Movie.mp4",
            isDirectory = false,
            fileSize = 256_000_000,
            childCount = 0,
            mimeType = "video/mp4",
            onClick = {},
        )
    }
}

@PreviewLightDark
@Preview
@Composable
private fun FileItemCardDirectoryPreview() {
    HzPlayerTheme {
        FileItemCard(
            name = "Movies",
            isDirectory = true,
            fileSize = 0,
            childCount = 12,
            onClick = {},
        )
    }
}
