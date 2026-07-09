package com.rhnxdev.hzplayer.presentation.player.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.rhnxdev.hzplayer.core.designsystem.stableNavBarPaddingValues
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhnxdev.hzplayer.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSelectionDialog(
    subtitleTracks: List<String>,
    selectedTrackIndex: Int,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    onAddExternalSubtitleClick: () -> Unit = {},
    subtitleDelayMs: Long = 0,
    onSubtitleDelayChange: (Long) -> Unit = {},
    onStyleClick: () -> Unit = {},
    onSearchOnlineClick: () -> Unit = {},
) {
    var tracksExpanded by remember { mutableStateOf(true) }
    var delayExpanded by remember { mutableStateOf(false) }

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
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            // ── Header ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Subtitles,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.subtitles_cc),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    ),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Scrollable content ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // ═══ Track list ═══
                SectionHeader(
                    title = stringResource(R.string.subtitle_tracks),
                    expanded = tracksExpanded,
                    onToggle = { tracksExpanded = !tracksExpanded },
                )
                if (tracksExpanded) {
                    // Off
                    TrackRow(
                        name = stringResource(R.string.subtitle_off),
                        isSelected = selectedTrackIndex == -1,
                        onClick = { onTrackSelected(-1); onDismiss() },
                    )
                    subtitleTracks.forEachIndexed { index, name ->
                        TrackRow(
                            name = name,
                            isSelected = selectedTrackIndex == index,
                            onClick = { onTrackSelected(index); onDismiss() },
                        )
                    }

                    // External addition
                    ActionRow(
                        icon = Icons.Default.Add,
                        text = stringResource(R.string.add_subtitle_file),
                        onClick = onAddExternalSubtitleClick,
                    )
                    ActionRow(
                        icon = Icons.Default.Search,
                        text = stringResource(R.string.search_online),
                        onClick = onSearchOnlineClick,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(4.dp))

                // ═══ Delay ═══
                SectionHeader(
                    title = stringResource(R.string.subtitle_delay),
                    expanded = delayExpanded,
                    onToggle = { delayExpanded = !delayExpanded },
                )
                if (delayExpanded) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { onSubtitleDelayChange(subtitleDelayMs - 100) },
                            enabled = subtitleDelayMs > -5000,
                        ) {
                            Text(text = stringResource(R.string.delay_decrease), color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                        }
                        Text(
                            text = stringResource(R.string.delay_value, subtitleDelayMs),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        TextButton(
                            onClick = { onSubtitleDelayChange(subtitleDelayMs + 100) },
                            enabled = subtitleDelayMs < 5000,
                        ) {
                            Text(text = stringResource(R.string.delay_increase), color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        TextButton(
                            onClick = { onSubtitleDelayChange(0) },
                            enabled = subtitleDelayMs != 0L,
                        ) {
                            Text(
                                text = stringResource(R.string.reset),
                                color = if (subtitleDelayMs != 0L) MaterialTheme.colorScheme.primary else Color.Gray,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(4.dp))

                // ═══ Bottom actions ═══
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onStyleClick) {
                        Text(text = stringResource(R.string.style), color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(R.string.close), color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

// ── Sub-components ──

@Composable
private fun SectionHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                color = Color.White.copy(alpha = 0.6f),
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
            tint = Color.Gray,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun TrackRow(name: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = Color.Gray,
            ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = if (isSelected) Color.White else Color.LightGray,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}
