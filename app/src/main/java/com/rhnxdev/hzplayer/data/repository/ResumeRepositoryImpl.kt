package com.rhnxdev.hzplayer.data.repository

import com.rhnxdev.hzplayer.data.datasource.local.room.dao.PlaybackPositionDao
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.PlaybackPositionEntity
import com.rhnxdev.hzplayer.domain.model.PlaybackProgress
import com.rhnxdev.hzplayer.domain.repository.ResumeRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResumeRepositoryImpl @Inject constructor(
    private val dao: PlaybackPositionDao,
) : ResumeRepository {

    override suspend fun saveProgress(uri: String, positionMs: Long, durationMs: Long) {
        if (uri.isBlank() || positionMs <= 0 || durationMs <= 0) return
        // Near the end -> finished; drop the row so the next play starts fresh.
        if (positionMs >= durationMs - END_THRESHOLD_MS) {
            dao.deleteByUri(uri)
            return
        }
        dao.upsert(
            PlaybackPositionEntity(
                uri = uri,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun getResumePosition(uri: String): Long {
        val row = dao.getByUri(uri) ?: return 0
        return when {
            row.positionMs < START_THRESHOLD_MS -> 0
            row.durationMs > 0 && row.positionMs >= row.durationMs - END_THRESHOLD_MS -> 0
            else -> row.positionMs
        }
    }

    override suspend fun getPlaybackProgressList(uris: List<String>): Map<String, PlaybackProgress> {
        if (uris.isEmpty()) return emptyMap()
        return dao.getByUris(uris).associate {
            it.uri to PlaybackProgress(it.positionMs, it.durationMs)
        }
    }

    companion object {
        private const val START_THRESHOLD_MS = 5_000L
        private const val END_THRESHOLD_MS = 10_000L
    }
}
