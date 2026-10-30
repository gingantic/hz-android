package com.rhnxdev.hzplayer.domain.repository

import com.rhnxdev.hzplayer.domain.model.ServerConfig
import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.StreamHistoryItem
import com.rhnxdev.hzplayer.domain.model.SortType
import kotlinx.coroutines.flow.Flow

interface NetworkRepository {
    fun getSavedServers(): Flow<List<ServerConfig>>
    suspend fun saveServer(server: ServerConfig): Long
    suspend fun updateServer(server: ServerConfig)
    suspend fun deleteServer(id: Long)

    /**
     * Move a saved server to a new address and re-point the resume / recently-played URIs that
     * embed the old one, all in one transaction.
     *
     * Compare-and-set: does nothing and returns false if the stored host is no longer
     * [expectedHost] (the user edited the server meanwhile).
     *
     * @param oldUriPrefix — playback-URI prefix for the old address (`scheme://user:pass@host:port/`).
     * @param newUriPrefix — same prefix for the new address.
     */
    suspend fun relocateServer(
        id: Long,
        expectedHost: String,
        newHost: String,
        newIp: String,
        oldUriPrefix: String,
        newUriPrefix: String,
    ): Boolean

    /** Store [name] and [ip] for a saved server that has no identity yet. Never overwrites one. */
    suspend fun setServerIdentityIfAbsent(id: Long, name: String, ip: String)

    fun getStreamHistory(): Flow<List<StreamHistoryItem>>
    suspend fun addStreamToHistory(
        url: String,
        title: String,
        headersJson: String? = null,
        pageUrl: String? = null,
        mimeType: String? = null,
    )
    suspend fun toggleFavorite(id: Long)
    suspend fun deleteHistoryItem(id: Long)
    suspend fun clearHistory()

    fun getSortType(key: String): Flow<SortType>
    suspend fun setSortType(key: String, sort: SortType)
    fun getSortDirection(key: String): Flow<SortDirection>
    suspend fun setSortDirection(key: String, direction: SortDirection)
}
