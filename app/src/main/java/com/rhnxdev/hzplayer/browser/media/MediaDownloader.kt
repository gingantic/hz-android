package com.rhnxdev.hzplayer.browser.media

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import java.net.URLDecoder

object MediaDownloader {

    fun downloadMedia(context: Context, item: DetectedMediaItem) {
        try {
            val downloadUrl = item.displayUrl
            val uri = Uri.parse(downloadUrl)
            val request = DownloadManager.Request(uri)

            // Derive safe clean filename
            val rawName = item.title.ifBlank { "media_download" }
            val cleanName = sanitizeFilename(rawName)
            val ext = item.extension.ifBlank { "mp4" }
            val fullFileName = if (cleanName.endsWith(".$ext", ignoreCase = true)) {
                cleanName
            } else {
                "$cleanName.$ext"
            }

            request.setTitle(fullFileName)
            request.setDescription("Downloading via HzPlayer Media Grabber")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fullFileName)

            // Attach request HTTP headers (User-Agent, Referer, Cookie)
            item.headers.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank()) {
                    request.addRequestHeader(key, value)
                }
            }

            // Fallback default User-Agent if none present
            if (!item.headers.containsKey("User-Agent")) {
                request.addRequestHeader(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
                )
            }

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (manager != null) {
                manager.enqueue(request)
                Toast.makeText(context, "Started download: $fullFileName", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Download service unavailable", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Download failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sanitizeFilename(name: String): String {
        val decoded = try { URLDecoder.decode(name, "UTF-8") } catch (_: Exception) { name }
        return decoded.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80).ifBlank { "downloaded_media" }
    }
}
