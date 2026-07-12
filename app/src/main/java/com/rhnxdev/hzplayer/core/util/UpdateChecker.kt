package com.rhnxdev.hzplayer.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.rhnxdev.hzplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val RELEASES_API_URL = "https://api.github.com/repos/gingantic/hz-android/releases"

    data class UpdateInfo(
        val latestVersionName: String,
        val latestVersionCode: Int,
        val downloadUrl: String,
        val releaseNotes: String?
    )

    /**
     * Checks if a new release exists on GitHub.
     * Compares the version code parsed from the release title with local BuildConfig.VERSION_CODE.
     */
    /**
     * Checks if a new release exists on GitHub.
     * Compares the version code parsed from the release title with local BuildConfig.VERSION_CODE.
     */
    suspend fun checkForUpdates(token: String = ""): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val activeToken = token.ifEmpty { BuildConfig.GITHUB_UPDATE_TOKEN }
            val url = URL(RELEASES_API_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "HzPlayer-UpdateChecker")
                connectTimeout = 10000
                readTimeout = 10000
                if (activeToken.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $activeToken")
                }
            }
            
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                return@withContext null
            }
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val responseText = reader.readText()
            reader.close()
            conn.disconnect()

            val jsonArray = JSONArray(responseText)
            if (jsonArray.length() == 0) return@withContext null

            // The first item is the most recent release
            val latestRelease = jsonArray.getJSONObject(0)
            val releaseName = latestRelease.optString("name", "") // e.g. "HzPlayer v1.0.0-rc1-build.145+hash"
            val body = latestRelease.optString("body", "")
            
            // Extract the APK download URL from release assets
            val assetsArray = latestRelease.optJSONArray("assets") ?: return@withContext null
            var downloadUrl: String? = null
            for (i in 0 until assetsArray.length()) {
                val asset = assetsArray.getJSONObject(i)
                val assetName = asset.optString("name", "")
                if (assetName.endsWith(".apk")) {
                    // For private repositories, download via the API URL instead of the public browser_download_url
                    downloadUrl = if (activeToken.isNotEmpty()) {
                        asset.optString("url", "")
                    } else {
                        asset.optString("browser_download_url", "")
                    }
                    break
                }
            }

            if (downloadUrl.isNullOrEmpty()) return@withContext null

            // Extract build number from release name (e.g. "build.145" -> 145)
            val buildRegex = Regex("""build\.(\d+)""")
            val matchResult = buildRegex.find(releaseName)
            val latestVersionCode = matchResult?.groupValues?.get(1)?.toIntOrNull() ?: 1

            // Format release name nicely (remove "HzPlayer" prefix)
            val cleanVersionName = releaseName.replace("HzPlayer", "").trim()

            if (latestVersionCode > BuildConfig.VERSION_CODE) {
                UpdateInfo(
                    latestVersionName = cleanVersionName,
                    latestVersionCode = latestVersionCode,
                    downloadUrl = downloadUrl,
                    releaseNotes = body
                )
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Download the APK file from GitHub and report progress back to the UI.
     * Manually follows redirects and removes the Authorization header to prevent S3 auth errors.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        destinationFile: File,
        token: String = "",
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            
            val activeToken = token.ifEmpty { BuildConfig.GITHUB_UPDATE_TOKEN }
            var currentUrl = downloadUrl
            var conn: HttpURLConnection? = null
            var responseCode = 0
            var redirectCount = 0
            val maxRedirects = 5

            while (redirectCount < maxRedirects) {
                val url = URL(currentUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false // Handle redirect manually to strip Auth header for S3
                    connectTimeout = 15000
                    readTimeout = 30000
                    if (activeToken.isNotEmpty()) {
                        // Only send authentication to GitHub hosts
                        if (url.host.contains("github.com")) {
                            setRequestProperty("Authorization", "Bearer $activeToken")
                            setRequestProperty("Accept", "application/octet-stream")
                        }
                    }
                }
                
                responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || 
                    responseCode == HttpURLConnection.HTTP_MOVED_PERM || 
                    responseCode == 307 || responseCode == 308) {
                    val newUrl = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (newUrl.isNullOrEmpty()) {
                        return@withContext false
                    }
                    currentUrl = newUrl
                    redirectCount++
                } else {
                    break
                }
            }
            
            if (conn == null || responseCode != 200) {
                conn?.disconnect()
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

    /**
     * Launch intent to prompt user to install the downloaded APK.
     */
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
