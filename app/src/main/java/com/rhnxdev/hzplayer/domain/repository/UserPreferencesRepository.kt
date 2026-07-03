package com.rhnxdev.hzplayer.domain.repository

import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.SubtitleStyle
import com.rhnxdev.hzplayer.domain.model.ViewMode
import com.rhnxdev.hzplayer.domain.player.EngineType
import kotlinx.coroutines.flow.Flow

interface UserPreferencesRepository {
    val isDarkTheme: Flow<Boolean>
    val useDynamicColors: Flow<Boolean>
    val activeEngine: Flow<EngineType>
    val subtitleStyle: Flow<SubtitleStyle>
    val openSubtitlesApiKey: Flow<String>

    fun getViewMode(key: String): Flow<ViewMode>
    fun getSortType(key: String): Flow<SortType>

    suspend fun setDarkTheme(enabled: Boolean)
    suspend fun setDynamicColors(enabled: Boolean)
    suspend fun setActiveEngine(engine: EngineType)
    suspend fun setSubtitleStyle(style: SubtitleStyle)
    suspend fun setOpenSubtitlesApiKey(key: String)
    suspend fun setViewMode(key: String, mode: ViewMode)
    suspend fun setSortType(key: String, sort: SortType)
}
