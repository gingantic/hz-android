package com.rhnxdev.hzplayer.data.repository

import com.rhnxdev.hzplayer.data.datasource.local.room.dao.ServerConfigDao
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.StreamHistoryDao
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.StreamHistoryEntity
import com.rhnxdev.hzplayer.data.mapper.toDomain
import com.rhnxdev.hzplayer.data.mapper.toEntity
import com.rhnxdev.hzplayer.data.security.PasswordCrypto
import com.rhnxdev.hzplayer.domain.model.ServerConfig
import com.rhnxdev.hzplayer.domain.model.StreamHistoryItem
import com.rhnxdev.hzplayer.domain.repository.NetworkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkRepositoryImpl @Inject constructor(
    private val serverConfigDao: ServerConfigDao,
    private val streamHistoryDao: StreamHistoryDao,
    private val passwordCrypto: PasswordCrypto,
) : NetworkRepository {

    override fun getSavedServers(): Flow<List<ServerConfig>> =
        serverConfigDao.getAll().map { entities ->
            entities.map { it.toDomain(passwordCrypto) }
        }

    override suspend fun getServer(id: Long): ServerConfig? =
        serverConfigDao.getById(id)?.toDomain(passwordCrypto)

    override suspend fun saveServer(server: ServerConfig): Long =
        serverConfigDao.insert(server.toEntity(passwordCrypto))

    override suspend fun updateServer(server: ServerConfig) =
        serverConfigDao.update(server.toEntity(passwordCrypto))

    override suspend fun deleteServer(id: Long) =
        serverConfigDao.deleteById(id)

    override fun getStreamHistory(): Flow<List<StreamHistoryItem>> =
        streamHistoryDao.getAll().map { entities ->
            entities.map { it.toDomain() }
        }

    override fun getFavoriteStreams(): Flow<List<StreamHistoryItem>> =
        streamHistoryDao.getFavorites().map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun addStreamToHistory(url: String, title: String) {
        val existing = streamHistoryDao.findByUrl(url)
        if (existing != null) {
            streamHistoryDao.update(
                existing.copy(
                    title = title,
                    lastPlayedAt = System.currentTimeMillis(),
                ),
            )
        } else {
            streamHistoryDao.insert(
                StreamHistoryEntity(
                    url = url,
                    title = title,
                    lastPlayedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override suspend fun toggleFavorite(id: Long) {
        val item = streamHistoryDao.getById(id) ?: return
        streamHistoryDao.update(item.copy(isFavorite = !item.isFavorite))
    }

    override suspend fun deleteHistoryItem(id: Long) =
        streamHistoryDao.deleteById(id)

    override suspend fun clearHistory() =
        streamHistoryDao.clearNonFavorites()
}
