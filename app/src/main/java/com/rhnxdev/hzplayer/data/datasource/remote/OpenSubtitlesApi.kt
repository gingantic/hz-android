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
     * Search for subtitles by movie/show name.
     */
    fun searchSubtitles(
        query: String,
        apiKey: String,
        language: String = DEFAULT_LANGUAGE,
    ): Result<List<SubtitleSearchResult>> {
        return try {
            val url = URL("$BASE_URL/subtitles?query=${java.net.URLEncoder.encode(query, "UTF-8")}&languages=$language&order_by=download_count")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Api-Key", apiKey)
            conn.setRequestProperty("User-Agent", "HzPlayer v1.0")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val code = conn.responseCode
            if (code != 200) {
                val error = readStream(conn.errorStream ?: conn.inputStream)
                return Result.failure(Exception("OpenSubtitles API error $code: $error"))
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

            Result.success(results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get a temporary download URL for a subtitle file.
     */
    fun getDownloadLink(
        fileId: Long,
        apiKey: String,
    ): Result<DownloadResult> {
        return try {
            val url = URL("$BASE_URL/download")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Api-Key", apiKey)
            conn.setRequestProperty("User-Agent", "HzPlayer v1.0")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val requestBody = JSONObject().apply {
                put("file_id", fileId)
            }
            OutputStreamWriter(conn.outputStream).use { it.write(requestBody.toString()) }

            val code = conn.responseCode
            if (code != 200) {
                val error = readStream(conn.errorStream ?: conn.inputStream)
                return Result.failure(Exception("OpenSubtitles download error $code: $error"))
            }

            val body = readStream(conn.inputStream)
            val json = JSONObject(body)
            Result.success(
                DownloadResult(
                    link = json.optString("link", ""),
                    fileName = json.optString("file_name", "subtitle.srt"),
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Download the subtitle content from a temporary URL and save to cache dir.
     */
    fun downloadSubtitleContent(
        downloadLink: String,
    ): ByteArray? {
        return try {
            val url = URL(downloadLink)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.inputStream.readBytes()
        } catch (e: Exception) {
            null
        }
    }

    private fun readStream(stream: java.io.InputStream): String {
        return BufferedReader(InputStreamReader(stream)).readText()
    }
}
