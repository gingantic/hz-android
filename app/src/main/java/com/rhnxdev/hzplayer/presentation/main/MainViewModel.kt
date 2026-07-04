package com.rhnxdev.hzplayer.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val selectedTabIndex: Int = 0,
    val isReady: Boolean = false,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val savedIndex = userPreferencesRepository.selectedTabIndex.first()
            _uiState.value = MainUiState(
                selectedTabIndex = savedIndex,
                isReady = true,
            )
        }
    }

    /** Update in-memory tab index instantly — no disk I/O. */
    fun setSelectedTabIndex(index: Int) {
        _uiState.value = _uiState.value.copy(selectedTabIndex = index)
    }

    /** Persist the current tab index to DataStore. Call on lifecycle stop. */
    fun persistTabIndex() {
        val index = _uiState.value.selectedTabIndex
        viewModelScope.launch {
            userPreferencesRepository.setSelectedTabIndex(index)
        }
    }
}
