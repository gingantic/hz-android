package com.rhnxdev.hzplayer.data.datasource.remote

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Minimal HTTP client for the SubDL v2 API (https://subdl.com/developers).
 *
 * Uses plain [HttpURLConnection] + [org.json] — no Retrofit/Moshi dependency.
 * v2 authenticates with `Authorization: Bearer <key>`; the legacy `?api_key=`
 * query is still appended to download links so quota/limit handling is consistent.
 *
 * ⚠️ Android's JSONObject.optString(key, fallback) returns the STRING "null" when
 * the JSON value is a JSON null — NOT the fallback. Always use safeString() below.
 */
@Singleton
class SubdlApi @Inject constructor() {

    private companion object {
        const val TAG = "SubdlApi"
        const val BASE_URL = "https://api.subdl.com/api/v2"
        const val DOWNLOAD_BASE = "https://dl.subdl.com"
        const val POSTER_BASE = "https://subdl.com"
        const val MAX_SUBTITLE_BYTES = 5 * 1024 * 1024

        /**
         * Safe string extraction that returns [fallback] when the JSON value is
         * JSON null (Android's JSONObject.optString returns "null" for JSON nulls,
         * not the fallback — this helper fixes that).
         */
        fun JSONObject.safeString(key: String, fallback: String = ""): String {
            return if (isNull(key)) fallback else optString(key, fallback)
        }

        /** Make poster paths absolute; SubDL sometimes returns relative "/img/…" paths. */
        fun normalizePosterUrl(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return if (raw.startsWith("http://") || raw.startsWith("https://")) raw
            else "$POSTER_BASE$raw"
        }

        /**
         * Build a full download URL from a path returned by SubDL.
         * The path may be:
         *   - relative: "/subtitle/abc/def?api_key=..."  → prepend DOWNLOAD_BASE
         *   - absolute: "https://dl.subdl.com/..."       → use as-is
         */
        fun resolveDownloadUrl(path: String): String =
            if (path.startsWith("http://") || path.startsWith("https://")) path
            else DOWNLOAD_BASE + path
    }

    data class SubtitleSearchResult(
        val downloadUrl: String,
        val language: String,
        val releaseName: String,
        val fps: String,
        val hearingImpaired: Boolean,
    )

    /**
     * A candidate title from v2 `/movies/search`. Carries the poster and year so
     * the picker can show thumbnails. Picking a candidate re-queries the subtitle
     * endpoint by [imdbId]/[tmdbId] to fetch that exact title's subtitles.
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
     * Resolve candidate titles via v2 `/movies/search`. Returns matches with
     * posters and years populated.
     */
    suspend fun searchTitles(
        query: String,
        apiKey: String,
        type: String = "movie",
        limit: Int = 30,
    ): Result<List<SearchCandidate>> = withContext(Dispatchers.IO) {
        runCatching {
            val apiType = if (type == "series") "tv" else type
            val url = StringBuilder("$BASE_URL/movies/search?")
                .append("q=").append(java.net.URLEncoder.encode(query, "UTF-8"))
                .append("&type=").append(java.net.URLEncoder.encode(apiType, "UTF-8"))
                .append("&limit=").append(limit.coerceIn(1, 30))
            val conn = open(url.toString(), apiKey).apply { requestMethod = "GET" }
            try {
                val code = conn.responseCode
                if (code == 429) {
                    Log.w(TAG, "searchTitles: rate limited (429)")
                    throw RateLimitedException(conn.getHeaderField("Retry-After")?.toIntOrNull())
                }
                if (code != 200) {
                    val error = readStream(conn.errorStream ?: conn.inputStream)
                    Log.e(TAG, "searchTitles: HTTP $code for query=$query: $error")
                    throw Exception("SubDL API error $code: $error")
                }
                val json = JSONObject(readStream(conn.inputStream))
                val results = json.optJSONArray("results") ?: JSONArray()
                val out = mutableListOf<SearchCandidate>()
                for (i in 0 until results.length()) {
                    val r = results.optJSONObject(i) ?: continue
                    // safeString guards against JSON null returning the string "null"
                    val imdbId = r.safeString("imdb_id").takeIf { it.isNotBlank() }
                    val tmdbId = if (r.isNull("tmdb_id")) null
                                 else r.optLong("tmdb_id", 0L).takeIf { it != 0L }
                    val posterRaw = r.safeString("poster_url").takeIf { it.isNotBlank() }
                    out.add(
                        SearchCandidate(
                            name = r.safeString("name"),
                            year = r.optInt("year", 0),
                            type = r.safeString("type"),
                            imdbId = imdbId,
                            tmdbId = tmdbId,
                            posterUrl = normalizePosterUrl(posterRaw),
                        )
                    )
                }
                Log.i(TAG, "searchTitles: ${out.size} candidates for query=$query")
                out
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Fetch subtitles for a title via v2 `/subtitles/search`. Prefers an exact id
     * ([imdbId]/[tmdbId]); falls back to [query] (film_name) when no id is given.
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
    ): Result<List<SubtitleSearchResult>> = withContext(Dispatchers.IO) {
        runCatching {
            // SubDL expects "tv" (not "series") and comma-separated UPPERCASE codes.
            val apiType = if (type == "series") "tv" else type
            val apiLanguage = language?.uppercase()?.takeIf { it != "ALL" }
            val url = StringBuilder("$BASE_URL/subtitles/search?")
            when {
                !imdbId.isNullOrBlank() -> {
                    url.append("imdb_id=").append(java.net.URLEncoder.encode(imdbId, "UTF-8"))
                }
                tmdbId != null -> {
                    url.append("tmdb_id=").append(tmdbId)
                    url.append("&type=").append(java.net.URLEncoder.encode(apiType, "UTF-8"))
                }
                else -> {
                    url.append("film_name=").append(java.net.URLEncoder.encode(query, "UTF-8"))
                    url.append("&type=").append(java.net.URLEncoder.encode(apiType, "UTF-8"))
                }
            }
            if (!apiLanguage.isNullOrBlank()) {
                url.append("&languages=").append(java.net.URLEncoder.encode(apiLanguage, "UTF-8"))
            }
            if (apiType == "tv") {
                if (season != null) url.append("&season_number=").append(season)
                if (episode != null) url.append("&episode_number=").append(episode)
            }
            url.append("&subs_per_page=30").append("&unpack=1")
            Log.i(TAG, "searchSubtitles URL: $url")
            val conn = open(url.toString(), apiKey).apply { requestMethod = "GET" }
            try {
                val code = conn.responseCode
                if (code == 429) {
                    Log.w(TAG, "searchSubtitles: rate limited (429)")
                    throw RateLimitedException(conn.getHeaderField("Retry-After")?.toIntOrNull())
                }
                if (code != 200) {
                    val error = readStream(conn.errorStream ?: conn.inputStream)
                    Log.e(TAG, "searchSubtitles: HTTP $code for query=$query: $error")
                    throw Exception("SubDL API error $code: $error")
                }
                val json = JSONObject(readStream(conn.inputStream))
                // Only treat explicit status:false as a failure (status may be absent).
                if (json.has("status") && json.optBoolean("status") == false) {
                    val msg = json.safeString("error", "SubDL search failed")
                    throw Exception(msg)
                }
                val subtitles = json.optJSONArray("subtitles") ?: JSONArray()
                val results = mutableListOf<SubtitleSearchResult>()
                Log.i(TAG, "searchSubtitles: ${subtitles.length()} subtitle groups for query=$query")
                for (i in 0 until subtitles.length()) {
                    val s = subtitles.getJSONObject(i)
                    // fps may be JSON null — safeString returns "" for JSON nulls
                    val fps = s.safeString("fps")
                    val parentLanguage = s.safeString("language")
                    val parentHi = s.optBoolean("hi", false)

                    val unpackFiles = s.optJSONArray("unpack_files")
                    if (unpackFiles != null && unpackFiles.length() > 0) {
                        // unpack=1: each file in unpack_files has its own direct URL
                        for (j in 0 until unpackFiles.length()) {
                            val f = unpackFiles.getJSONObject(j)
                            val rawUrl = f.safeString("url")
                            if (rawUrl.isBlank()) continue
                            val lang = f.safeString("language").ifBlank { parentLanguage }
                            val relName = f.safeString("release_name").ifBlank { f.safeString("name") }
                            results.add(
                                SubtitleSearchResult(
                                    downloadUrl = resolveDownloadUrl(rawUrl),
                                    language = lang,
                                    releaseName = relName,
                                    fps = fps,
                                    hearingImpaired = f.optBoolean("hi", parentHi),
                                )
                            )
                        }
                    } else {
                        // Fallback: use the zip URL on the parent subtitle object
                        val rawUrl = s.safeString("url")
                        if (rawUrl.isBlank()) continue
                        val relName = s.safeString("release_name").ifBlank { s.safeString("name") }
                        results.add(
                            SubtitleSearchResult(
                                downloadUrl = resolveDownloadUrl(rawUrl),
                                language = parentLanguage,
                                releaseName = relName,
                                fps = fps,
                                hearingImpaired = parentHi,
                            )
                        )
                    }
                }
                Log.i(TAG, "searchSubtitles: ${results.size} individual subtitle files parsed")
                results
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Download subtitle content from a SubDL download URL, returning raw bytes.
     * The URL returned by the API already contains the api_key query param when
     * unpack=1 is used — we only append it if not already present.
     */
    suspend fun downloadSubtitleContent(
        downloadUrl: String,
        apiKey: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            // Avoid duplicating api_key if it's already embedded in the URL
            val finalUrl = if (downloadUrl.contains("api_key=")) {
                downloadUrl
            } else {
                val sep = if (downloadUrl.contains('?')) '&' else '?'
                "$downloadUrl${sep}api_key=${java.net.URLEncoder.encode(apiKey, "UTF-8")}"
            }
            Log.i(TAG, "downloadSubtitleContent: $finalUrl")
            val conn = (URL(finalUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 30000
            }
            try {
                val code = conn.responseCode
                if (code != 200) {
                    val error = readStream(conn.errorStream ?: conn.inputStream)
                    Log.e(TAG, "download: HTTP $code: $error")
                    throw Exception("SubDL download error $code: $error")
                }
                readBytesCapped(conn.inputStream, MAX_SUBTITLE_BYTES)
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "download failed: ${it.message}") }
            .getOrNull()
    }

    private fun open(url: String, apiKey: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("User-Agent", "HzPlayer")
            connectTimeout = 10000
            readTimeout = 15000
        }

    private class RateLimitedException(val retryAfterSec: Int?) : Exception() {
        init { Log.w(TAG, "RateLimitedException retryAfter=$retryAfterSec") }
    }

    private fun readStream(stream: java.io.InputStream): String {
        return BufferedReader(InputStreamReader(stream)).readText()
    }

    /**
     * Read a response body into a [ByteArray], capped at [maxBytes] to avoid OOM on
     * a malformed/oversized subtitle link. Returns null if the cap is exceeded.
     */
    private fun readBytesCapped(stream: java.io.InputStream, maxBytes: Int): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = stream.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }
}
