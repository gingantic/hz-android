package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhnxdev.hzplayer.domain.model.SubtitleStyle
import com.rhnxdev.hzplayer.domain.model.backgroundColor
import com.rhnxdev.hzplayer.domain.model.textColor

/**
 * Custom Compose overlay for rendering subtitle cue text with
 * user-controlled styling.
 *
 * Used only for the ExoPlayer engine.  VLC renders subtitles natively.
 *
 * @param cues Current subtitle lines to display (one per element).
 * @param style The [SubtitleStyle] to apply.
 * @param modifier Modifier for the parent [Box].
 */
@Composable
fun SubtitleOverlay(
    cues: List<String>,
    style: SubtitleStyle,
    modifier: Modifier = Modifier,
) {
    if (!style.enabled || cues.isEmpty()) return

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 32.dp, vertical = 64.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            cues.forEach { cueText ->
                Text(
                    text = cueText,
                    style = TextStyle(
                        fontSize = style.fontSizeSp.sp,
                        color = style.textColor,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(style.backgroundColor)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
