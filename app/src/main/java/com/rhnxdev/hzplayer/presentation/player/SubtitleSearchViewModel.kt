package com.rhnxdev.hzplayer.presentation.player

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.domain.repository.SubtitleRepository
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SubtitleSearchViewModel @Inject constructor(
    private val subtitleRepository: SubtitleRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubtitleSearchUiState())
    val uiState: StateFlow<SubtitleSearchUiState> = _uiState.asStateFlow()

    init {
        // Load persisted search history when the ViewModel is created.
        viewModelScope.launch {
            val history = userPreferencesRepository.subtitleSearchHistory.first()
            _uiState.update { it.copy(searchHistory = history) }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun onTypeChange(type: String) {
        _uiState.update { it.copy(searchType = type) }
    }

    fun onSeasonChange(season: String) {
        _uiState.update { it.copy(season = season.filter { c -> c.isDigit() }) }
    }

    fun onEpisodeChange(episode: String) {
        _uiState.update { it.copy(episode = episode.filter { c -> c.isDigit() }) }
    }

    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isBlank()) return
        Log.i(TAG, "search: query=$query type=${_uiState.value.searchType}")
        // Add to history (deduplicate, newest first, cap at 10).
        val updatedHistory = (_uiState.value.searchHistory
            .filterNot { it.equals(query, ignoreCase = true) } + query)
            .takeLast(10)
            .reversed()
        // Fresh search drops the previous candidate list and collapses the results layer.
        _uiState.update {
            it.copy(
                isSearching = true,
                error = null,
                results = emptyList(),
                candidates = emptyList(),
                selectedCandidateIndex = -1,
                hasSearched = false,
                showResultsLayer = false,
                searchHistory = updatedHistory,
            )
        }
        // Persist history asynchronously.
        viewModelScope.launch {
            userPreferencesRepository.setSubtitleSearchHistory(updatedHistory)
        }
        runTitleSearch(query)
    }

    /** Remove a single entry from search history. */
    fun removeHistoryItem(query: String) {
        val updated = _uiState.value.searchHistory.filterNot { it == query }
        _uiState.update { it.copy(searchHistory = updated) }
        viewModelScope.launch {
            userPreferencesRepository.setSubtitleSearchHistory(updated)
        }
    }

    /** Clear all search history. */
    fun clearHistory() {
        _uiState.update { it.copy(searchHistory = emptyList()) }
        viewModelScope.launch {
            userPreferencesRepository.setSubtitleSearchHistory(emptyList())
        }
    }

    /** Re-query subtitles by an exact title id when the user picks a candidate. */
    fun selectCandidate(index: Int) {
        val candidates = _uiState.value.candidates
        if (index !in candidates.indices) return
        if (index == _uiState.value.selectedCandidateIndex) return
        val candidate = candidates[index]
        Log.i(TAG, "selectCandidate: index=$index name=${candidate.name} imdb=${candidate.imdbId} tmdb=${candidate.tmdbId}")
        // Update selectedCandidateIndex immediately so the UI highlights the tapped
        // row right away instead of waiting for the network round-trip to finish.
        _uiState.update {
            it.copy(
                isSearching = true,
                error = null,
                results = emptyList(),
                selectedCandidateIndex = index,
            )
        }
        // Pass the candidate's own name as the query fallback so that if the candidate
        // has no imdbId/tmdbId the API still searches for the correct title rather than
        // reusing the original user-typed query (which may differ from the candidate name).
        runSubtitleSearch(candidate.imdbId, candidate.tmdbId, index, candidate.name)
    }

    /** Show / hide the results overlay layer. */
    fun showResultsLayer() {
        _uiState.update { it.copy(showResultsLayer = true) }
    }

    fun hideResultsLayer() {
        _uiState.update { it.copy(showResultsLayer = false) }
    }

    /** v2 title search: resolve candidates (poster + year) and show the list for the user to pick. */
    private fun runTitleSearch(query: String) {
        viewModelScope.launch {
            val apiKey = userPreferencesRepository.subdlApiKey.first()
            if (apiKey.isBlank()) {
                _uiState.update {
                    it.copy(isSearching = false,
                        error = "SubDL API key not set. Configure it in Settings.")
                }
                return@launch
            }
            val type = _uiState.value.searchType
            subtitleRepository.searchTitles(query, apiKey, type)
                .onSuccess { cands ->
                    val items = cands.map { c ->
                        SubtitleSearchCandidateItem(
                            name = c.name, year = c.year, type = c.type,
                            posterUrl = c.posterUrl, imdbId = c.imdbId, tmdbId = c.tmdbId,
                        )
                    }
                    // Show the candidate list; nothing is selected yet — the user picks.
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            hasSearched = true,
                            candidates = items,
                            selectedCandidateIndex = -1,
                        )
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "title search failed: ${error.message}")
                    _uiState.update {
                        it.copy(isSearching = false, hasSearched = true,
                            error = error.message ?: "Search failed")
                    }
                }
        }
    }

    private fun runSubtitleSearch(imdbId: String?, tmdbId: Long?, selectedIndex: Int, query: String? = null) {
        viewModelScope.launch {
            val apiKey = userPreferencesRepository.subdlApiKey.first()
            if (apiKey.isBlank()) {
                _uiState.update {
                    it.copy(isSearching = false,
                        error = "SubDL API key not set. Configure it in Settings.")
                }
                return@launch
            }
            val type = _uiState.value.searchType
            val season = _uiState.value.season.toIntOrNull()
            val episode = _uiState.value.episode.toIntOrNull()
            subtitleRepository.searchSubtitles(
                query ?: _uiState.value.query.trim(), apiKey, null, type, season, episode, imdbId, tmdbId
            ).onSuccess { subs ->
                Log.i(TAG, "subtitle search OK: ${subs.size} subs")
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        hasSearched = true,
                        selectedCandidateIndex = selectedIndex,
                        // Automatically open the results layer once subtitles are ready.
                        showResultsLayer = true,
                        results = subs.map { sr ->
                            SubtitleSearchResultItem(
                                downloadUrl = sr.downloadUrl,
                                language = sr.language,
                                releaseName = sr.releaseName,
                                fps = sr.fps,
                                hearingImpaired = sr.hearingImpaired,
                            )
                        },
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "subtitle search failed: ${error.message}")
                _uiState.update {
                    it.copy(isSearching = false, hasSearched = true,
                        error = error.message ?: "Search failed")
                }
            }
        }
    }

    fun download(downloadUrl: String, fileName: String, onDownloaded: (Uri) -> Unit) {
        Log.i(TAG, "download: fileName=$fileName")
        _uiState.update { it.copy(downloadingUrls = it.downloadingUrls + downloadUrl) }
        viewModelScope.launch {
            val apiKey = userPreferencesRepository.subdlApiKey.first()
            subtitleRepository.download(downloadUrl, fileName, apiKey)
                .onSuccess { uri ->
                    Log.i(TAG, "download OK: $uri")
                    _uiState.update { it.copy(downloadingUrls = it.downloadingUrls - downloadUrl) }
                    onDownloaded(uri)
                }
                .onFailure { error ->
                    Log.w(TAG, "download failed: ${error.message}")
                    _uiState.update {
                        it.copy(
                            downloadingUrls = it.downloadingUrls - downloadUrl,
                            error = "Download failed: ${error.message}"
                        )
                    }
                }
        }
    }

    companion object {
        private const val TAG = "SubtitleSearchVM"
        private const val MAX_HISTORY = 10
    }
}
