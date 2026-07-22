package com.rhnxdev.hzplayer.domain.repository

import com.rhnxdev.hzplayer.domain.model.BrowserHistoryItem
import kotlinx.coroutines.flow.Flow

interface BrowserHistoryRepository {
    fun getAllHistory(): Flow<List<BrowserHistoryItem>>
    fun searchHistory(query: String): Flow<List<BrowserHistoryItem>>
    suspend fun addHistory(url: String, title: String, timestamp: Long = System.currentTimeMillis()): Long
    suspend fun deleteHistoryItem(id: Long)
    suspend fun clearAllHistory()
}
