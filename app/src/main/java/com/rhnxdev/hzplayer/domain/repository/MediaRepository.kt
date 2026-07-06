package com.rhnxdev.hzplayer.domain.repository

import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.VideoItem
import kotlinx.coroutines.flow.Flow

interface MediaRepository {
    fun getAllVideos(sortType: SortType = SortType.TITLE): Flow<List<VideoItem>>
    suspend fun getVideoById(id: Long): VideoItem?
    suspend fun getVideosByUris(uris: List<String>): List<VideoItem>
    suspend fun toggleFavorite(id: Long, isFavorite: Boolean)
    suspend fun updateWatchedProgress(id: Long, progress: Float)
    fun searchVideos(query: String): Flow<List<VideoItem>>
}
