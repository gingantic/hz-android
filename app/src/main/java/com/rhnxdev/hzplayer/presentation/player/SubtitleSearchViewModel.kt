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

    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isBlank()) return

        _uiState.update { it.copy(isSearching = true, error = null, results = emptyList()) }

        viewModelScope.launch {
            val apiKey = userPreferencesRepository.openSubtitlesApiKey.first()
            if (apiKey.isBlank()) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        error = "OpenSubtitles API key not set. Configure it in Settings."
                    )
                }
                return@launch
            }

            subtitleRepository.search(query, apiKey)
                .onSuccess { results ->
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            results = results.map { sr ->
                                SubtitleSearchResultItem(
                                    id = sr.id,
                                    fileId = sr.fileId,
                                    language = sr.language,
                                    releaseName = sr.releaseName,
                                    downloadCount = sr.downloadCount,
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

    fun download(fileId: Long, fileName: String, onDownloaded: (Uri) -> Unit) {
        viewModelScope.launch {
            val apiKey = userPreferencesRepository.openSubtitlesApiKey.first()
            subtitleRepository.download(fileId, fileName, apiKey)
                .onSuccess { uri -> onDownloaded(uri) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(error = "Download failed: ${error.message}")
                    }
                }
        }
    }
}
