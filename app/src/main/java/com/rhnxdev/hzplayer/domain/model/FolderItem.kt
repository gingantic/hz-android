package com.rhnxdev.hzplayer.domain.model

data class FolderItem(
    val id: Long,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val fileSize: Long = 0,
    val freeSpace: Long = 0,
    val totalSpace: Long = 0,
    val childCount: Int = -1,
    val subfolderCount: Int = -1,
    val fileCount: Int = -1,
    val mediaCount: Int = -1,
    val dateModified: Long = 0,
    val mimeType: String? = null,
    val durationMs: Long = 0,
    val playbackPositionMs: Long = 0,
    val resolution: String? = null,
    /** Seconds since Unix epoch (not milliseconds!). */
    val dateAdded: Long = 0,
)
