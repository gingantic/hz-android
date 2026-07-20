package com.rhnxdev.hzplayer.data.repository

import com.rhnxdev.hzplayer.data.datasource.local.room.dao.MediaDao
import com.rhnxdev.hzplayer.data.datasource.media.MediaScanner
import com.rhnxdev.hzplayer.data.mapper.toVideoItem
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.domain.repository.MediaRepository
import com.rhnxdev.hzplayer.presentation.preview.PreviewMedia
import com.rhnxdev.hzplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class MediaRepositoryImpl @Inject constructor(
    private val mediaDao: MediaDao,
    private val mediaScanner: MediaScanner,
) : MediaRepository {

    private val previewVideos: List<VideoItem> = if (BuildConfig.DEBUG) {
        PreviewMedia.videoMovies.mapIndexed { index, item ->
            VideoItem(
                id = index.toLong(),
                title = item["title"] as? String ?: "",
                uri = item["uri"] as? String ?: "",
                durationMs = (item["durationMs"] as? Long) ?: 0,
                resolution = item["resolution"] as? String,
            )
        }
    } else emptyList()

    // In-memory cache so re-entering a tab (or config change) skips the MediaStore
    // re-scan and shows instantly. Cleared/repopulated only on a forced refresh.
    private var cachedVideos: List<VideoItem>? = null

    override fun getAllVideos(sortType: SortType, forceRefresh: Boolean): Flow<List<VideoItem>> = flow {
        // Instant path: serve the in-memory cache unless a forced refresh was requested.
        val memCache = cachedVideos
        if (memCache != null && !forceRefresh) {
            emit(applySort(memCache, sortType))
            return@flow
        }

        var emitted = false
        // Phase 1: Try Room cache (instant — emitted immediately if populated)
        val cached = mediaDao.getAllVideos().first()
        if (cached.isNotEmpty()) {
            val list = cached.map { it.toVideoItem() }
            cachedVideos = list
            emit(applySort(list, sortType))
            emitted = true
        } else if (BuildConfig.DEBUG) {
            // Phase 2: No cache — emit preview data immediately so UI never shows a blank shimmer.
            // Debug only: release builds get empty list until scan completes.
            emit(applySort(previewVideos, sortType))
            emitted = true
        }

        try {
            val scanned = mediaScanner.scanVideos().first()
            if (scanned.isNotEmpty()) {
                mediaDao.replaceVideos(scanned)
                val list = scanned.map { it.toVideoItem() }
                cachedVideos = list
                emit(applySort(list, sortType))
                emitted = true
            } else if (!emitted) {
                emit(emptyList())
                emitted = true
            }
        } catch (e: Exception) {
            if (!emitted) {
                throw e
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getVideoById(id: Long): VideoItem? {
        return mediaDao.getById(id)?.toVideoItem()
            ?: if (BuildConfig.DEBUG) previewVideos.find { it.id == id } else null
    }

    override suspend fun getVideosByUris(uris: List<String>): List<VideoItem> {
        if (uris.isEmpty()) return emptyList()
        val localVideos = mediaDao.getByUris(uris).map { it.toVideoItem() }
        val localUris = localVideos.map { it.uri }.toSet()
        val remainingUris = uris.filter { it !in localUris }
        val matchingPreviews = if (BuildConfig.DEBUG) previewVideos.filter { it.uri in remainingUris } else emptyList()
        return localVideos + matchingPreviews
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
