package com.rhnxdev.hzplayer.data.repository

import com.rhnxdev.hzplayer.data.datasource.local.room.dao.MediaDao
import com.rhnxdev.hzplayer.data.datasource.media.MediaScanner
import com.rhnxdev.hzplayer.data.mapper.toVideoItem
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.domain.repository.MediaRepository
import com.rhnxdev.hzplayer.presentation.preview.PreviewMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepositoryImpl @Inject constructor(
    private val mediaDao: MediaDao,
    private val mediaScanner: MediaScanner,
) : MediaRepository {

    private val previewVideos: List<VideoItem> = PreviewMedia.videoMovies.mapIndexed { index, item ->
        VideoItem(
            id = index.toLong(),
            title = item["title"] as? String ?: "",
            uri = "",
            durationMs = (item["durationMs"] as? Long) ?: 0,
            resolution = item["resolution"] as? String,
        )
    }

    override fun getAllVideos(sortType: SortType): Flow<List<VideoItem>> = flow {
        // Try Room cache first
        val cached = mediaDao.getAllVideos().first()
        if (cached.isNotEmpty()) {
            emit(applySort(cached.map { it.toVideoItem() }, sortType))
            return@flow
        }

        // Scan from MediaStore
        val scanned = mediaScanner.scanVideos().first()
        if (scanned.isNotEmpty()) {
            mediaDao.insertAll(scanned)
            emit(applySort(scanned.map { it.toVideoItem() }, sortType))
            return@flow
        }

        // Fallback to preview data
        emit(applySort(previewVideos, sortType))
    }.flowOn(Dispatchers.IO)

    override suspend fun getVideoById(id: Long): VideoItem? {
        return mediaDao.getById(id)?.toVideoItem()
            ?: previewVideos.find { it.id == id }
    }

    override suspend fun toggleFavorite(id: Long, isFavorite: Boolean) {
        mediaDao.updateFavorite(id, isFavorite)
    }

    override suspend fun updateWatchedProgress(id: Long, progress: Float) {
        mediaDao.updateWatchedProgress(id, progress)
    }

    override fun searchVideos(query: String): Flow<List<VideoItem>> {
        return mediaDao.searchVideos(query).map { entities ->
            entities.map { it.toVideoItem() }
        }
    }

    private fun applySort(videos: List<VideoItem>, sort: SortType): List<VideoItem> {
        return when (sort) {
            SortType.TITLE -> videos.sortedBy { it.title }
            SortType.DATE_ADDED -> videos.sortedByDescending { it.dateAdded }
            SortType.DURATION -> videos.sortedByDescending { it.durationMs }
            SortType.DATE_MODIFIED -> videos.sortedByDescending { it.dateModified }
            else -> videos.sortedBy { it.title }
        }
    }
}
