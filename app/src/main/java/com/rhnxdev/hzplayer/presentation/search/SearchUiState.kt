package com.rhnxdev.hzplayer.presentation.search

import com.rhnxdev.hzplayer.domain.model.Album
import com.rhnxdev.hzplayer.domain.model.Artist
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.VideoItem

import androidx.compose.runtime.Immutable

@Immutable
data class SearchUiState(
    val query: String = "",
    val videoResults: List<VideoItem> = emptyList(),
    val audioResults: List<AudioItem> = emptyList(),
    val albumResults: List<Album> = emptyList(),
    val artistResults: List<Artist> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
)
