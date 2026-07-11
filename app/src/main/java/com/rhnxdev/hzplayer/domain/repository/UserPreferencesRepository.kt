package com.rhnxdev.hzplayer.domain.repository

import com.rhnxdev.hzplayer.domain.model.DecoderMode
import com.rhnxdev.hzplayer.domain.model.OrientationMode
import com.rhnxdev.hzplayer.domain.model.ResumeMode
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
    val decoderMode: Flow<DecoderMode>
    val fileBrowserMediaMode: Flow<Boolean>
    val orientationMode: Flow<OrientationMode>
    val resumeMode: Flow<ResumeMode>

    fun getViewMode(key: String): Flow<ViewMode>
    fun getSortType(key: String): Flow<SortType>
    val minSongDurationSecs: Flow<Int>
    val debugMode: Flow<Boolean>
    val backgroundPlay: Flow<Boolean>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setAppColorArgb(argb: Int)
    suspend fun setDynamicColors(enabled: Boolean)
    suspend fun setActiveEngine(engine: EngineType)
    suspend fun setSubtitleStyle(style: SubtitleStyle)
    suspend fun setOpenSubtitlesApiKey(key: String)
    suspend fun setSeekSensitivity(sensitivity: Float)
    suspend fun setShowHiddenFiles(enabled: Boolean)
    suspend fun setUseSurfaceView(enabled: Boolean)
    suspend fun setDecoderMode(mode: DecoderMode)
    suspend fun setFileBrowserMediaMode(enabled: Boolean)
    suspend fun setOrientationMode(mode: OrientationMode)
    suspend fun setResumeMode(mode: ResumeMode)
    suspend fun setViewMode(key: String, mode: ViewMode)
    suspend fun setSortType(key: String, sort: SortType)
    suspend fun setMinSongDurationSecs(seconds: Int)
    suspend fun setDebugMode(enabled: Boolean)
    suspend fun setBackgroundPlay(enabled: Boolean)

    val selectedTabIndex: Flow<Int>

    suspend fun setSelectedTabIndex(index: Int)
}
