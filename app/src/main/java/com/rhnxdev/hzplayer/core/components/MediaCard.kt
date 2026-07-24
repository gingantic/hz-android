package com.rhnxdev.hzplayer.core.components

import com.rhnxdev.hzplayer.domain.model.MediaType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.core.designsystem.CornerRadii
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaCard(
    title: String,
    subtitle: String,
    thumbnailContent: @Composable () -> Unit,
    durationMs: Long = 0,
    progress: Float? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onPropertiesClick: (() -> Unit)? = null,
    onPlayAsAudioClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val cardShape = remember { RoundedCornerShape(CornerRadii.md) }
    val clipShape = remember { RoundedCornerShape(topStart = CornerRadii.md, topEnd = CornerRadii.md) }
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(
                alpha = if (isLight) 0.08f else 0.35f
            )
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            // Thumbnail area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(clipShape),
            ) {
                thumbnailContent()

                // Duration badge (bottom-right, YouTube style)
                if (durationMs > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(Spacing.xs),
                    ) {
                        DurationBadge(durationMs = durationMs)
                    }
                }

                // YouTube-style red watch-progress line flush at the thumbnail bottom
                if (progress != null && progress in 0f..1f) {
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

            // Text area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.md, bottom = Spacing.md),
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (onPropertiesClick != null || onPlayAsAudioClick != null) {
                        var showMenu by remember { mutableStateOf(false) }
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(32.dp),
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
                                name = title,
                                isDirectory = false,
                                subtitle = subtitle,
                                leadingThumbnail = thumbnailContent,
                                onPlayAsAudioClick = onPlayAsAudioClick,
                                onPropertiesClick = onPropertiesClick,
                                onDismissRequest = { showMenu = false },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

            }
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun MediaCardPreview() {
    HzPlayerTheme {
        MediaCard(
            title = "Blade Runner 2049",
            subtitle = "2017 • Sci-Fi",
            durationMs = 9_123_000,
            progress = 0.35f,
            thumbnailContent = {
                ThumbnailPlaceholder(mediaType = MediaType.VIDEO)
            },
            onClick = {},
            modifier = Modifier.padding(Spacing.lg),
        )
    }
}
