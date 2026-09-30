package com.rhnxdev.hzplayer.data.datasource.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.PlayHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayHistoryDao {

    /** All history, most recently played first. */
    @Query("SELECT * FROM play_history ORDER BY playedAt DESC")
    fun getAll(): Flow<List<PlayHistoryEntity>>

    /** Insert or bump an existing row (dedup by uri primary key). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlayHistoryEntity)

    @Query("DELETE FROM play_history WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("DELETE FROM play_history")
    suspend fun clear()
}
