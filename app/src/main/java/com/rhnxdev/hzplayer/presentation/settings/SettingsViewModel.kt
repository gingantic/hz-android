package com.rhnxdev.hzplayer.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    val openSubtitlesApiKey: StateFlow<String> = prefs.openSubtitlesApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val seekSensitivity: StateFlow<Float> = prefs.seekSensitivity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)

    val showHiddenFiles: StateFlow<Boolean> = prefs.showHiddenFiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val useSurfaceView: StateFlow<Boolean> = prefs.useSurfaceView
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun saveOpenSubtitlesApiKey(key: String) {
        viewModelScope.launch { prefs.setOpenSubtitlesApiKey(key) }
    }

    fun saveSeekSensitivity(value: Float) {
        viewModelScope.launch { prefs.setSeekSensitivity(value) }
    }

    fun setShowHiddenFiles(enabled: Boolean) {
        viewModelScope.launch { prefs.setShowHiddenFiles(enabled) }
    }

    fun saveUseSurfaceView(enabled: Boolean) {
        viewModelScope.launch { prefs.setUseSurfaceView(enabled) }
    }
}
