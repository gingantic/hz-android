package com.rhnxdev.hzplayer.presentation.player

data class SubtitleSearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<SubtitleSearchResultItem> = emptyList(),
    val error: String? = null,
)

data class SubtitleSearchResultItem(
    val id: String,
    val fileId: Long,
    val language: String,
    val releaseName: String,
    val downloadCount: Long,
)
