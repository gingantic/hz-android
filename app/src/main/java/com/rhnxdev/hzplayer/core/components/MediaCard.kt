package com.rhnxdev.hzplayer.core.components

import com.rhnxdev.hzplayer.domain.model.MediaType
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.core.designsystem.CornerRadii
import androidx.compose.ui.graphics.luminance
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun MediaCard(
    title: String,
    subtitle: String,
    thumbnailContent: @Composable () -> Unit,
    durationMs: Long = 0,
    progress: Float? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
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

                // Duration badge (bottom-right)
                if (durationMs > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(Spacing.xs),
                    ) {
                        DurationBadge(durationMs = durationMs)
                    }
                }
            }

            // Text area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.md),
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
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Watched progress bar
                if (progress != null && progress in 0f..1f) {
                    val progressShape = remember { RoundedCornerShape(2.dp) }
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(progressShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = StrokeCap.Round,
                    )
                }
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
