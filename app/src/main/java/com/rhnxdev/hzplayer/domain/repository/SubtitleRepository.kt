package com.rhnxdev.hzplayer.domain.repository

import android.net.Uri

/**
 * Repository for searching and downloading online subtitles.
 */
interface SubtitleRepository {

    data class SearchResult(
        val downloadUrl: String,
        val language: String,
        val releaseName: String,
        val fps: String,
    )

    /**
     * Search SubDL for subtitle files matching [query].
     * Returns a list of search results with direct download URLs.
     */
    suspend fun search(
        query: String,
        apiKey: String,
        language: String? = null,
        type: String = "movie",
        season: Int? = null,
        episode: Int? = null,
    ): Result<List<SearchResult>>

    /**
     * Download a subtitle from its [downloadUrl] and save it to the app's cache
     * directory. Returns the file [Uri] of the saved subtitle, or an error.
     */
    suspend fun download(downloadUrl: String, fileName: String, apiKey: String): Result<Uri>
}
