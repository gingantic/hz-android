package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
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
import com.rhnxdev.hzplayer.core.util.formatDuration
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
    leadingThumbnail: (@Composable () -> Unit)? = null,
    marqueeTitle: Boolean = false,
    durationMs: Long = 0,
    playbackPositionMs: Long = 0,
    resolution: String? = null,
    isNew: Boolean = false,
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
        durationMs > 0 -> {
            val sizeOrResolution = if (resolution != null) resolution else {
                if (fileSize > 0) formatFileSize(fileSize) else "- bytes"
            }
            if (playbackPositionMs > 0) {
                "$sizeOrResolution • ${formatDuration(playbackPositionMs)} / ${formatDuration(durationMs)}"
            } else {
                "$sizeOrResolution • ${formatDuration(durationMs)}"
            }
        }
        else -> {
            if (resolution != null) resolution
            else if (fileSize > 0) formatFileSize(fileSize)
            else "- bytes"
        }
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
            if (leadingThumbnail != null) {
                // Thumbnail occupies ~42% of the row width on the left (40% bigger than 30%).
                Box(
                    modifier = Modifier
                        .weight(0.42f)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(Spacing.xs)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    leadingThumbnail()
                }
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isDirectory) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(if (leadingThumbnail != null) 0.58f else 1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = if (marqueeTitle) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = if (marqueeTitle) {
                        Modifier.basicMarquee(
                            iterations = 3,
                            initialDelayMillis = 2000,
                        )
                    } else Modifier,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isNew) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color.Red)
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                    }
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
