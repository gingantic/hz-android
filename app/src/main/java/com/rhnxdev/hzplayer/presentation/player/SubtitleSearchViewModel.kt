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
        // Fresh search drops the previous candidate list.
        _uiState.update {
            it.copy(isSearching = true, error = null, results = emptyList(),
                candidates = emptyList(), selectedCandidateIndex = -1, hasSearched = false)
        }
        runTitleSearch(query)
    }

    /** Re-query subtitles by an exact title id when the user picks a candidate. */
    fun selectCandidate(index: Int) {
        val candidates = _uiState.value.candidates
        if (index !in candidates.indices) return
        if (index == _uiState.value.selectedCandidateIndex) return
        val candidate = candidates[index]
        Log.i(TAG, "selectCandidate: index=$index name=${candidate.name} imdb=${candidate.imdbId} tmdb=${candidate.tmdbId}")
        _uiState.update { it.copy(isSearching = true, error = null, results = emptyList()) }
        runSubtitleSearch(candidate.imdbId, candidate.tmdbId, index)
    }

    /** v2 title search: resolve candidates (poster + year), then load first one's subs. */
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
                    _uiState.update { it.copy(candidates = items, selectedCandidateIndex = 0, hasSearched = true) }
                    // Auto-load the first candidate's subtitles; if it has an id we
                    // target it exactly, otherwise fall back to the film name.
                    val first = cands.firstOrNull()
                    runSubtitleSearch(first?.imdbId, first?.tmdbId, 0, query)
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
    }
}
