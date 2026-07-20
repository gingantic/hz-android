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

        /** SubDL returns at most 30 subtitle groups per page. */
        const val SUBS_PER_PAGE = 30

        /** Safety cap on pagination so a runaway totalPages can't loop forever
         *  (30 groups/page × 10 pages = up to 300 groups, far beyond any title). */
        const val MAX_PAGES = 10

        /** File formats treated as real subtitles. SubDL packs often bundle spam
         *  files inside `unpack_files` (e.g. "LINK.FILM_….txt" ad/link files, .nfo,
         *  images). Those return HTTP 404 on download and aren't usable, so they are
         *  filtered out before being shown to the user. */
        val SUBTITLE_FORMATS = setOf(
            "srt", "ass", "ssa", "sub", "idx", "vtt", "webvtt", "smi", "sami",
            "stl", "ttml", "dfxp", "sup", "usf", "jss", "aqt", "mpl2", "pjs",
            "rt", "scc", "cap",
        )

        /** True when an unpacked file is a real subtitle (by its `format` field,
         *  falling back to the file-name extension when format is absent). */
        fun isSubtitleFile(format: String, name: String): Boolean {
            val fmt = format.lowercase().trim()
            if (fmt.isNotEmpty()) return fmt in SUBTITLE_FORMATS
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in SUBTITLE_FORMATS
        }

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
     *
     * SubDL paginates results (max [SUBS_PER_PAGE] groups per page) and reports
     * `totalPages`/`currentPage` in the body. Popular titles easily exceed one
     * page — e.g. a new blockbuster can have 90+ subtitle groups across many
     * releases/languages. We fetch EVERY page and merge them so the app shows the
     * same full list as the SubDL website instead of only the first 30 groups.
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

            // Build the base query once (everything except the page number).
            val base = StringBuilder("$BASE_URL/subtitles/search?")
            when {
                !imdbId.isNullOrBlank() -> {
                    base.append("imdb_id=").append(java.net.URLEncoder.encode(imdbId, "UTF-8"))
                }
                tmdbId != null -> {
                    base.append("tmdb_id=").append(tmdbId)
                    base.append("&type=").append(java.net.URLEncoder.encode(apiType, "UTF-8"))
                }
                else -> {
                    base.append("film_name=").append(java.net.URLEncoder.encode(query, "UTF-8"))
                    base.append("&type=").append(java.net.URLEncoder.encode(apiType, "UTF-8"))
                }
            }
            if (!apiLanguage.isNullOrBlank()) {
                base.append("&languages=").append(java.net.URLEncoder.encode(apiLanguage, "UTF-8"))
            }
            if (apiType == "tv") {
                if (season != null) base.append("&season_number=").append(season)
                if (episode != null) base.append("&episode_number=").append(episode)
            }
            base.append("&subs_per_page=").append(SUBS_PER_PAGE).append("&unpack=1")

            val results = mutableListOf<SubtitleSearchResult>()
            var page = 1
            var totalPages = 1
            while (true) {
                val url = "$base&page=$page"
                Log.i(TAG, "searchSubtitles URL: $url")
                val conn = open(url, apiKey).apply { requestMethod = "GET" }
                val json: JSONObject
                try {
                    val code = conn.responseCode
                    if (code == 429) {
                        Log.w(TAG, "searchSubtitles: rate limited (429) on page $page")
                        // First page failure → surface the error. Later pages → keep
                        // whatever we already collected rather than failing the search.
                        if (page == 1) throw RateLimitedException(conn.getHeaderField("Retry-After")?.toIntOrNull())
                        Log.w(TAG, "searchSubtitles: stopping pagination early (429), keeping ${results.size} results")
                        break
                    }
                    if (code != 200) {
                        val error = readStream(conn.errorStream ?: conn.inputStream)
                        Log.e(TAG, "searchSubtitles: HTTP $code for query=$query page=$page: $error")
                        if (page == 1) throw Exception("SubDL API error $code: $error")
                        break
                    }
                    json = JSONObject(readStream(conn.inputStream))
                } finally {
                    conn.disconnect()
                }
                // Only treat explicit status:false as a failure (status may be absent).
                if (json.has("status") && json.optBoolean("status") == false) {
                    val msg = json.safeString("error", "SubDL search failed")
                    if (page == 1) throw Exception(msg)
                    break
                }
                totalPages = json.optInt("totalPages", 1).coerceAtLeast(1)
                val subtitles = json.optJSONArray("subtitles") ?: JSONArray()
                Log.i(TAG, "searchSubtitles: page $page/$totalPages — ${subtitles.length()} subtitle groups")
                parseSubtitleGroups(subtitles, results)

                if (page >= totalPages || page >= MAX_PAGES) break
                page++
            }
            Log.i(TAG, "searchSubtitles: ${results.size} individual subtitle files parsed")
            results
        }
    }

    /**
     * Parse one page's `subtitles` array into individual downloadable files and
     * append them to [out]. With `unpack=1` each group exposes `unpack_files`
     * (per-file direct URLs); otherwise we fall back to the group's zip URL.
     */
    private fun parseSubtitleGroups(subtitles: JSONArray, out: MutableList<SubtitleSearchResult>) {
        for (i in 0 until subtitles.length()) {
            val s = subtitles.getJSONObject(i)
            // fps may be JSON null — safeString returns "" for JSON nulls
            val fps = s.safeString("fps")
            val parentLanguage = s.safeString("language")
            val parentHi = s.optBoolean("hi", false)

            val unpackFiles = s.optJSONArray("unpack_files")
            if (unpackFiles != null && unpackFiles.length() > 0) {
                // The per-file direct URLs (dl.subdl.com/subtitle/{page}/{fileId})
                // returned by unpack=1 currently respond HTTP 404, but the parent
                // pack ZIP URL works reliably. Download the pack and let the
                // repository extract the exact file the user picked (matched by
                // name), falling back to the first subtitle in the pack.
                val zipUrl = s.safeString("url")
                for (j in 0 until unpackFiles.length()) {
                    val f = unpackFiles.getJSONObject(j)
                    // Skip bundled spam/non-subtitle files (.txt ads, .nfo, images…)
                    // that can't be rendered.
                    if (!isSubtitleFile(f.safeString("format"), f.safeString("name"))) continue
                    val fileName = f.safeString("name")
                    // Prefer the working pack ZIP; fall back to the per-file URL only
                    // if the pack URL is somehow absent.
                    val rawUrl = zipUrl.ifBlank { f.safeString("url") }
                    if (rawUrl.isBlank()) continue
                    val lang = f.safeString("language").ifBlank { parentLanguage }
                    out.add(
                        SubtitleSearchResult(
                            downloadUrl = resolveDownloadUrl(rawUrl),
                            language = lang,
                            // Carry the real file name (with extension) so the download
                            // can extract & save the exact file the user selected.
                            releaseName = fileName.ifBlank { f.safeString("release_name") },
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
                out.add(
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
