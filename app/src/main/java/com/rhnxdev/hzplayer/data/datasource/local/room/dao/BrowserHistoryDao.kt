package com.rhnxdev.hzplayer.data.datasource.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.BrowserHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BrowserHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(historyEntity: BrowserHistoryEntity): Long

    @Query("SELECT * FROM browser_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<BrowserHistoryEntity>>

    @Query("SELECT * FROM browser_history WHERE title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchHistory(query: String): Flow<List<BrowserHistoryEntity>>

    @Query("DELETE FROM browser_history WHERE id = :id")
    suspend fun deleteHistoryItem(id: Long)

    @Query("DELETE FROM browser_history")
    suspend fun clearAllHistory()
}
