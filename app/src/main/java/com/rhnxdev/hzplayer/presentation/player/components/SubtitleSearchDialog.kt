package com.rhnxdev.hzplayer.presentation.player.components

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import com.rhnxdev.hzplayer.core.util.SubtitleLanguageResolver
import com.rhnxdev.hzplayer.presentation.player.SubtitleSearchCandidateItem
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
        // Back handling priority:
        //  1. Keyboard visible → close keyboard only
        //  2. Results layer visible → go back to candidate layer
        //  3. Otherwise → dismiss dialog
        BackHandler {
            when {
                imeVisible -> {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
                uiState.showResultsLayer -> viewModel.hideResultsLayer()
                else -> onDismiss()
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 620.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            // Two-layer navigation: candidates picker (layer 0) → subtitle results (layer 1).
            // AnimatedContent slides between the two so they feel like separate screens.
            AnimatedContent(
                targetState = uiState.showResultsLayer,
                transitionSpec = {
                    if (targetState) {
                        // Slide results in from the right
                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                    } else {
                        // Slide candidates back in from the left
                        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                    }
                },
                label = "SubtitleDialogLayer",
            ) { showResults ->
                if (showResults) {
                    // ── Layer 2: Subtitle Results ─────────────────────────────────
                    ResultsLayer(
                        uiState = uiState,
                        onBack = { viewModel.hideResultsLayer() },
                        onClose = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            onDismiss()
                        },
                        onDownload = { result ->
                            viewModel.download(
                                downloadUrl = result.downloadUrl,
                                fileName = result.releaseName,
                                onDownloaded = onSubtitleDownloaded,
                            )
                        },
                    )
                } else {
                    // ── Layer 1: Search + Candidate Picker ────────────────────────
                    CandidateLayer(
                        uiState = uiState,
                        onClose = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            onDismiss()
                        },
                        onQueryChange = viewModel::onQueryChange,
                        onTypeChange = viewModel::onTypeChange,
                        onSeasonChange = viewModel::onSeasonChange,
                        onEpisodeChange = viewModel::onEpisodeChange,
                        onSearch = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            viewModel.search()
                        },
                        onSelectCandidate = { index -> viewModel.selectCandidate(index) },
                        onShowResults = { viewModel.showResultsLayer() },
                        onHistorySelect = { query ->
                            viewModel.onQueryChange(query)
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            viewModel.search()
                        },
                        onHistoryRemove = viewModel::removeHistoryItem,
                        onHistoryClear = viewModel::clearHistory,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Layer 1 — Search field + candidate picker
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CandidateLayer(
    uiState: com.rhnxdev.hzplayer.presentation.player.SubtitleSearchUiState,
    onClose: () -> Unit,
    onQueryChange: (String) -> Unit,
    onTypeChange: (String) -> Unit,
    onSeasonChange: (String) -> Unit,
    onEpisodeChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectCandidate: (Int) -> Unit,
    onShowResults: () -> Unit,
    onHistorySelect: (String) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
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
                onClick = onClose,
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
                onClick = { onTypeChange("movie") },
                label = { Text(stringResource(R.string.subtitle_search_movie)) },
            )
            FilterChip(
                selected = uiState.searchType == "series",
                onClick = { onTypeChange("series") },
                label = { Text(stringResource(R.string.subtitle_search_series)) },
            )
        }

        if (uiState.searchType == "series") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = uiState.season,
                    onValueChange = onSeasonChange,
                    label = { Text(stringResource(R.string.subtitle_search_season)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = uiState.episode,
                    onValueChange = onEpisodeChange,
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
            onValueChange = onQueryChange,
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
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
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
                IconButton(onClick = onSearch) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.search),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Big spinner only while fetching the candidate list (title search).
        // Once candidates are visible the per-row spinner handles subtitle loading.
        if (uiState.isSearching && uiState.candidates.isEmpty()) {
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

        // Candidate list
        val candidates = uiState.candidates
        if (candidates.isNotEmpty()) {
            Text(
                text = stringResource(R.string.subtitle_search_pick_title),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                ),
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(candidates.indices.toList()) { index ->
                    val candidate = candidates[index]
                    val isSelected = index == uiState.selectedCandidateIndex
                    val isLoading = isSelected && uiState.isSearching
                    CandidateRow(
                        candidate = candidate,
                        isSelected = isSelected,
                        isLoading = isLoading,
                        // Disable all rows while any subtitle search is running
                        enabled = !uiState.isSearching,
                        onClick = {
                            if (isSelected && uiState.hasSearched) {
                                // Already loaded — just open the results layer
                                onShowResults()
                            } else {
                                onSelectCandidate(index)
                            }
                        },
                    )
                }
            }
        } else if (!uiState.isSearching && uiState.error == null) {
            // Show history when no candidates are loaded and no active search
            val history = uiState.searchHistory
            if (!uiState.hasSearched && history.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.subtitle_search_history),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        ),
                    )
                    Text(
                        text = stringResource(R.string.subtitle_search_history_clear),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.clickable(onClick = onHistoryClear),
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(history) { query ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onHistorySelect(query) }
                                .padding(vertical = 10.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = query,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(
                                onClick = { onHistoryRemove(query) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            } else if (uiState.hasSearched) {
                // No results for last search
                Text(
                    text = stringResource(R.string.subtitle_search_no_results, uiState.query),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                // Empty state hint
                Text(
                    text = stringResource(R.string.subtitle_search_hint),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: SubtitleSearchCandidateItem,
    isSelected: Boolean,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f),
            )
            .clickable(enabled = enabled, onClick = onClick)
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
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Layer 2 — Subtitle results for the selected candidate
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ResultsLayer(
    uiState: com.rhnxdev.hzplayer.presentation.player.SubtitleSearchUiState,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onDownload: (SubtitleSearchResultItem) -> Unit,
) {
    val selectedCandidate = uiState.candidates.getOrNull(uiState.selectedCandidateIndex)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        // Header with back button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selectedCandidate?.name?.ifBlank { stringResource(R.string.search_subtitles_online) }
                        ?: stringResource(R.string.search_subtitles_online),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selectedCandidate != null && selectedCandidate.year != 0) {
                    Text(
                        text = selectedCandidate.year.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        ),
                    )
                }
            }
            IconButton(
                onClick = onClose,
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
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // Loading while fetching subs for this candidate
        if (uiState.isSearching) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        // Error
        val error = uiState.error
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        // No results
        if (uiState.results.isEmpty() && !uiState.isSearching && uiState.error == null) {
            Text(
                text = stringResource(R.string.subtitle_search_no_results, uiState.query),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }

        // Results list — grouped alphabetically by language, original order kept
        // stable within each language.
        val sortedResults = remember(uiState.results) {
            uiState.results.withIndex()
                .sortedWith(
                    compareBy(
                        { SubtitleLanguageResolver.resolve(it.value.language).sortKey },
                        { it.index },
                    )
                )
                .map { it.value }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(sortedResults) { result ->
                SearchResultRow(
                    result = result,
                    isDownloading = result.downloadUrl in uiState.downloadingUrls,
                    onDownload = { onDownload(result) },
                )
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
    val lang = remember(result.language) { SubtitleLanguageResolver.resolve(result.language) }
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlagIcon(countryCode = lang.countryCode)
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
