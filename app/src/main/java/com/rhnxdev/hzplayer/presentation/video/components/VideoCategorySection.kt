package com.rhnxdev.hzplayer.presentation.video.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.rhnxdev.hzplayer.core.components.MediaCard
import com.rhnxdev.hzplayer.core.components.MediaType
import com.rhnxdev.hzplayer.core.components.ThumbnailPlaceholder
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.presentation.preview.PreviewMedia
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun VideoCategorySection(
    title: String,
    items: List<Map<String, Any>>,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Spacing.lg),
        )

        Spacer(modifier = Modifier.height(Spacing.sm))

        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            itemsIndexed(items, key = { index, _ -> index }) { index, item ->
                MediaCard(
                    title = item["title"] as? String ?: "",
                    subtitle = item["subtitle"] as? String ?: "",
                    durationMs = (item["durationMs"] as? Long) ?: 0,
                    progress = ((item["progress"] as? Double)?.toFloat()) ?: -1f,
                    thumbnailContent = {
                        ThumbnailPlaceholder(mediaType = MediaType.VIDEO)
                    },
                    onClick = { onItemClick(items.indexOf(item)) },
                    modifier = Modifier.width(200.dp),
                )
            }
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun VideoCategorySectionPreview() {
    HzPlayerTheme {
        Column {
            VideoCategorySection(
                title = "Recent",
                items = PreviewMedia.recentVideos,
                onItemClick = {},
            )
            Spacer(modifier = Modifier.height(Spacing.lg))
            VideoCategorySection(
                title = "All Videos",
                items = PreviewMedia.videoMovies,
                onItemClick = {},
            )
        }
    }
}
