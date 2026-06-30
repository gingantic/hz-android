package com.rhnxdev.hzplayer.presentation.browse.components

import androidx.compose.foundation.clickable
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
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun FileListItem(
    item: FolderItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon: ImageVector = when {
        item.isDirectory -> Icons.Filled.Folder
        item.mimeType?.startsWith("video") == true -> Icons.Filled.Movie
        item.mimeType?.startsWith("audio") == true -> Icons.Filled.Audiotrack
        else -> Icons.Filled.Description
    }

    val subtitle = when {
        item.isDirectory -> "${item.childCount} items"
        item.mimeType == null -> formatFileSize(item.fileSize)
        else -> "${item.mimeType} • ${formatFileSize(item.fileSize)}"
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
                tint = if (item.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
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
private fun FileListItemPreview() {
    HzPlayerTheme {
        FileListItem(
            item = FolderItem(
                id = 1,
                name = "Movies",
                path = "/storage/Movies",
                isDirectory = true,
                childCount = 15,
                dateModified = 1_700_000_000_000,
            ),
            onClick = {},
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
    }
}
