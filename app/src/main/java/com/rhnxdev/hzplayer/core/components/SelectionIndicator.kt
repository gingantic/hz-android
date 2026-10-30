package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

/**
 * Round check badge shown on a row while multi-select is active.
 *
 * Filled with the primary colour and a check when selected, an empty ring otherwise.
 * Over a thumbnail the ring is dark-backed so it stays visible on bright frames; on a
 * plain surface ([onMedia] false) it is a bare ring in the theme's variant colour.
 *
 * @param selected — whether the item is part of the selection
 * @param size — outer diameter of the badge
 * @param onMedia — true when drawn over a thumbnail, false over the plain row surface
 */
@Composable
fun SelectionIndicator(
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    onMedia: Boolean = true,
) {
    val primary = MaterialTheme.colorScheme.primary
    val ringColor = if (onMedia) Color.White.copy(alpha = 0.9f)
    else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.background(primary)
                else Modifier
                    .then(
                        if (onMedia) Modifier.background(Color.Black.copy(alpha = 0.35f))
                        else Modifier.background(MaterialTheme.colorScheme.surface)
                    )
                    .border(BorderStroke(2.dp, ringColor), CircleShape)
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(R.string.selection_item_cd),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(size * 0.65f),
            )
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun SelectionIndicatorPreview() {
    HzPlayerTheme {
        Box(modifier = Modifier.background(Color.DarkGray)) {
            SelectionIndicator(selected = true)
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun SelectionIndicatorUnselectedPreview() {
    HzPlayerTheme {
        Box(modifier = Modifier.background(Color.DarkGray)) {
            SelectionIndicator(selected = false)
        }
    }
}
