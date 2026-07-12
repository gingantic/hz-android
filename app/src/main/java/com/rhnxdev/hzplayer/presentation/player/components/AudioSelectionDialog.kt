package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSelectionDialog(
    audioTracks: List<String>,
    selectedTrackIndex: Int,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetScaffold(
        title = stringResource(R.string.audio_track),
        icon = Icons.Default.MusicNote,
        onDismiss = onDismiss,
        columnModifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // ── Track list ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (audioTracks.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_alternate_audio),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    ),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                audioTracks.forEachIndexed { index, name ->
                    TrackSelectionRow(
                        name = name,
                        isSelected = selectedTrackIndex == index,
                        onClick = { onTrackSelected(index); onDismiss() },
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

