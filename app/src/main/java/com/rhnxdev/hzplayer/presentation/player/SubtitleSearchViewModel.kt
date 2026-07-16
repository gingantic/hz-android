package com.rhnxdev.hzplayer.presentation.player

import android.net.Uri
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

        _uiState.update { it.copy(isSearching = true, error = null, results = emptyList()) }

        viewModelScope.launch {
            val apiKey = userPreferencesRepository.subdlApiKey.first()
            if (apiKey.isBlank()) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        error = "SubDL API key not set. Configure it in Settings."
                    )
                }
                return@launch
            }

            val type = _uiState.value.searchType
            val season = _uiState.value.season.toIntOrNull()
            val episode = _uiState.value.episode.toIntOrNull()
            subtitleRepository.search(query, apiKey, null, type, season, episode)
                .onSuccess { results ->
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            results = results.map { sr ->
                                SubtitleSearchResultItem(
                                    downloadUrl = sr.downloadUrl,
                                    language = sr.language,
                                    releaseName = sr.releaseName,
                                    fps = sr.fps,
                                )
                            },
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            error = error.message ?: "Search failed",
                        )
                    }
                }
        }
    }

    fun download(downloadUrl: String, fileName: String, onDownloaded: (Uri) -> Unit) {
        viewModelScope.launch {
            val apiKey = userPreferencesRepository.subdlApiKey.first()
            subtitleRepository.download(downloadUrl, fileName, apiKey)
                .onSuccess { uri -> onDownloaded(uri) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(error = "Download failed: ${error.message}")
                    }
                }
        }
    }
}
