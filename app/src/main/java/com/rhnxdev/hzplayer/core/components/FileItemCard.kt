package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.core.designsystem.HzPlayerIcons
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.core.util.formatDuration
import com.rhnxdev.hzplayer.core.util.formatFileSize
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme
import com.rhnxdev.hzplayer.R
import androidx.compose.ui.res.stringResource

import androidx.compose.material3.ExperimentalMaterial3Api

/**
 * Unified file/directory item card used by both local and remote file browsers.
 *
 * Replace both [com.rhnxdev.hzplayer.presentation.browse.components.FileListItem]
 * and [com.rhnxdev.hzplayer.presentation.network.components.RemoteFileListItem].
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    subfolderCount: Int = -1,
    fileCount: Int = -1,
    mediaCount: Int = -1,
    mediaMode: Boolean = false,
    onPropertiesClick: (() -> Unit)? = null,
    onPlayAsAudioClick: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onFavoriteClick: (() -> Unit)? = null,
) {

    val subtitle = when {
        isDirectory && subfolderCount >= 0 && fileCount >= 0 -> if (mediaMode) {
            stringResource(R.string.folder_badge_media, subfolderCount, mediaCount)
        } else {
            stringResource(R.string.folder_badge, subfolderCount, fileCount)
        }
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
            containerColor = Color.Transparent,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingThumbnail != null) {
                // Thumbnail occupies ~40% of the row width on the left.
                Box(
                    modifier = Modifier
                        .weight(0.4f)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(Spacing.xs)),
                    contentAlignment = Alignment.Center,
                ) {
                    FileItemIcon(
                        name = name,
                        isDirectory = isDirectory,
                        mimeType = mimeType,
                        size = 32.dp
                    )
                    leadingThumbnail()

                    // Duration badge (bottom-right, YouTube style)
                    if (durationMs > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(3.dp),
                        ) {
                            DurationBadge(durationMs = durationMs)
                        }
                    }

                    // YouTube-style red watch-progress line flush at the thumbnail bottom
                    val progress = if (durationMs > 0 && playbackPositionMs > 0) {
                        (playbackPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    } else null
                    if (progress != null) {
                        // Track
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(Color.White.copy(alpha = 0.35f))
                        )
                        // Watched portion
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth(fraction = progress)
                                .height(2.dp)
                                .background(Color(0xFFE53935))
                        )
                    }
                }
            } else {
                FileItemIcon(
                    name = name,
                    isDirectory = isDirectory,
                    mimeType = mimeType,
                    size = 40.dp
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

            if (onPropertiesClick != null || onPlayAsAudioClick != null || onFavoriteClick != null) {
                var showMenu by remember { mutableStateOf(false) }
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.media_overflow_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (showMenu) {
                    FileOptionsBottomSheet(
                        name = name,
                        isDirectory = isDirectory,
                        subtitle = subtitle,
                        mimeType = mimeType,
                        leadingThumbnail = leadingThumbnail,
                        isFavorite = isFavorite,
                        onFavoriteClick = onFavoriteClick,
                        onPlayAsAudioClick = onPlayAsAudioClick,
                        onPropertiesClick = onPropertiesClick,
                        onDismissRequest = { showMenu = false },
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

@Composable
private fun FileItemIcon(
    name: String,
    isDirectory: Boolean,
    mimeType: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    if (isDirectory) {
        val lowerName = name.lowercase()
        val badgeIcon = when {
            lowerName == "download" || lowerName == "downloads" -> Icons.Filled.Download
            lowerName == "music" || lowerName == "audio" -> Icons.Filled.MusicNote
            lowerName == "movie" || lowerName == "movies" || lowerName == "video" || lowerName == "videos" -> Icons.Filled.PlayArrow
            lowerName == "android" -> Icons.Filled.Android
            else -> null
        }

        if (badgeIcon != null) {
            // FolderSpecial-style icon: the symbol is punched out of the
            // folder as transparent negative space (like Icons.Filled.FolderSpecial).
            // Offscreen compositing confines the DstOut erase to this Box so the
            // cutout reveals the list background instead of erasing the window.
            Box(
                modifier = modifier
                    .size(size)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    tint = primaryColor,
                )
                Icon(
                    imageVector = badgeIcon,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize(0.52f)
                        .align(Alignment.BottomEnd)
                        .padding(end = 2.5.dp, bottom = 4.5.dp)
                        .graphicsLayer { blendMode = BlendMode.DstOut },
                    tint = primaryColor,
                )
            }
        } else {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                modifier = modifier.size(size),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    } else {
        val icon = when {
            mimeType?.startsWith("video") == true -> Icons.Filled.Movie
            mimeType?.startsWith("audio") == true -> Icons.Filled.Audiotrack
            else -> Icons.Filled.Description
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = modifier.size(size),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
