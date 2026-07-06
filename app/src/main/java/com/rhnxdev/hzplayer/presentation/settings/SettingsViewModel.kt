package com.rhnxdev.hzplayer.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.domain.model.ThemeMode
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository,
    private val mediaDao: com.rhnxdev.hzplayer.data.datasource.local.room.dao.MediaDao,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    fun clearAllCache() {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Clear room database cached media scan entries
            try {
                mediaDao.deleteAll()
            } catch (_: Exception) {}

            // 2. Clear custom video thumbs folder
            try {
                val thumbsDir = java.io.File(context.cacheDir, "video_thumbs")
                if (thumbsDir.exists() && thumbsDir.isDirectory) {
                    thumbsDir.deleteRecursively()
                }
            } catch (_: Exception) {}

            // 3. Clear Coil memory and disk caches
            try {
                val imageLoader = coil3.SingletonImageLoader.get(context)
                imageLoader.memoryCache?.clear()
                imageLoader.diskCache?.clear()
            } catch (_: Exception) {}

            // 4. Show success toast
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Cache cleared successfully", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    val themeMode: StateFlow<ThemeMode> = prefs.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.DARK)

    val useDynamicColors: StateFlow<Boolean> = prefs.useDynamicColors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val appColorArgb: StateFlow<Int> = prefs.appColorArgb
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0xFFE85E00.toInt())

    val openSubtitlesApiKey: StateFlow<String> = prefs.openSubtitlesApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val seekSensitivity: StateFlow<Float> = prefs.seekSensitivity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.0f)

    val showHiddenFiles: StateFlow<Boolean> = prefs.showHiddenFiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val useSurfaceView: StateFlow<Boolean> = prefs.useSurfaceView
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val minSongDurationSecs: StateFlow<Int> = prefs.minSongDurationSecs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun saveThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun saveDynamicColors(enabled: Boolean) {
        viewModelScope.launch { prefs.setDynamicColors(enabled) }
    }

    fun saveAppColorArgb(argb: Int) {
        viewModelScope.launch { prefs.setAppColorArgb(argb) }
    }

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

    fun saveMinSongDurationSecs(seconds: Int) {
        viewModelScope.launch { prefs.setMinSongDurationSecs(seconds) }
    }
}
