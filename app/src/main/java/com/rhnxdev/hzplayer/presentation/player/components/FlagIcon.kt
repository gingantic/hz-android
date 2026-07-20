package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.carlsen.flagkit.FlagKit

/**
 * Small rounded country flag for a track row, resolved from an ISO 3166-1
 * alpha-2 [countryCode] (FlagKit also knows subdivision codes like "GB-WLS").
 * Falls back to a globe glyph for unknown or stateless languages.
 */
@Composable
fun FlagIcon(countryCode: String?, modifier: Modifier = Modifier) {
    val flag = remember(countryCode) {
        countryCode
            ?.uppercase()
            ?.let { code -> runCatching { FlagKit.getFlag(code) }.getOrNull() }
    }
    if (flag != null) {
        Image(
            imageVector = flag,
            contentDescription = countryCode,
            modifier = modifier
                .size(width = 22.dp, height = 16.dp)
                .clip(RoundedCornerShape(3.dp))
                .border(
                    width = 0.5.dp,
                    color = Color.Black.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(3.dp),
                ),
        )
    } else {
        Icon(
            imageVector = Icons.Default.Language,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = modifier.size(16.dp),
        )
    }
}
