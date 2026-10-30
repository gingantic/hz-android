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

    /**
     * Compare-and-set a server's address. Matches only while [expectedHost] is still stored,
     * so a user edit made during a relocation is never overwritten.
     *
     * @return rows changed: 1 on success, 0 if the host was edited meanwhile.
     */
    @Query(
        "UPDATE server_configs SET host = :newHost, lastKnownIp = :ip " +
            "WHERE id = :id AND host = :expectedHost",
    )
    suspend fun updateAddress(id: Long, expectedHost: String, newHost: String, ip: String): Int

    /** Record an identity only for rows that have none, so a learned name is never overwritten. */
    @Query(
        "UPDATE server_configs SET discoveryName = :name, lastKnownIp = :ip " +
            "WHERE id = :id AND discoveryName IS NULL",
    )
    suspend fun setIdentityIfAbsent(id: Long, name: String, ip: String): Int
}
