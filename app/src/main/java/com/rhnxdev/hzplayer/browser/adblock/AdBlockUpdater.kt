package com.rhnxdev.hzplayer.browser.adblock

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object AdBlockUpdater {

    /** Minimum age of the downloaded lists before an automatic refresh is due. */
    private val REFRESH_INTERVAL_MS = TimeUnit.HOURS.toMillis(24)

    /** Same, on a metered link — the lists are several MB, so wait longer. */
    private val METERED_REFRESH_INTERVAL_MS = TimeUnit.DAYS.toMillis(7)

    sealed class UpdateResult {
        /**
         * At least one list was reachable.
         * @param changed — true when any list had new content (the engine must be rebuilt)
         */
        data class Success(val changed: Boolean) : UpdateResult()

        /** Every enabled list failed to download — almost always no network. */
        object Error : UpdateResult()
    }

    private enum class ListOutcome { UPDATED, UNCHANGED, FAILED }

    /**
     * True when the automatic startup refresh should run.
     *
     * Lists that were never downloaded (first run, or a list enabled later) are
     * always due, because the bundled copy is only a stub. Otherwise the refresh
     * waits for [REFRESH_INTERVAL_MS], or the longer metered interval.
     *
     * @param lastUpdateTimestamp — epoch ms of the last successful refresh, 0 if never
     * @param metered — true when the active network is metered
     */
    fun isRefreshDue(
        context: Context,
        enabledListIds: Set<String>,
        lastUpdateTimestamp: Long,
        metered: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val lists = AdBlockListManager.BUILTIN_LISTS.filter { enabledListIds.contains(it.id) }
        if (lists.isEmpty()) return false
        val neverDownloaded = lastUpdateTimestamp <= 0L ||
            lists.any { !AdBlockListManager.getListFile(context, it.id).exists() }
        if (neverDownloaded) return true
        val interval = if (metered) METERED_REFRESH_INTERVAL_MS else REFRESH_INTERVAL_MS
        return now - lastUpdateTimestamp >= interval
    }

    /**
     * Downloads enabled filter lists in parallel, so one slow or dead host does
     * not delay the others. Lists the server reports as unchanged are not
     * re-downloaded.
     */
    suspend fun updateLists(
        context: Context,
        enabledListIds: Set<String>,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
    ): UpdateResult = withContext(Dispatchers.IO) {
        val listsToUpdate = AdBlockListManager.BUILTIN_LISTS.filter { enabledListIds.contains(it.id) }
        val total = listsToUpdate.size
        if (total == 0) return@withContext UpdateResult.Success(changed = false)

        val completed = AtomicInteger(0)
        val outcomes = coroutineScope {
            listsToUpdate.map { descriptor ->
                async {
                    downloadList(context, descriptor).also {
                        onProgress?.invoke(completed.incrementAndGet(), total)
                    }
                }
            }.awaitAll()
        }

        if (outcomes.all { it == ListOutcome.FAILED }) {
            UpdateResult.Error
        } else {
            UpdateResult.Success(changed = outcomes.any { it == ListOutcome.UPDATED })
        }
    }

    private fun etagFile(listFile: File): File =
        File(listFile.parentFile, listFile.nameWithoutExtension + ".etag")

    private fun downloadList(context: Context, descriptor: FilterListDescriptor): ListOutcome {
        val file = AdBlockListManager.getListFile(context, descriptor.id)
        val etagFile = etagFile(file)
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(descriptor.rawUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "HzPlayer-Browser/1.0 AdBlocker")

            // Conditional GET — only when we hold a downloaded copy the validator belongs to.
            val etag = if (file.exists() && etagFile.exists()) {
                runCatching { etagFile.readText().trim() }.getOrDefault("")
            } else ""
            if (etag.isNotEmpty()) connection.setRequestProperty("If-None-Match", etag)

            when (connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> ListOutcome.UNCHANGED
                HttpURLConnection.HTTP_OK -> {
                    val text = connection.inputStream.bufferedReader().use { it.readText() }
                    // A captive portal answers 200 with an HTML login page — never store that.
                    if (!looksLikeFilterList(text)) return ListOutcome.FAILED
                    writeAtomically(file, text)
                    val newEtag = connection.getHeaderField("ETag")
                    if (newEtag.isNullOrBlank()) etagFile.delete() else etagFile.writeText(newEtag)
                    ListOutcome.UPDATED
                }
                else -> ListOutcome.FAILED
            }
        } catch (_: Exception) {
            ListOutcome.FAILED
        } finally {
            connection?.disconnect()
        }
    }

    private fun looksLikeFilterList(text: String): Boolean =
        text.isNotBlank() && !text.trimStart().startsWith("<")

    /** Write via a temp file so a kill mid-write can't leave a truncated list behind. */
    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }
}
