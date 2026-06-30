package com.rhnxdev.hzplayer.data.repository

import android.content.ContentResolver
import android.provider.MediaStore
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.domain.repository.FileRepository
import com.rhnxdev.hzplayer.presentation.preview.PreviewMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileRepositoryImpl @Inject constructor(
    private val contentResolver: ContentResolver,
) : FileRepository {

    override fun listDirectory(path: String): Flow<List<FolderItem>> = flow {
        val file = File(path)
        if (!file.exists() || !file.isDirectory) {
            emit(emptyList())
            return@flow
        }

        val items = file.listFiles()?.map { f ->
            FolderItem(
                id = f.hashCode().toLong(),
                name = f.name,
                path = f.absolutePath,
                isDirectory = f.isDirectory,
                fileSize = if (f.isFile) f.length() else 0,
                childCount = if (f.isDirectory) f.listFiles()?.size ?: 0 else 0,
                dateModified = f.lastModified(),
            )
        }?.sortedByDescending { it.isDirectory }
            ?.sortedBy { it.name }

        if (items.isNullOrEmpty()) {
            // Fallback to preview data for demo
            emit(PreviewMedia.folders.filter { it.path.startsWith(path) && it.path != path })
        } else {
            emit(items)
        }
    }.flowOn(Dispatchers.IO)

    override fun getStorageRoots(): Flow<List<FolderItem>> = flow {
        val roots = mutableListOf<FolderItem>()

        // Internal storage
        val internalRoot = File("/storage/emulated/0")
        if (internalRoot.exists()) {
            roots.add(
                FolderItem(
                    id = 1,
                    name = "Internal Storage",
                    path = internalRoot.absolutePath,
                    isDirectory = true,
                    childCount = internalRoot.listFiles()?.size ?: 0,
                ),
            )
        }

        emit(roots)
    }.flowOn(Dispatchers.IO)

    override fun searchFiles(query: String): Flow<List<FolderItem>> = flow {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.TITLE,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE,
        )

        val selection = "${MediaStore.Files.FileColumns.TITLE} LIKE ?"
        val selectionArgs = arrayOf("%$query%")

        val cursor = contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
        )

        val results = mutableListOf<FolderItem>()
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.TITLE)
            val dataCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val sizeCol = it.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val dateCol = it.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val mimeCol = it.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)

            while (it.moveToNext()) {
                results.add(
                    FolderItem(
                        id = it.getLong(idCol),
                        name = it.getString(titleCol) ?: "Unknown",
                        path = it.getString(dataCol) ?: "",
                        isDirectory = false,
                        fileSize = if (sizeCol >= 0) it.getLong(sizeCol) else 0,
                        dateModified = if (dateCol >= 0) it.getLong(dateCol) else 0,
                        mimeType = if (mimeCol >= 0) it.getString(mimeCol) else null,
                    ),
                )
            }
        }

        if (results.isEmpty()) {
            val filtered = PreviewMedia.folders.filter {
                it.name.contains(query, ignoreCase = true)
            }
            emit(filtered)
        } else {
            emit(results)
        }
    }.flowOn(Dispatchers.IO)
}
