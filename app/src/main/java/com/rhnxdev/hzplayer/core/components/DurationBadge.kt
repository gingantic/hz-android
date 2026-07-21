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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
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
        style = TextStyle(
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 10.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 4.dp, vertical = 0.5.dp),
    )
}

@Preview
@Composable
private fun DurationBadgePreview() {
    DurationBadge(durationMs = 9123000)
}
