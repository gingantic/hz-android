package com.rhnxdev.hzplayer.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.domain.repository.AudioRepository
import com.rhnxdev.hzplayer.domain.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val audioRepository: AudioRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (query.isBlank()) {
                _uiState.update {
                    it.copy(
                        videoResults = emptyList(),
                        audioResults = emptyList(),
                        isSearching = false,
                        hasSearched = false,
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(isSearching = true) }
            delay(300) // Debounce

            mediaRepository.searchVideos(query).collect { videos ->
                _uiState.update {
                    it.copy(
                        videoResults = videos,
                        isSearching = false,
                        hasSearched = true,
                    )
                }
            }

            audioRepository.searchSongs(query).collect { songs ->
                _uiState.update {
                    it.copy(
                        audioResults = songs,
                        isSearching = false,
                        hasSearched = true,
                    )
                }
            }
        }
    }

    fun onClearQuery() {
        _uiState.update {
            SearchUiState()
        }
        searchJob?.cancel()
    }
}
