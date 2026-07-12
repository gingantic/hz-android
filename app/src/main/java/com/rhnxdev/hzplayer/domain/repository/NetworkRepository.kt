package com.rhnxdev.hzplayer.domain.repository

import com.rhnxdev.hzplayer.domain.model.ServerConfig
import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.StreamHistoryItem
import com.rhnxdev.hzplayer.domain.model.SortType
import kotlinx.coroutines.flow.Flow

interface NetworkRepository {
    fun getSavedServers(): Flow<List<ServerConfig>>
    suspend fun getServer(id: Long): ServerConfig?
    suspend fun saveServer(server: ServerConfig): Long
    suspend fun updateServer(server: ServerConfig)
    suspend fun deleteServer(id: Long)

    fun getStreamHistory(): Flow<List<StreamHistoryItem>>
    fun getFavoriteStreams(): Flow<List<StreamHistoryItem>>
    suspend fun addStreamToHistory(url: String, title: String)
    suspend fun toggleFavorite(id: Long)
    suspend fun deleteHistoryItem(id: Long)
    suspend fun clearHistory()

    fun getSortType(key: String): Flow<SortType>
    suspend fun setSortType(key: String, sort: SortType)
    fun getSortDirection(key: String): Flow<SortDirection>
    suspend fun setSortDirection(key: String, direction: SortDirection)
}
