package com.rhnxdev.hzplayer.domain.repository

import com.rhnxdev.hzplayer.domain.model.FolderItem
import kotlinx.coroutines.flow.Flow

interface FileRepository {
    fun listDirectory(path: String, showHidden: Boolean = false): Flow<List<FolderItem>>
    fun getStorageRoots(): Flow<List<FolderItem>>
    fun searchFiles(query: String): Flow<List<FolderItem>>
}
