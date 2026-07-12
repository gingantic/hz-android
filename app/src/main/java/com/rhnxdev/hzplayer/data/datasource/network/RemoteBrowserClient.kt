package com.rhnxdev.hzplayer.data.datasource.network

import com.rhnxdev.hzplayer.domain.model.RemoteFileItem

interface RemoteBrowserClient {
    suspend fun connect()
    suspend fun listDirectory(path: String): List<RemoteFileItem>
    suspend fun disconnect()
}
