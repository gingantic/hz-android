package com.rhnxdev.hzplayer.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.rhnxdev.hzplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    // Public R2 bucket base URL — set this to your Cloudflare R2 public endpoint
    private const val R2_BASE_URL = "CHANGEME" // e.g. "https://pub-xxxxx.r2.dev"

    data class UpdateInfo(
        val latestVersionName: String,
        val latestVersionCode: Int,
        val downloadUrl: String,
        val releaseNotes: String?
    )

    suspend fun checkForUpdates(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val manifestUrl = "$R2_BASE_URL/latest.json"
            val json = fetchText(manifestUrl) ?: return@withContext null
            val obj = JSONObject(json)

            val versionName = obj.optString("versionName", "")
            val versionCode = obj.optInt("versionCode", 0)
            val downloadUrl = obj.optString("downloadUrl", "")
            val releaseNotes = obj.optString("releaseNotes", null)

            if (versionName.isBlank() || downloadUrl.isBlank()) return@withContext null
            if (versionCode <= BuildConfig.VERSION_CODE) return@withContext null

            UpdateInfo(
                latestVersionName = "v$versionName",
                latestVersionCode = versionCode,
                downloadUrl = downloadUrl,
                releaseNotes = releaseNotes?.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun fetchText(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return try {
            if (conn.responseCode != 200) return null
            val text = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            conn.disconnect()
            text
        } catch (e: Exception) {
            conn.disconnect()
            null
        }
    }

    suspend fun downloadApk(
        downloadUrl: String,
        destinationFile: File,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (destinationFile.exists()) destinationFile.delete()

            val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                conn.disconnect()
                return@withContext false
            }

            val totalBytes = conn.contentLength
            val inputStream = conn.inputStream
            val outputStream = FileOutputStream(destinationFile)
            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            var totalBytesRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                if (totalBytes > 0) {
                    onProgress(totalBytesRead.toFloat() / totalBytes.toFloat())
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()
            conn.disconnect()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
