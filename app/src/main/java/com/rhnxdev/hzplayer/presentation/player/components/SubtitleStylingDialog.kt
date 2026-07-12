package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

    SheetScaffold(
        title = stringResource(R.string.subtitle_style),
        icon = Icons.Default.Style,
        onDismiss = onDismiss,
        columnModifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Font size
        Text(
            text = stringResource(R.string.size_label, fontSize.toInt()),
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                fontWeight = FontWeight.Medium
            ),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = fontSize,
            onValueChange = { fontSize = it },
            valueRange = 14f..32f,
            steps = 17,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Spacer(modifier = Modifier.height(12.dp))

        // Color
        Text(
            text = stringResource(R.string.color_label),
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            colorOptions.forEachIndexed { index, (label, argb) ->
                val selected = textColorArgb == index
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { textColorArgb = index }
                        .padding(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(argb.toLong()))
                            .then(
                                if (label == "White") Modifier.border(
                                    1.dp,
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                    CircleShape
                                ) else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = if (label == "White" || label == "Yellow") Color.Black else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Spacer(modifier = Modifier.height(12.dp))

        // Background
        Text(
            text = stringResource(R.string.background_label),
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(modifier = Modifier.height(8.dp))
        listOf(
            stringResource(R.string.background_none) to 0,
            stringResource(R.string.background_semi) to 1,
            stringResource(R.string.background_full) to 2
        ).forEach { (label, value) ->
            val isSelected = edgeStyle == value
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else Color.Transparent
                    )
                    .then(
                        if (isSelected) Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        else Modifier
                    )
                    .clickable { edgeStyle = value }
                    .padding(vertical = 12.dp, horizontal = 16.dp),
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = { edgeStyle = value },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                        unselectedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    ),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Spacer(modifier = Modifier.height(8.dp))

        // Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = {
                onStyleChange(SubtitleStyle.DEFAULT)
                onDismiss()
            }) {
                Text(text = stringResource(R.string.reset), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
            Spacer(modifier = Modifier.width(12.dp))
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
                Text(text = stringResource(R.string.apply), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

