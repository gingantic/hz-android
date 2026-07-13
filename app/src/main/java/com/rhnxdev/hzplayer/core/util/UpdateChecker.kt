package com.rhnxdev.hzplayer.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.rhnxdev.hzplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    // Fetch all releases (newest first). Works for both public and private repos.
    private const val RELEASES_API_URL =
        "https://api.github.com/repos/gingantic/hz-android/releases"

    data class UpdateInfo(
        val latestVersionName: String,
        val latestVersionCode: Int,
        val downloadUrl: String,
        val releaseNotes: String?
    )

    /**
     * Fetches the releases list and returns an [UpdateInfo] if a newer build is available,
     * or null if already up-to-date / an error occurred.
     *
     * Version comparison uses the "build.<N>" number embedded in the release name
     * (e.g. "HzPlayer v0.9.1-build.145+abc1234") vs [BuildConfig.VERSION_CODE].
     */
    suspend fun checkForUpdates(token: String = ""): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val activeToken = token.ifEmpty { BuildConfig.GITHUB_UPDATE_TOKEN }

            val jsonArray = fetchReleasesList(activeToken) ?: return@withContext null
            if (jsonArray.length() == 0) return@withContext null

            // GitHub returns releases newest-first; pick the first one.
            val latestRelease = jsonArray.getJSONObject(0)
            return@withContext parseRelease(latestRelease, activeToken)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun fetchReleasesList(token: String): JSONArray? {
        val url = URL(RELEASES_API_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "HzPlayer-UpdateChecker")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connectTimeout = 10_000
            readTimeout = 10_000
            if (token.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        return try {
            if (conn.responseCode != 200) return null
            val text = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            conn.disconnect()
            JSONArray(text)
        } catch (e: Exception) {
            conn.disconnect()
            null
        }
    }

    private fun parseRelease(release: JSONObject, token: String): UpdateInfo? {
        val releaseName = release.optString("name", "")     // e.g. "HzPlayer v0.9.1-build.145+abc1234"
        val body        = release.optString("body", "")

        // --- Find the APK asset ---
        val assetsArray = release.optJSONArray("assets") ?: return null
        var downloadUrl: String? = null
        var assetName: String = ""
        for (i in 0 until assetsArray.length()) {
            val asset = assetsArray.getJSONObject(i)
            val name = asset.optString("name", "")
            if (name.endsWith(".apk")) {
                assetName = name
                // For private repos: use the API URL with auth.
                // For public repos: use the plain browser URL.
                downloadUrl = if (token.isNotEmpty()) {
                    asset.optString("url", "")
                } else {
                    asset.optString("browser_download_url", "")
                }
                break
            }
        }
        if (downloadUrl.isNullOrEmpty()) return null

        // --- Extract build number ---
        // Try the release name first ("build.145"), then the APK filename as fallback.
        val buildRegex = Regex("""build\.(\d+)""")
        val latestVersionCode =
            buildRegex.find(releaseName)?.groupValues?.get(1)?.toIntOrNull()
                ?: buildRegex.find(assetName)?.groupValues?.get(1)?.toIntOrNull()
                ?: return null   // Can't determine version → skip

        // Clean display name: strip "HzPlayer" prefix
        val cleanVersionName = releaseName
            .replace("HzPlayer", "", ignoreCase = true)
            .trim()
            .trimStart('v', ' ')
            .let { "v$it" }

        if (latestVersionCode <= BuildConfig.VERSION_CODE) return null

        return UpdateInfo(
            latestVersionName = cleanVersionName,
            latestVersionCode = latestVersionCode,
            downloadUrl = downloadUrl,
            releaseNotes = body.takeIf { it.isNotBlank() }
        )
    }

    // -------------------------------------------------------------------------
    // Download
    // -------------------------------------------------------------------------

    /**
     * Download the APK file from GitHub and report progress back to the UI.
     * Manually follows redirects and removes the Authorization header when
     * redirected away from github.com (e.g. to S3) to prevent auth errors.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        destinationFile: File,
        token: String = "",
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (destinationFile.exists()) destinationFile.delete()

            val activeToken = token.ifEmpty { BuildConfig.GITHUB_UPDATE_TOKEN }
            var currentUrl = downloadUrl
            var conn: HttpURLConnection? = null
            var responseCode = 0
            var redirectCount = 0
            val maxRedirects = 5

            while (redirectCount < maxRedirects) {
                val url = URL(currentUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false   // Handle manually to strip auth on redirect
                    connectTimeout = 15_000
                    readTimeout   = 30_000
                    if (activeToken.isNotEmpty() && url.host.contains("github.com")) {
                        setRequestProperty("Authorization", "Bearer $activeToken")
                        setRequestProperty("Accept", "application/octet-stream")
                    }
                }

                responseCode = conn.responseCode
                val isRedirect = responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                        || responseCode == HttpURLConnection.HTTP_MOVED_PERM
                        || responseCode == 307
                        || responseCode == 308

                if (isRedirect) {
                    val newUrl = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (newUrl.isNullOrEmpty()) return@withContext false
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

    // -------------------------------------------------------------------------
    // Install
    // -------------------------------------------------------------------------

    /**
     * Launch an install intent for the downloaded APK via FileProvider.
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
