package com.rhnxdev.hzplayer.data.datasource.remote

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Minimal HTTP client for the OpenSubtitles REST API v1.
 *
 * Uses plain [HttpURLConnection] + [org.json] — no Retrofit/Moshi dependency.
 *
 * Docs: https://opensubtitles.stoplight.io/docs/opensubtitles-api
 */
@Singleton
class OpenSubtitlesApi @Inject constructor() {

    private companion object {
        const val BASE_URL = "https://api.opensubtitles.com/api/v1"
        const val DEFAULT_LANGUAGE = "en"
        const val MAX_SUBTITLE_BYTES = 5 * 1024 * 1024
    }

    data class SubtitleSearchResult(
        val id: String,
        val fileId: Long,
        val language: String,
        val releaseName: String,
        val downloadCount: Long,
        val hearingImpaired: Boolean,
        val fps: Double,
    )

    data class DownloadResult(
        val link: String,
        val fileName: String,
    )

    /**
     * Open an [HttpURLConnection] for [url] with the standard OpenSubtitles headers.
     */
    private fun open(url: String, apiKey: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Api-Key", apiKey)
            setRequestProperty("User-Agent", "HzPlayer v1.0")
            connectTimeout = 10000
            readTimeout = 10000
        }

    /**
     * Run [block], retrying once after a 429 with the server's `Retry-After` delay
     * (capped). OpenSubtitles enforces strict rate limits; without this, a few
     * searches trip a temporary ban with no backoff.
     */
    private inline fun <T> retryOnRateLimit(block: () -> T): T {
        return try {
            block()
        } catch (e: RateLimitedException) {
            val waitMs = (e.retryAfterSec?.let { it * 1000L } ?: 2000L).coerceAtMost(10_000L)
            // ponytail: fixed short sleep on 429; a real scheduler/scope would be
            // overkill for a one-shot user-initiated search.
            Thread.sleep(waitMs)
            block()
        }
    }

    private class RateLimitedException(val retryAfterSec: Int?) : Exception()

    /**
     * Search for subtitles by movie/show name.
     */
    suspend fun searchSubtitles(
        query: String,
        apiKey: String,
        language: String = DEFAULT_LANGUAGE,
    ): Result<List<SubtitleSearchResult>> = withContext(Dispatchers.IO) {
        runCatching {
            retryOnRateLimit {
                val url = URL("$BASE_URL/subtitles?query=${java.net.URLEncoder.encode(query, "UTF-8")}&languages=$language&order_by=download_count")
                val conn = open(url.toString(), apiKey).apply { requestMethod = "GET" }
                try {
                    val code = conn.responseCode
                    if (code == 429) throw RateLimitedException(conn.getHeaderField("Retry-After")?.toIntOrNull())
                    if (code != 200) {
                        val error = readStream(conn.errorStream ?: conn.inputStream)
                        throw Exception("OpenSubtitles API error $code: $error")
                    }
                    val body = readStream(conn.inputStream)
                    val json = JSONObject(body)
                    val dataArray = json.optJSONArray("data") ?: JSONArray()

                    val results = mutableListOf<SubtitleSearchResult>()
                    for (i in 0 until dataArray.length()) {
                        val item = dataArray.getJSONObject(i)
                        val attrs = item.optJSONObject("attributes") ?: continue
                        val filesArray = attrs.optJSONArray("files") ?: continue
                        val firstFile = if (filesArray.length() > 0) filesArray.getJSONObject(0) else continue

                        results.add(
                            SubtitleSearchResult(
                                id = item.optString("id", ""),
                                fileId = firstFile.optLong("file_id", 0),
                                language = attrs.optString("language", ""),
                                releaseName = attrs.optString("release", ""),
                                downloadCount = attrs.optLong("download_count", 0),
                                hearingImpaired = attrs.optBoolean("hearing_impaired", false),
                                fps = attrs.optDouble("fps", 0.0),
                            )
                        )
                    }
                    results
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

    /**
     * Get a temporary download URL for a subtitle file.
     */
    suspend fun getDownloadLink(
        fileId: Long,
        apiKey: String,
    ): Result<DownloadResult> = withContext(Dispatchers.IO) {
        runCatching {
            retryOnRateLimit {
                val url = URL("$BASE_URL/download")
                val conn = open(url.toString(), apiKey).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                try {
                    val code = conn.responseCode
                    if (code == 429) throw RateLimitedException(conn.getHeaderField("Retry-After")?.toIntOrNull())
                    if (code != 200) {
                        val error = readStream(conn.errorStream ?: conn.inputStream)
                        throw Exception("OpenSubtitles download error $code: $error")
                    }
                    val body = readStream(conn.inputStream)
                    val json = JSONObject(body)
                    DownloadResult(
                        link = json.optString("link", ""),
                        fileName = json.optString("file_name", "subtitle.srt"),
                    )
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

    /**
     * Download the subtitle content from a temporary URL and save to cache dir.
     */
    suspend fun downloadSubtitleContent(
        downloadLink: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(downloadLink).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 30000
            }
            try {
                readBytesCapped(conn.inputStream, MAX_SUBTITLE_BYTES)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
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
