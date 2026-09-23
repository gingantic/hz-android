package com.rhnxdev.hzplayer.data.repository

import com.rhnxdev.hzplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Cache-first listing flow shared by the video and audio repositories:
 * in-memory cache → Room → debug preview → MediaStore scan.
 *
 * Each source emits as soon as it has data, so the UI never sits on a blank
 * shimmer while MediaStore is walked. A scan failure is swallowed once something
 * has already been emitted, but rethrown when the flow would otherwise complete
 * empty — a silent empty list would read as "no media" rather than "scan failed".
 *
 * @param memoryCache read lazily, not captured: the flow is cold and re-collected
 *   on every tab re-entry, so it must see the cache as of collection time.
 * @param preview emitted when Room is empty; debug builds only.
 * @param sort applied to every emission — video sorts by the user's preference,
 *   audio has a single fixed order.
 * @param cacheIn writes a freshly loaded list back to the in-memory cache.
 */
internal fun <E, T> cachedScanFlow(
    forceRefresh: Boolean,
    memoryCache: () -> List<T>?,
    preview: List<T>,
    readRoom: () -> Flow<List<E>>,
    toItem: (E) -> T,
    scan: () -> Flow<List<E>>,
    replaceRoom: suspend (List<E>) -> Unit,
    cacheIn: (List<T>) -> Unit,
    sort: (List<T>) -> List<T> = { it },
): Flow<List<T>> = flow {
    // Instant path: serve the in-memory cache unless a forced refresh was requested.
    val memCache = memoryCache()
    if (memCache != null && !forceRefresh) {
        emit(sort(memCache))
        return@flow
    }

    var emitted = false

    // Phase 1: Room cache — emitted immediately when populated.
    val cached = readRoom().first()
    if (cached.isNotEmpty()) {
        val list = cached.map(toItem)
        cacheIn(list)
        emit(sort(list))
        emitted = true
    } else if (BuildConfig.DEBUG) {
        // Phase 2: no cache — emit preview data so the UI never shows a blank
        // shimmer. Debug only: release builds stay empty until the scan completes.
        emit(sort(preview))
        emitted = true
    }

    try {
        val scanned = scan().first()
        if (scanned.isNotEmpty()) {
            replaceRoom(scanned)
            val list = scanned.map(toItem)
            cacheIn(list)
            emit(sort(list))
        } else if (!emitted) {
            emit(emptyList())
        }
    } catch (e: Exception) {
        if (!emitted) throw e
    }
}.flowOn(Dispatchers.IO)
