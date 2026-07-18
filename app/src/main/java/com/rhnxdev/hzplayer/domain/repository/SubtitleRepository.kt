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
        val hearingImpaired: Boolean = false,
    )

    /**
     * A candidate title from SubDL's title search. Carries [posterUrl] and [year]
     * so the picker can show thumbnails. Picking one re-queries by [imdbId]/[tmdbId]
     * to load that exact title's subtitles.
     */
    data class SearchCandidate(
        val name: String,
        val year: Int,
        val type: String,
        val imdbId: String?,
        val tmdbId: Long?,
        val posterUrl: String?,
    )

    /**
     * Resolve candidate titles (with posters + years) for [query].
     */
    suspend fun searchTitles(
        query: String,
        apiKey: String,
        type: String = "movie",
    ): Result<List<SearchCandidate>>

    /**
     * Fetch subtitles for a title. Prefers an exact id ([imdbId]/[tmdbId]);
     * falls back to [query] (film name) if no id is given.
     */
    suspend fun searchSubtitles(
        query: String,
        apiKey: String,
        language: String? = null,
        type: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        imdbId: String? = null,
        tmdbId: Long? = null,
    ): Result<List<SearchResult>>

    /**
     * Download a subtitle from its [downloadUrl] and save it to the app's cache
     * directory. Returns the file [Uri] of the saved subtitle, or an error.
     */
    suspend fun download(downloadUrl: String, fileName: String, apiKey: String): Result<Uri>
}
