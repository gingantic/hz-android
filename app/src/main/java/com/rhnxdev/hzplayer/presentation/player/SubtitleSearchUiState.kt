package com.rhnxdev.hzplayer.presentation.player

import androidx.compose.runtime.Immutable

@Immutable
data class SubtitleSearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<SubtitleSearchResultItem> = emptyList(),
    val error: String? = null,
    val searchType: String = "movie",
    val season: String = "",
    val episode: String = "",
)

@Immutable
data class SubtitleSearchResultItem(
    val downloadUrl: String,
    val language: String,
    val releaseName: String,
    val fps: String,
)
