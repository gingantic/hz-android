package com.rhnxdev.hzplayer.core.util

/**
 * Thread-safe generic LRU cache for directory listings.
 * Used by both local and remote file browser ViewModels.
 */
class DirectoryLruCache<T>(private val maxSize: Int = 50) {
    private val map = object : LinkedHashMap<String, List<T>>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<T>>): Boolean =
            size > maxSize
    }

    @Synchronized fun get(key: String): List<T>? = map[key]
    @Synchronized fun put(key: String, items: List<T>) { map[key] = items }
    @Synchronized fun remove(key: String) { map.remove(key) }
    @Synchronized fun clear() { map.clear() }
}
