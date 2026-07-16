package com.rhnxdev.hzplayer.data.datasource.remote

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
 * Minimal HTTP client for the SubDL Search & Download API (https://subdl.com/api-doc).
 *
 * Uses plain [HttpURLConnection] + [org.json] — no Retrofit/Moshi dependency.
 * Search returns subtitles with a direct download URL (dl.subdl.com), so there
 * is no separate token-exchange step like OpenSubtitles' `/download` endpoint.
 */
@Singleton
class SubdlApi @Inject constructor() {

    private companion object {
        const val BASE_URL = "https://api.subdl.com/api/v1"
        const val DOWNLOAD_BASE = "https://dl.subdl.com"
        const val MAX_SUBTITLE_BYTES = 5 * 1024 * 1024
    }

    data class SubtitleSearchResult(
        val downloadUrl: String,
        val language: String,
        val releaseName: String,
        val fps: String,
        val hearingImpaired: Boolean,
    )

    /**
     * Search SubDL for subtitles by film/TV name. [apiKey] is the user's SubDL key
     * passed as the `api_key` query param. [query] is matched against film_name.
     */
    suspend fun searchSubtitles(
        query: String,
        apiKey: String,
        language: String? = null,
        type: String = "movie",
        season: Int? = null,
        episode: Int? = null,
    ): Result<List<SubtitleSearchResult>> = withContext(Dispatchers.IO) {
        runCatching {
            // SubDL expects "tv" (not "series") and comma-separated UPPERCASE codes.
            val apiType = if (type == "series") "tv" else type
            val apiLanguage = language?.uppercase()?.takeIf { it != "ALL" }
            val url = StringBuilder("$BASE_URL/subtitles?")
                .append("api_key=").append(java.net.URLEncoder.encode(apiKey, "UTF-8"))
                .append("&film_name=").append(java.net.URLEncoder.encode(query, "UTF-8"))
                .append("&type=").append(java.net.URLEncoder.encode(apiType, "UTF-8"))
            if (!apiLanguage.isNullOrBlank()) {
                url.append("&languages=").append(java.net.URLEncoder.encode(apiLanguage, "UTF-8"))
            }
            if (apiType == "tv") {
                if (season != null) url.append("&season_number=").append(season)
                if (episode != null) url.append("&episode_number=").append(episode)
            }
            url.append("&subs_per_page=30")
                .append("&hi=1")
                .append("&unpack=1")
            val conn = open(url.toString(), apiKey).apply { requestMethod = "GET" }
            try {
                val code = conn.responseCode
                if (code == 429) throw RateLimitedException(conn.getHeaderField("Retry-After")?.toIntOrNull())
                if (code != 200) {
                    val error = readStream(conn.errorStream ?: conn.inputStream)
                    throw Exception("SubDL API error $code: $error")
                }
                val body = readStream(conn.inputStream)
                val json = JSONObject(body)
                if (json.optBoolean("status") != true) {
                    val msg = json.optString("error", "SubDL search failed")
                    throw Exception(msg)
                }
                val subtitles = json.optJSONArray("subtitles") ?: JSONArray()
                val results = mutableListOf<SubtitleSearchResult>()
                for (i in 0 until subtitles.length()) {
                    val s = subtitles.getJSONObject(i)
                    // Prefer unpacked raw files (direct .srt), else the packed zip URL.
                    val unpackFiles = s.optJSONArray("unpack_files")
                    if (unpackFiles != null && unpackFiles.length() > 0) {
                        for (j in 0 until unpackFiles.length()) {
                            val f = unpackFiles.getJSONObject(j)
                            val url = f.optString("url", "")
                            if (url.isBlank()) continue
                            results.add(
                                SubtitleSearchResult(
                                    downloadUrl = DOWNLOAD_BASE + url,
                                    language = f.optString("language", s.optString("language", "")),
                                    releaseName = f.optString("release_name", f.optString("name", "")),
                                    fps = s.optString("fps", ""),
                                    hearingImpaired = f.optBoolean("hi", false),
                                )
                            )
                        }
                    } else {
                        val url = s.optString("url", "")
                        if (url.isBlank()) continue
                        results.add(
                            SubtitleSearchResult(
                                downloadUrl = DOWNLOAD_BASE + url,
                                language = s.optString("language", ""),
                                releaseName = s.optString("release_name", s.optString("name", "")),
                                fps = s.optString("fps", ""),
                                hearingImpaired = s.optBoolean("hi", false),
                            )
                        )
                    }
                }
                results
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Download subtitle content from a SubDL download URL, returning raw bytes.
     * Paid keys pass the api_key so the download counts against the account quota.
     */
    suspend fun downloadSubtitleContent(
        downloadUrl: String,
        apiKey: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val sep = if (downloadUrl.contains('?')) '&' else '?'
            val url = "$downloadUrl${sep}api_key=${java.net.URLEncoder.encode(apiKey, "UTF-8")}"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 30000
            }
            try {
                val code = conn.responseCode
                if (code != 200) {
                    val error = readStream(conn.errorStream ?: conn.inputStream)
                    throw Exception("SubDL download error $code: $error")
                }
                readBytesCapped(conn.inputStream, MAX_SUBTITLE_BYTES)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun open(url: String, apiKey: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "HzPlayer")
            connectTimeout = 10000
            readTimeout = 10000
        }

    private class RateLimitedException(val retryAfterSec: Int?) : Exception()

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
