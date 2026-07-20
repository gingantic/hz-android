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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.rhnxdev.hzplayer.core.util.SubtitleLanguageResolver

/**
 * A subtitle track prepared for display: the engine index is preserved so the
 * sorted UI list can still be mapped back onto the engine's track ordering.
 */
private data class DisplayTrack(
    val engineIndex: Int,
    val label: String,
    val countryCode: String?,
    val sortKey: String,
)

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
    onSearchOnlineClick: () -> Unit = {},
) {
    var tracksExpanded by remember { mutableStateOf(true) }
    var delayExpanded by remember { mutableStateOf(false) }

    // Resolve each track's language, then sort alphabetically by language while
    // keeping the original engine index for selection callbacks.
    val displayTracks = remember(subtitleTracks) {
        subtitleTracks
            .mapIndexed { index, name ->
                val lang = SubtitleLanguageResolver.resolve(name)
                DisplayTrack(
                    engineIndex = index,
                    label = lang.displayName,
                    countryCode = lang.countryCode,
                    sortKey = lang.sortKey,
                )
            }
            .sortedWith(compareBy({ it.sortKey }, { it.engineIndex }))
    }

    SheetScaffold(
        title = stringResource(R.string.subtitles_cc),
        icon = Icons.Default.Subtitles,
        onDismiss = onDismiss,
        columnModifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // ── Scrollable content ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ═══ Track list ═══
            SectionHeader(
                title = stringResource(R.string.subtitle_tracks),
                expanded = tracksExpanded,
                onToggle = { tracksExpanded = !tracksExpanded },
            )
            if (tracksExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Off — pinned above the language-sorted tracks
                    TrackSelectionRow(
                        name = stringResource(R.string.subtitle_off),
                        isSelected = selectedTrackIndex == -1,
                        onClick = { onTrackSelected(-1); onDismiss() },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.SubtitlesOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    displayTracks.forEach { track ->
                        TrackSelectionRow(
                            name = track.label,
                            isSelected = selectedTrackIndex == track.engineIndex,
                            onClick = { onTrackSelected(track.engineIndex); onDismiss() },
                            leadingIcon = { FlagIcon(countryCode = track.countryCode) },
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // External addition actions
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
            }

            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(4.dp))

            // ═══ Delay ═══
            SectionHeader(
                title = stringResource(R.string.subtitle_delay),
                expanded = delayExpanded,
                onToggle = { delayExpanded = !delayExpanded },
            )
            if (delayExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { onSubtitleDelayChange(subtitleDelayMs - 100) },
                        enabled = subtitleDelayMs > -5000,
                        modifier = Modifier
                            .background(
                                if (subtitleDelayMs > -5000) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f),
                                CircleShape
                            )
                            .size(40.dp)
                    ) {
                        Text(
                            text = "–",
                            color = if (subtitleDelayMs > -5000) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.delay_value, subtitleDelayMs),
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        if (subtitleDelayMs != 0L) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.reset),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onSubtitleDelayChange(0) }
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { onSubtitleDelayChange(subtitleDelayMs + 100) },
                        enabled = subtitleDelayMs < 5000,
                        modifier = Modifier
                            .background(
                                if (subtitleDelayMs < 5000) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f),
                                CircleShape
                            )
                            .size(40.dp)
                    ) {
                        Text(
                            text = "+",
                            color = if (subtitleDelayMs < 5000) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(4.dp))

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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp),
        )
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
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

