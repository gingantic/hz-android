package com.rhnxdev.hzplayer.data.datasource.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.BrowserHistoryEntity
import kotlinx.coroutines.flow.Flow

/** Aggregated omnibox suggestion row: one entry per unique URL with visit stats. */
data class UrlSuggestionRow(
    val url: String,
    val title: String,
    val lastVisited: Long,
    val visitCount: Int,
)

@Dao
interface BrowserHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(historyEntity: BrowserHistoryEntity): Long

    @Query("SELECT * FROM browser_history ORDER BY timestamp DESC LIMIT 500")
    fun getAllHistory(): Flow<List<BrowserHistoryEntity>>

    @Query("SELECT * FROM browser_history WHERE title LIKE '%' || :query || '%' OR url LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT 500")
    fun searchHistory(query: String): Flow<List<BrowserHistoryEntity>>

    // Chrome-like omnibox ranking: empty query returns most visited sites,
    // otherwise substring matches ordered by visit count then recency.
    @Query(
        "SELECT url, title, MAX(timestamp) AS lastVisited, COUNT(url) AS visitCount " +
            "FROM browser_history " +
            "WHERE url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' " +
            "GROUP BY url " +
            "ORDER BY visitCount DESC, lastVisited DESC " +
            "LIMIT :limit"
    )
    fun getUrlSuggestions(query: String, limit: Int): Flow<List<UrlSuggestionRow>>

    @Query("DELETE FROM browser_history WHERE id = :id")
    suspend fun deleteHistoryItem(id: Long)

    @Query("DELETE FROM browser_history")
    suspend fun clearAllHistory()
}
