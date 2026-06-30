package com.rhnxdev.hzplayer.presentation.search

import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.VideoItem

data class SearchUiState(
    val query: String = "",
    val videoResults: List<VideoItem> = emptyList(),
    val audioResults: List<AudioItem> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
)
