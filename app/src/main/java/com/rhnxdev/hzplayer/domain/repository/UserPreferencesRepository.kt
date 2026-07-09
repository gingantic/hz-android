package com.rhnxdev.hzplayer.domain.repository

import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.SubtitleStyle
import com.rhnxdev.hzplayer.domain.model.ThemeMode
import com.rhnxdev.hzplayer.domain.model.ViewMode
import com.rhnxdev.hzplayer.domain.player.EngineType
import kotlinx.coroutines.flow.Flow

interface UserPreferencesRepository {
    val themeMode: Flow<ThemeMode>
    val appColorArgb: Flow<Int>
    val useDynamicColors: Flow<Boolean>
    val activeEngine: Flow<EngineType>
    val subtitleStyle: Flow<SubtitleStyle>
    val openSubtitlesApiKey: Flow<String>
    val seekSensitivity: Flow<Float>
    val showHiddenFiles: Flow<Boolean>
    val useSurfaceView: Flow<Boolean>
    val enableHdrPlayback: Flow<Boolean>
    val fileBrowserMediaMode: Flow<Boolean>

    fun getViewMode(key: String): Flow<ViewMode>
    fun getSortType(key: String): Flow<SortType>
    val minSongDurationSecs: Flow<Int>
    val debugMode: Flow<Boolean>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setAppColorArgb(argb: Int)
    suspend fun setDynamicColors(enabled: Boolean)
    suspend fun setActiveEngine(engine: EngineType)
    suspend fun setSubtitleStyle(style: SubtitleStyle)
    suspend fun setOpenSubtitlesApiKey(key: String)
    suspend fun setSeekSensitivity(sensitivity: Float)
    suspend fun setShowHiddenFiles(enabled: Boolean)
    suspend fun setUseSurfaceView(enabled: Boolean)
    suspend fun setEnableHdrPlayback(enabled: Boolean)
    suspend fun setFileBrowserMediaMode(enabled: Boolean)
    suspend fun setViewMode(key: String, mode: ViewMode)
    suspend fun setSortType(key: String, sort: SortType)
    suspend fun setMinSongDurationSecs(seconds: Int)
    suspend fun setDebugMode(enabled: Boolean)

    val selectedTabIndex: Flow<Int>

    suspend fun setSelectedTabIndex(index: Int)
}
