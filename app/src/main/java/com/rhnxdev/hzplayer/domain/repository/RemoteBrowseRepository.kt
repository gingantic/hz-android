package com.rhnxdev.hzplayer.domain.repository

import com.rhnxdev.hzplayer.domain.model.FolderCounts
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.domain.model.ServerConfig

interface RemoteBrowseRepository {
    suspend fun listDirectory(server: ServerConfig, path: String): Result<List<RemoteFileItem>>
    suspend fun enrichDirectory(
        server: ServerConfig,
        items: List<RemoteFileItem>,
        onCount: suspend (path: String, counts: FolderCounts) -> Unit,
    )
    fun buildPlaybackUri(server: ServerConfig, remotePath: String): String
}
