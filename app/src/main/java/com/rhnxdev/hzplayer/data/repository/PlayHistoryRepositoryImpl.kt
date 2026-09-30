package com.rhnxdev.hzplayer.data.repository

import com.rhnxdev.hzplayer.data.datasource.local.room.dao.PlayHistoryDao
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.PlayHistoryEntity
import com.rhnxdev.hzplayer.domain.model.PlayHistoryItem
import com.rhnxdev.hzplayer.domain.repository.PlayHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class PlayHistoryRepositoryImpl @Inject constructor(
    private val dao: PlayHistoryDao,
) : PlayHistoryRepository {

    override fun observeHistory(): Flow<List<PlayHistoryItem>> =
        dao.getAll().map { rows -> rows.map { it.toItem() } }

    override suspend fun recordPlay(
        uri: String,
        title: String,
        isVideo: Boolean,
        artist: String?,
        mimeType: String?,
        thumbnailUri: String?,
    ) {
        if (uri.isBlank()) return
        dao.upsert(
            PlayHistoryEntity(
                uri = uri,
                title = title,
                isVideo = isVideo,
                playedAt = System.currentTimeMillis(),
                artist = artist,
                mimeType = mimeType,
                thumbnailUri = thumbnailUri,
            ),
        )
    }

    override suspend fun remove(uri: String) = dao.deleteByUri(uri)

    override suspend fun clear() = dao.clear()

    private fun PlayHistoryEntity.toItem() = PlayHistoryItem(
        uri = uri,
        title = title,
        isVideo = isVideo,
        playedAt = playedAt,
        artist = artist,
        mimeType = mimeType,
        thumbnailUri = thumbnailUri,
    )
}
