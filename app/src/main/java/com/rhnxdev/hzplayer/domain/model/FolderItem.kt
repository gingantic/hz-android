package com.rhnxdev.hzplayer.domain.model

data class FolderItem(
    val id: Long,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val fileSize: Long = 0,
    val freeSpace: Long = 0,
    val totalSpace: Long = 0,
    val childCount: Int = 0,
    val dateModified: Long = 0,
    val mimeType: String? = null,
)
