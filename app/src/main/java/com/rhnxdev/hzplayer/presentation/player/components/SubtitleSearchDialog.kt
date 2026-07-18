package com.rhnxdev.hzplayer.presentation.player.components

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.presentation.player.SubtitleSearchResultItem
import com.rhnxdev.hzplayer.presentation.player.SubtitleSearchViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SubtitleSearchDialog(
    onDismiss: () -> Unit,
    onSubtitleDownloaded: (Uri) -> Unit,
    viewModel: SubtitleSearchViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Use actual IME visibility as the source of truth instead of focus state.
    // Focus and keyboard visibility can be out of sync (e.g. keyboard stays open
    // after focus is cleared on some devices, or Dialog windows handle focus differently).
    val imeVisible = WindowInsets.isImeVisible

    Dialog(
        onDismissRequest = {
            // Called on outside tap - just hide keyboard, keep dialog open.
            focusManager.clearFocus()
            keyboardController?.hide()
        },
        properties = DialogProperties(
            dismissOnBackPress = false,      // we handle back ourselves via BackHandler
            dismissOnClickOutside = false,   // prevent accidental dismiss on outside tap
            usePlatformDefaultWidth = false,
        ),
    ) {
        // BackHandler inside Dialog is reliable: no ModalBottomSheet competing for the event.
        // Two-step back: first press closes the keyboard, second press dismisses the dialog.
        BackHandler {
            if (imeVisible) {
                // Keyboard is actually visible -> close it, keep dialog alive
                focusManager.clearFocus()
                keyboardController?.hide()
            } else {
                // Keyboard is gone -> dismiss dialog
                onDismiss()
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 600.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.search_subtitles_online),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            onDismiss()
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    modifier = Modifier.padding(bottom = 12.dp),
                )

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

                // Search field
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = {
                        Text(
                            stringResource(R.string.subtitle_search_placeholder),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
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
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Loading indicator
                if (uiState.isSearching) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
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

                // Candidate title picker — capped height + scroll so many results
                // don't push the subtitle list off screen.
                val candidates = uiState.candidates
                if (candidates.isNotEmpty() && uiState.selectedCandidateIndex >= 0) {
                    Text(
                        text = stringResource(R.string.subtitle_search_pick_title),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        ),
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        candidates.forEachIndexed { index, candidate ->
                            val selected = index == uiState.selectedCandidateIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f),
                                    )
                                    .clickable(enabled = !selected && !uiState.isSearching) {
                                        viewModel.selectCandidate(index)
                                    }
                                    .padding(vertical = 8.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AsyncImage(
                                    model = candidate.posterUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(40.dp, 60.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = candidate.name.ifBlank { stringResource(R.string.unknown) },
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (candidate.year != 0) {
                                            Text(
                                                text = candidate.year.toString(),
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                                ),
                                            )
                                        }
                                        if (candidate.type.isNotBlank()) {
                                            Text(
                                                text = candidate.type.replaceFirstChar { it.uppercase() },
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold,
                                                ),
                                            )
                                        }
                                    }
                                }
                                if (selected) {
                                    Text(
                                        text = stringResource(R.string.subtitle_search_showing),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                // Empty states
                if (uiState.results.isEmpty() && !uiState.isSearching && uiState.error == null) {
                    if (uiState.hasSearched) {
                        Text(
                            text = stringResource(R.string.subtitle_search_no_results, uiState.query),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.subtitle_search_hint),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }

                // Results list — weight(1f) so it fills remaining dialog height
                // instead of collapsing to 0 inside the non-scrolling Column.
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(uiState.results) { result ->
                        SearchResultRow(
                            result = result,
                            isDownloading = result.downloadUrl in uiState.downloadingUrls,
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
    }
}

@Composable
private fun SearchResultRow(
    result: SubtitleSearchResultItem,
    isDownloading: Boolean,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f))
            .clickable(enabled = !isDownloading, onClick = onDownload)
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = result.language.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                if (result.hearingImpaired) {
                    Text(
                        text = "HI",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
                if (result.fps.isNotBlank()) {
                    Text(
                        text = result.fps,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        ),
                    )
                }
            }
        }
        if (isDownloading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            IconButton(
                onClick = onDownload,
                modifier = Modifier.size(32.dp),
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
}
