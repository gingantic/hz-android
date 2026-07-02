package com.rhnxdev.hzplayer.data.datasource.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.ServerConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerConfigDao {

    @Query("SELECT * FROM server_configs ORDER BY createdAt DESC")
    fun getAll(): Flow<List<ServerConfigEntity>>

    @Query("SELECT * FROM server_configs WHERE id = :id")
    suspend fun getById(id: Long): ServerConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ServerConfigEntity): Long

    @Update
    suspend fun update(entity: ServerConfigEntity)

    @Query("DELETE FROM server_configs WHERE id = :id")
    suspend fun deleteById(id: Long)
}
