package com.rhnxdev.hzplayer.presentation.history

import androidx.compose.runtime.Immutable
import com.rhnxdev.hzplayer.domain.model.PlayHistoryItem

@Immutable
data class HistoryUiState(
    val items: List<PlayHistoryItem> = emptyList(),
    val isLoading: Boolean = true,
)
