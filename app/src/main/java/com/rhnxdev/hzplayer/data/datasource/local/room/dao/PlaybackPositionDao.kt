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

    /**
     * Re-point rows whose URI starts with [oldPrefix] at [newPrefix] after a server's address
     * changed. Uses `substr` equality rather than `LIKE` so `_` / `%` inside a stored
     * credential can never act as wildcards. `OR REPLACE` because `uri` is the primary key
     * and the rewritten URI may already exist.
     */
    @Query(
        "UPDATE OR REPLACE playback_position " +
            "SET uri = :newPrefix || substr(uri, length(:oldPrefix) + 1) " +
            "WHERE substr(uri, 1, length(:oldPrefix)) = :oldPrefix",
    )
    suspend fun rewriteUriPrefix(oldPrefix: String, newPrefix: String): Int
}
