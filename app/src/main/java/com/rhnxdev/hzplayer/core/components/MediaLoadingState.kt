package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun MediaLoadingState(
    modifier: Modifier = Modifier,
    itemCount: Int = 6,
    shape: ShimmerShape = ShimmerShape.VIDEO_CATEGORY,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

enum class ShimmerShape {
    VIDEO_CARD,
    ALBUM_CARD,
    LIST_ITEM,
    VIDEO_CATEGORY,
    STORAGE_ROOT,
    FILE_LIST_ITEM,
}

@PreviewLightDark
@Preview
@Composable
private fun MediaLoadingStatePreview() {
    HzPlayerTheme {
        MediaLoadingState()
    }
}
