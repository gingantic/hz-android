package com.rhnxdev.hzplayer.browser.adblock

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object AdBlockUpdater {

    sealed class UpdateResult {
        object Success : UpdateResult()
        data class Error(val message: String) : UpdateResult()
    }

    /**
     * Downloads enabled filter lists asynchronously in the background.
     */
    suspend fun updateLists(
        context: Context,
        enabledListIds: Set<String>,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
    ): UpdateResult = withContext(Dispatchers.IO) {
        val listsToUpdate = AdBlockListManager.BUILTIN_LISTS.filter { enabledListIds.contains(it.id) }
        val total = listsToUpdate.size
        if (total == 0) return@withContext UpdateResult.Success

        var completed = 0
        var errorCount = 0

        listsToUpdate.forEach { descriptor ->
            val success = downloadList(context, descriptor)
            completed++
            if (!success) errorCount++
            onProgress?.invoke(completed, total)
        }

        if (errorCount == total) {
            UpdateResult.Error("Failed to update filter lists (network unreachable)")
        } else {
            UpdateResult.Success
        }
    }

    private fun downloadList(context: Context, descriptor: FilterListDescriptor): Boolean {
        return try {
            val url = URL(descriptor.rawUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "HzPlayer-Browser/1.0 AdBlocker")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                if (text.isNotBlank()) {
                    val file = AdBlockListManager.getListFile(context, descriptor.id)
                    file.writeText(text)
                    true
                } else false
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
