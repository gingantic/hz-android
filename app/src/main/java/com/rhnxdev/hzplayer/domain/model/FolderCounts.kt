package com.rhnxdev.hzplayer.domain.model

/**
 * Breakdown of a folder's immediate children, used for the per-folder badge.
 * All values default to -1 (uncounted) so the UI can hide the badge until known.
 */
data class FolderCounts(
    val folders: Int = -1,
    val files: Int = -1,
    val media: Int = -1,
)
