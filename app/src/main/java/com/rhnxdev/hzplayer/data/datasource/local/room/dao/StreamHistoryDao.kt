package com.rhnxdev.hzplayer.data.datasource.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.StreamHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StreamHistoryDao {

    @Query("SELECT * FROM stream_history ORDER BY lastPlayedAt DESC")
    fun getAll(): Flow<List<StreamHistoryEntity>>

    @Query("SELECT * FROM stream_history WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): StreamHistoryEntity?

    @Query("SELECT * FROM stream_history WHERE id = :id")
    suspend fun getById(id: Long): StreamHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: StreamHistoryEntity): Long

    @Update
    suspend fun update(entity: StreamHistoryEntity)

    @Query("DELETE FROM stream_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM stream_history WHERE isFavorite = 0")
    suspend fun clearNonFavorites()
}
