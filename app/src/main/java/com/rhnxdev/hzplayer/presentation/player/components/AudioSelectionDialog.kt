package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
            // ModalBottomSheet already insets for the nav bar; don't add it again
            // or the bottom gap is doubled.
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
            Spacer(modifier = Modifier.height(12.dp))

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
                            color = Color.White.copy(alpha = 0.5f),
                        ),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    audioTracks.forEachIndexed { index, name ->
                        AudioTrackRow(
                            name = name,
                            isSelected = selectedTrackIndex == index,
                            onClick = { onTrackSelected(index); onDismiss() },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Close button
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(text = stringResource(R.string.close), color = Color.White.copy(alpha = 0.7f))
                }
            }
    }
}

@Composable
private fun AudioTrackRow(name: String, isSelected: Boolean, onClick: () -> Unit) {
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
    }
}
