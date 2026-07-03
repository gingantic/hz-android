package com.rhnxdev.hzplayer.data.repository

import android.content.ContentResolver
import android.provider.MediaStore
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.domain.repository.FileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Map of file extensions to MIME types for the file browser. */
private val MIME_MAP = mapOf(
    "mp4" to "video/mp4", "mkv" to "video/x-matroska", "avi" to "video/avi",
    "mov" to "video/quicktime", "wmv" to "video/x-ms-wmv", "flv" to "video/x-flv",
    "webm" to "video/webm", "3gp" to "video/3gpp", "m4v" to "video/mp4",
    "mpg" to "video/mpeg", "mpeg" to "video/mpeg", "ts" to "video/mp2t",
    "mp3" to "audio/mpeg", "flac" to "audio/flac", "wav" to "audio/wav",
    "ogg" to "audio/ogg", "aac" to "audio/aac", "wma" to "audio/x-ms-wma",
    "m4a" to "audio/mp4", "opus" to "audio/opus", "ape" to "audio/x-ape",
    "aiff" to "audio/aiff", "dsf" to "audio/dsf", "dff" to "audio/dff",
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
    "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
    "pdf" to "application/pdf", "zip" to "application/zip", "rar" to "application/vnd.rar",
    "7z" to "application/x-7z-compressed", "gz" to "application/gzip",
    "txt" to "text/plain",
    "srt" to "text/plain", "sub" to "text/plain",
    "ass" to "text/plain", "ssa" to "text/plain", "vtt" to "text/plain",
    "lrc" to "text/plain", "nfo" to "text/plain",
    "iso" to "application/x-iso9660-image",
)

private fun guessMimeType(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return MIME_MAP[ext]
}

@Singleton
class FileRepositoryImpl @Inject constructor(
    private val contentResolver: ContentResolver,
) : FileRepository {

    override fun listDirectory(path: String): Flow<List<FolderItem>> = flow {
        val file = File(path.trimEnd('/'))
        if (!file.exists() || !file.isDirectory) {
            emit(emptyList())
            return@flow
        }

        val files = file.listFiles() ?: run {
            emit(emptyList())
            return@flow
        }

        emit(
            files.map { f ->
                FolderItem(
                    id = f.hashCode().toLong(),
                    name = f.name,
                    path = f.absolutePath,
                    isDirectory = f.isDirectory,
                    fileSize = if (f.isFile) f.length() else 0,
                    childCount = if (f.isDirectory) f.listFiles()?.size ?: 0 else 0,
                    dateModified = f.lastModified(),
                    mimeType = if (f.isFile) guessMimeType(f.name) else null,
                )
            }.sortedWith(
                compareByDescending<FolderItem> { it.isDirectory }
                    .thenBy { it.name.lowercase() }
            )
        )
    }.flowOn(Dispatchers.IO)

    override fun getStorageRoots(): Flow<List<FolderItem>> = flow {
        val roots = mutableListOf<FolderItem>()

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
            emit(emptyList())
        } else {
            emit(results)
        }
    }.flowOn(Dispatchers.IO)
}
