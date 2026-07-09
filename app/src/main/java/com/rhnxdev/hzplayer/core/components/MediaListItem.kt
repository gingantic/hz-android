package com.rhnxdev.hzplayer.core.components

import com.rhnxdev.hzplayer.domain.model.MediaType
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.core.designsystem.CornerRadii
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.core.util.formatDuration
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun MediaListItem(
    title: String,
    subtitle: String,
    durationMs: Long = 0,
    thumbnailContent: @Composable () -> Unit = {},
    onClick: () -> Unit,
    trailingContent: @Composable (() -> Unit)? = null,
    isSquareThumbnail: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val cardShape = remember { RoundedCornerShape(CornerRadii.sm) }
    val clipShape = remember { RoundedCornerShape(CornerRadii.xs) }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = cardShape,
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
            // Thumbnail
            Box(
                modifier = Modifier
                    .run {
                        if (isSquareThumbnail) {
                            size(56.dp)
                        } else {
                            size(width = 78.dp, height = 56.dp)
                        }
                    }
                    .clip(clipShape),
            ) {
                thumbnailContent()
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            // Text content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (durationMs > 0) {
                Text(
                    text = formatDuration(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }

            trailingContent?.invoke()
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun MediaListItemPreview() {
    HzPlayerTheme {
        MediaListItem(
            title = "Blade Runner 2049",
            subtitle = "2017 • 2h32m",
            durationMs = 9_123_000,
            thumbnailContent = {
                ThumbnailPlaceholder(mediaType = MediaType.VIDEO)
            },
            onClick = {},
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )
    }
}
