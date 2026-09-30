package com.rhnxdev.hzplayer.domain.repository

import com.rhnxdev.hzplayer.domain.model.FolderItem
import kotlinx.coroutines.flow.Flow

interface FileRepository {
    fun listDirectory(path: String, showHidden: Boolean = false): Flow<List<FolderItem>>
    fun getStorageRoots(): Flow<List<FolderItem>>
    fun searchFiles(query: String): Flow<List<FolderItem>>

    /**
     * Copy a file or directory (recursively) into [destDirPath].
     * Name collisions are resolved with a "name (1)" suffix.
     * @param onProgress fraction in 0f..1f of bytes copied so far; called off the main thread.
     * @return the absolute path of the created copy.
     */
    suspend fun copyEntry(
        sourcePath: String,
        destDirPath: String,
        onProgress: (Float) -> Unit = {},
    ): Result<String>

    /**
     * Move a file or directory into [destDirPath]. Uses an atomic rename when
     * source and destination share a volume, otherwise falls back to copy + delete.
     * @param onProgress fraction in 0f..1f of bytes moved so far; a fast atomic
     *   rename reports a single 1f, a cross-volume copy reports incrementally.
     * @return the absolute path of the moved entry.
     */
    suspend fun moveEntry(
        sourcePath: String,
        destDirPath: String,
        onProgress: (Float) -> Unit = {},
    ): Result<String>

    /**
     * Permanently delete a file or directory (recursively).
     * @return the name of the deleted entry.
     */
    suspend fun deleteEntry(path: String): Result<String>

    /**
     * Create a new folder named [folderName] inside [parentPath].
     * @return the absolute path of the created directory.
     */
    suspend fun createDirectory(parentPath: String, folderName: String): Result<String>
}
