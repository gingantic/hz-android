package com.rhnxdev.hzplayer.presentation.player.components

import android.net.Uri
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
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rhnxdev.hzplayer.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rhnxdev.hzplayer.presentation.player.SubtitleSearchViewModel
import com.rhnxdev.hzplayer.presentation.player.SubtitleSearchResultItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSearchDialog(
    onDismiss: () -> Unit,
    onSubtitleDownloaded: (Uri) -> Unit,
    viewModel: SubtitleSearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    SheetScaffold(
        title = stringResource(R.string.search_subtitles_online),
        icon = Icons.Default.Search,
        onDismiss = onDismiss,
        columnModifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Type toggle (Movie / Series)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.subtitle_search_type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            FilterChip(
                selected = uiState.searchType == "movie",
                onClick = { viewModel.onTypeChange("movie") },
                label = { Text(stringResource(R.string.subtitle_search_movie)) },
            )
            FilterChip(
                selected = uiState.searchType == "series",
                onClick = { viewModel.onTypeChange("series") },
                label = { Text(stringResource(R.string.subtitle_search_series)) },
            )
        }

        AnimatedVisibility(visible = uiState.searchType == "series") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = uiState.season,
                    onValueChange = viewModel::onSeasonChange,
                    label = { Text(stringResource(R.string.subtitle_search_season)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = uiState.episode,
                    onValueChange = viewModel::onEpisodeChange,
                    label = { Text(stringResource(R.string.subtitle_search_episode)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search field with integrated trailing search icon
        OutlinedTextField(
            value = uiState.query,
            onValueChange = viewModel::onQueryChange,
            placeholder = { Text(stringResource(R.string.subtitle_search_placeholder), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            ),
            trailingIcon = {
                IconButton(onClick = viewModel::search) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.search),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Loading indicator
        if (uiState.isSearching) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Error message
        val error = uiState.error
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        // Search results state / list
        if (uiState.results.isEmpty() && !uiState.isSearching && uiState.error == null) {
            Text(
                text = stringResource(R.string.subtitle_search_hint),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(uiState.results) { result ->
                SearchResultRow(
                    result = result,
                    onDownload = {
                        viewModel.download(
                            downloadUrl = result.downloadUrl,
                            fileName = result.releaseName,
                            onDownloaded = onSubtitleDownloaded,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    result: SubtitleSearchResultItem,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f))
            .clickable(onClick = onDownload)
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.releaseName.ifBlank { stringResource(R.string.unknown) },
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = result.language.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    ),
                )
                if (result.fps.isNotBlank()) {
                    Text(
                        text = result.fps,
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)),
                    )
                }
            }
        }
        IconButton(
            onClick = onDownload,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = stringResource(R.string.download),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

