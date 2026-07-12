package com.rhnxdev.hzplayer.presentation.main

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import com.rhnxdev.hzplayer.presentation.navigation.AppDestination
import com.rhnxdev.hzplayer.presentation.navigation.bottomNavDestinations
import dagger.hilt.android.lifecycle.HiltViewModel
import com.rhnxdev.hzplayer.domain.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class MainUiState(
    val selectedTabIndex: Int = 0,
    val isReady: Boolean = false,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = userPreferencesRepository.themeMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemeMode.SYSTEM,
        )

    val appColorArgb: StateFlow<Int> = userPreferencesRepository.appColorArgb
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0xFFE85E00.toInt(),
        )

    val useDynamicColors: StateFlow<Boolean> = userPreferencesRepository.useDynamicColors
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false,
        )

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val savedIndex = userPreferencesRepository.selectedTabIndex.first()
            // Never restore to the Settings tab on startup
            val isSettings = bottomNavDestinations.getOrNull(savedIndex) is AppDestination.Settings
            val clampedIndex = if (isSettings) 0 else savedIndex.coerceIn(0, bottomNavDestinations.lastIndex)
            _uiState.value = MainUiState(
                selectedTabIndex = clampedIndex,
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
        // Don't save if user is on Settings — keep the previous valid tab
        if (bottomNavDestinations.getOrNull(index) is AppDestination.Settings) return
        viewModelScope.launch {
            userPreferencesRepository.setSelectedTabIndex(index)
        }
    }
}
