package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhnxdev.hzplayer.core.util.formatDuration

@Composable
fun DurationBadge(
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    if (durationMs <= 0) return

    Text(
        text = formatDuration(durationMs),
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Preview
@Composable
private fun DurationBadgePreview() {
    DurationBadge(durationMs = 9123000)
}
