package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.rhnxdev.hzplayer.domain.model.MediaType
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

/**
 * Album art with a graceful fallback: renders [albumArtUri] cropped to fill, and the
 * audio [ThumbnailPlaceholder] both when the URI is absent and while loading fails.
 *
 * The placeholder takes [modifier] too, so a caller that sizes the art (aspect ratio,
 * rounded clip) gets the same box in every state.
 *
 * @param albumArtUri artwork URI, or null when the track has none
 * @param contentDescription track/album title for accessibility; null when decorative
 */
@Composable
fun AlbumArtImage(
    albumArtUri: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    if (albumArtUri != null) {
        SubcomposeAsyncImage(
            model = albumArtUri,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            error = { ThumbnailPlaceholder(mediaType = MediaType.AUDIO) },
            loading = { ThumbnailPlaceholder(mediaType = MediaType.AUDIO) },
        )
    } else {
        ThumbnailPlaceholder(mediaType = MediaType.AUDIO, modifier = modifier)
    }
}

@PreviewLightDark
@Preview
@Composable
private fun AlbumArtImagePreview() {
    HzPlayerTheme {
        Row(modifier = Modifier.size(width = 160.dp, height = 80.dp)) {
            // No URI — the fallback placeholder.
            AlbumArtImage(
                albumArtUri = null,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
            )
            // Unreachable host — exercises the error placeholder.
            AlbumArtImage(
                albumArtUri = "https://example.invalid/cover.jpg",
                contentDescription = null,
                modifier = Modifier.size(80.dp),
            )
        }
    }
}
