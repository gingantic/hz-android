package com.rhnxdev.hzplayer.data.datasource.local.room.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "server_configs")
data class ServerConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    val encryptedPassword: String,
    val basePath: String,
    val createdAt: Long,
)
