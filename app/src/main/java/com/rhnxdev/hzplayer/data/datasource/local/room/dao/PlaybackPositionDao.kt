package com.rhnxdev.hzplayer.data.datasource.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.PlaybackPositionEntity

@Dao
interface PlaybackPositionDao {

    @Query("SELECT * FROM playback_position WHERE uri = :uri")
    suspend fun getByUri(uri: String): PlaybackPositionEntity?

    @Query("SELECT * FROM playback_position WHERE uri IN (:uris)")
    suspend fun getByUris(uris: List<String>): List<PlaybackPositionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackPositionEntity)

    @Query("DELETE FROM playback_position WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)
}
