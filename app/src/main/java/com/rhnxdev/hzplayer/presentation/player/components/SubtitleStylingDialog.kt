package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import com.rhnxdev.hzplayer.core.designsystem.stableNavBarPaddingValues
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.domain.model.SubtitleStyle

private val colorOptions = listOf(
    "White" to 0xFFFFFFFF.toInt(),
    "Yellow" to 0xFFFFFF00.toInt(),
    "Cyan" to 0xFF00FFFF.toInt(),
    "Green" to 0xFF00FF00.toInt(),
    "Red" to 0xFFFF0000.toInt(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleStylingDialog(
    currentStyle: SubtitleStyle,
    onStyleChange: (SubtitleStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    var fontSize by remember { mutableFloatStateOf(currentStyle.fontSizeSp.toFloat()) }
    var textColorArgb by remember { mutableIntStateOf(
        colorOptions.indexOfFirst { it.second == currentStyle.textColorArgb }.coerceAtLeast(0)
    ) }
    var edgeStyle by remember { mutableIntStateOf(currentStyle.edgeStyle) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF1E1E24),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(stableNavBarPaddingValues())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Style,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.subtitle_style),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    ),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Font size
            Text(
                text = stringResource(R.string.size_label, fontSize.toInt()),
                style = MaterialTheme.typography.bodyMedium.copy(color = Color.White.copy(alpha = 0.8f)),
            )
            Slider(
                value = fontSize,
                onValueChange = { fontSize = it },
                valueRange = 14f..32f,
                steps = 17,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(12.dp))

            // Color
            Text(
                text = stringResource(R.string.color_label),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color.White.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                colorOptions.forEachIndexed { index, (label, argb) ->
                    val selected = textColorArgb == index
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { textColorArgb = index }
                            .padding(2.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(argb.toLong())),
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (selected) Color.White else Color.Gray,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            ),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(12.dp))

            // Background
            Text(
                text = stringResource(R.string.background_label),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color.White.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(modifier = Modifier.height(4.dp))
            listOf(stringResource(R.string.background_none) to 0, stringResource(R.string.background_semi) to 1, stringResource(R.string.background_full) to 2).forEach { (label, value) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { edgeStyle = value }
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                ) {
                    RadioButton(
                        selected = edgeStyle == value,
                        onClick = { edgeStyle = value },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary,
                            unselectedColor = Color.Gray,
                        ),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = if (edgeStyle == value) Color.White else Color.LightGray,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = {
                    onStyleChange(SubtitleStyle.DEFAULT)
                    onDismiss()
                }) {
                    Text(text = stringResource(R.string.reset), color = Color.Gray)
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = {
                    onStyleChange(
                        SubtitleStyle(
                            fontSizeSp = fontSize.toInt(),
                            textColorArgb = colorOptions[textColorArgb].second,
                            edgeStyle = edgeStyle,
                        )
                    )
                    onDismiss()
                }) {
                    Text(text = stringResource(R.string.apply), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
