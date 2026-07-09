package com.rhnxdev.hzplayer.domain.model

data class RemoteFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val fileSize: Long = 0,
    val childCount: Int = -1,
    val subfolderCount: Int = -1,
    val fileCount: Int = -1,
    val mediaCount: Int = -1,
    val dateModified: Long = 0,
    val mimeType: String? = null,
)
