package com.rhnxdev.hzplayer.domain.repository

import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.ViewMode
import kotlinx.coroutines.flow.Flow

interface UserPreferencesRepository {
    val isDarkTheme: Flow<Boolean>
    val useDynamicColors: Flow<Boolean>

    fun getViewMode(key: String): Flow<ViewMode>
    fun getSortType(key: String): Flow<SortType>

    suspend fun setDarkTheme(enabled: Boolean)
    suspend fun setDynamicColors(enabled: Boolean)
    suspend fun setViewMode(key: String, mode: ViewMode)
    suspend fun setSortType(key: String, sort: SortType)
}
