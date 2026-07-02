package com.rhnxdev.hzplayer.domain.repository

import android.net.Uri

/**
 * Repository for searching and downloading online subtitles.
 */
interface SubtitleRepository {

    data class SearchResult(
        val id: String,
        val fileId: Long,
        val language: String,
        val releaseName: String,
        val downloadCount: Long,
    )

    /**
     * Search OpenSubtitles for subtitle files matching [query].
     * Returns a list of search results with file IDs for download.
     */
    suspend fun search(query: String, apiKey: String): Result<List<SearchResult>>

    /**
     * Download a subtitle by its [fileId] and save it to the app's cache directory.
     * Returns the file [Uri] of the saved subtitle, or an error.
     */
    suspend fun download(fileId: Long, fileName: String, apiKey: String): Result<Uri>
}
