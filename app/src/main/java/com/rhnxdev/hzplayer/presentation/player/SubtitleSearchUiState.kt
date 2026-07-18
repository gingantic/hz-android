package com.rhnxdev.hzplayer.presentation.player

import androidx.compose.runtime.Immutable

@Immutable
data class SubtitleSearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val candidates: List<SubtitleSearchCandidateItem> = emptyList(),
    /** Index into [candidates] whose subtitles are currently shown. */
    val selectedCandidateIndex: Int = -1,
    val results: List<SubtitleSearchResultItem> = emptyList(),
    val error: String? = null,
    val searchType: String = "movie",
    val season: String = "",
    val episode: String = "",
    /** URLs currently being downloaded — each shows a spinner. */
    val downloadingUrls: Set<String> = emptySet(),
    /**
     * When true the dialog shows the subtitle-results layer (second screen).
     * When false it shows the candidate-picker layer (first screen).
     */
    val showResultsLayer: Boolean = false,
    /** Recent search queries, newest first. Loaded from DataStore on init. */
    val searchHistory: List<String> = emptyList(),
)

@Immutable
data class SubtitleSearchCandidateItem(
    val name: String,
    val year: Int = 0,
    val type: String,
    val posterUrl: String? = null,
    val imdbId: String? = null,
    val tmdbId: Long? = null,
)

@Immutable
data class SubtitleSearchResultItem(
    val downloadUrl: String,
    val language: String,
    val releaseName: String,
    val fps: String,
    val hearingImpaired: Boolean = false,
)
