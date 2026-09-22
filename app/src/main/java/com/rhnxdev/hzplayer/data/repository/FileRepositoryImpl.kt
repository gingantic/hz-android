package com.rhnxdev.hzplayer.data.repository

import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.util.classifyVolume
import com.rhnxdev.hzplayer.core.util.fallbackVolumeKind
import com.rhnxdev.hzplayer.core.util.fallbackVolumeLabel
import com.rhnxdev.hzplayer.core.util.hasUsbMassStorage
import com.rhnxdev.hzplayer.core.util.guessMimeType
import com.rhnxdev.hzplayer.core.util.isAudioExtension
import com.rhnxdev.hzplayer.core.util.isVideoExtension
import com.rhnxdev.hzplayer.core.util.mountDirectory
import com.rhnxdev.hzplayer.core.util.storageVolumeLabel
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.domain.model.StorageKind
import com.rhnxdev.hzplayer.domain.repository.FileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

class FileRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentResolver: ContentResolver,
) : FileRepository {

    override fun listDirectory(path: String, showHidden: Boolean): Flow<List<FolderItem>> = flow {
        val file = File(path.trimEnd('/'))
        if (!file.exists() || !file.isDirectory) {
            emit(emptyList())
            return@flow
        }

        val files = file.listFiles() ?: run {
            emit(emptyList())
            return@flow
        }

        val noMedia = File(file, ".nomedia").exists()

        emit(
            files.asSequence()
                .filter { f ->
                    // .nomedia marks the directory as media-hidden; skip only the
                    // marker file itself, not the entire tree.
                    if (f.name == ".nomedia") false
                    else showHidden || !f.name.startsWith(".")
                }
                .map { f ->
                    val creationTime = try {
                        val nioPath = java.nio.file.Paths.get(f.absolutePath)
                        val attrs = java.nio.file.Files.readAttributes(nioPath, java.nio.file.attribute.BasicFileAttributes::class.java)
                        attrs.creationTime().toMillis()
                    } catch (_: Exception) {
                        f.lastModified()
                    }

                        val children = if (f.isDirectory) {
                            try { f.listFiles() } catch (e: Exception) { null }
                        } else null
                        val childFolders = children?.count { it.isDirectory } ?: 0
                        val childFiles = (children?.size ?: 0) - childFolders
                        val childMedia = children?.count {
                            !it.isDirectory && (isVideoExtension(it.name) || isAudioExtension(it.name))
                        } ?: 0
                        FolderItem(
                            id = f.hashCode().toLong(),
                            name = f.name,
                            path = f.absolutePath,
                            isDirectory = f.isDirectory,
                            fileSize = if (f.isFile) f.length() else 0,
                            childCount = children?.size ?: 0,
                            subfolderCount = childFolders,
                            fileCount = childFiles,
                            mediaCount = childMedia,
                            dateModified = f.lastModified(),
                            mimeType = if (f.isFile) guessMimeType(f.name) else null,
                            dateAdded = creationTime / 1000L,
                        )
                }.sortedWith(
                    compareByDescending<FolderItem> { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                ).toList()
        )
    }.flowOn(Dispatchers.IO)

    override fun getStorageRoots(): Flow<List<FolderItem>> = flow {
        val roots = mutableListOf<FolderItem>()
        val seen = mutableSetOf<String>()

        val internalStorage = Environment.getExternalStorageDirectory()
        if (internalStorage.exists()) {
            seen.add(internalStorage.absolutePath)
            roots.add(
                buildRootItem(
                    id = 0,
                    name = context.getString(R.string.internal_storage),
                    dir = internalStorage,
                    storageKind = StorageKind.INTERNAL,
                )
            )
        }

        // StorageManager reports every mounted volume with an accurate removable
        // flag, so SD cards and USB/OTG drives are classified reliably instead of
        // guessing from a /storage directory scan.
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val usbAttached = context.hasUsbMassStorage()
        val volumeRoots = storageManager?.storageVolumes.orEmpty()
            .asSequence()
            .filterNot { it.isPrimary }
            .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
            .mapNotNull { volume -> volume.mountDirectory()?.let { volume to it } }
            .filter { (_, dir) -> dir.absolutePath !in seen && dir.exists() && dir.isDirectory }
            .toList()

        volumeRoots.forEachIndexed { index, (volume, dir) ->
            seen.add(dir.absolutePath)
            roots.add(
                buildRootItem(
                    id = (100 + index).toLong(),
                    name = storageVolumeLabel(context, volume, dir, index, usbAttached),
                    dir = dir,
                    storageKind = classifyVolume(context, volume, dir, usbAttached),
                )
            )
        }

        // Fallback: some OEMs mount OTG/SD volumes that StorageManager omits.
        // Scan /storage siblings for any mount point not already reported.
        @Suppress("DEPRECATION")
        val fallbackDirs = try {
            internalStorage.parentFile?.listFiles()
                ?.filter {
                    it.isDirectory && it.absolutePath !in seen && it.exists() &&
                        // /storage/self is an alias of the primary volume, not a volume of its own.
                        !it.name.equals("self", ignoreCase = true)
                }
                ?: emptyList()
        } catch (_: Exception) { emptyList() }

        fallbackDirs.forEachIndexed { index, dir ->
            // Skip non-navigable pseudo mounts (empty, no read access).
            val children = try { dir.listFiles() } catch (_: Exception) { null } ?: return@forEachIndexed
            roots.add(
                buildRootItem(
                    id = (200 + index).toLong(),
                    name = fallbackVolumeLabel(context, dir, index, usbAttached),
                    dir = dir,
                    children = children,
                    storageKind = fallbackVolumeKind(dir, usbAttached),
                )
            )
        }

        emit(roots)
    }.flowOn(Dispatchers.IO)

    /** Build a storage-root [FolderItem] for [dir]; [children] reuses an existing listing. */
    private fun buildRootItem(
        id: Long,
        name: String,
        dir: File,
        children: Array<File>? = null,
        storageKind: StorageKind? = null,
    ): FolderItem {
        val kids = children ?: try { dir.listFiles() } catch (_: Exception) { null }
        val folders = kids?.count { it.isDirectory } ?: 0
        val files = (kids?.size ?: 0) - folders
        val media = kids?.count {
            !it.isDirectory && (isVideoExtension(it.name) || isAudioExtension(it.name))
        } ?: 0
        return FolderItem(
            id = id,
            name = name,
            path = dir.absolutePath,
            isDirectory = true,
            freeSpace = dir.freeSpace,
            totalSpace = dir.totalSpace,
            childCount = kids?.size ?: 0,
            subfolderCount = folders,
            fileCount = files,
            mediaCount = media,
            storageKind = storageKind,
        )
    }

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

        if (results.isEmpty()) emit(emptyList()) else emit(results)
    }.flowOn(Dispatchers.IO)

    override suspend fun copyEntry(sourcePath: String, destDirPath: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = File(sourcePath)
                val destDir = File(destDirPath)
                validateOperation(source, destDir)

                val target = uniqueTarget(destDir, source.name)
                copyRecursively(source, target)
                scanPaths(collectFilePaths(target))
                target.absolutePath
            }
        }

    override suspend fun moveEntry(sourcePath: String, destDirPath: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = File(sourcePath)
                val destDir = File(destDirPath)
                validateOperation(source, destDir)
                if (source.parentFile?.absolutePath == destDir.absolutePath) {
                    throw IOException("Source is already in this folder")
                }

                val target = uniqueTarget(destDir, source.name)
                val movedFilePaths: List<String>
                val oldFilePaths = collectFilePaths(source)

                // Fast path: atomic rename works when both ends are on the same volume.
                if (source.renameTo(target)) {
                    movedFilePaths = collectFilePaths(target)
                } else {
                    // Cross-volume: copy then delete the original.
                    copyRecursively(source, target)
                    if (!source.deleteRecursively()) {
                        // Copy succeeded but cleanup didn't — keep the copy, report the leftover.
                        throw IOException("Moved, but could not remove the original at $sourcePath")
                    }
                    movedFilePaths = collectFilePaths(target)
                }
                scanPaths(oldFilePaths + movedFilePaths)
                target.absolutePath
            }
        }

    override suspend fun deleteEntry(path: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = File(path)
                if (!target.exists()) throw IOException("File no longer exists")
                val parent = target.parentFile
                if (parent != null && !parent.canWrite()) throw IOException("Folder is not writable")

                // Collect file paths before deleting so MediaStore can drop the stale rows.
                val removedPaths = collectFilePaths(target)
                if (!target.deleteRecursively()) {
                    throw IOException("Could not delete ${target.name}")
                }
                scanPaths(removedPaths)
                target.name
            }
        }

    override suspend fun createDirectory(parentPath: String, folderName: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val parent = File(parentPath.trimEnd('/'))
                if (!parent.exists() || !parent.isDirectory) {
                    throw IOException("Parent directory not found")
                }
                val cleanName = folderName.trim()
                if (cleanName.isBlank() || cleanName.contains('/') || cleanName.contains('\\')) {
                    throw IOException("Invalid folder name")
                }
                val newDir = File(parent, cleanName)
                if (newDir.exists()) {
                    throw IOException("Folder already exists")
                }
                val created = newDir.mkdir() || newDir.mkdirs()
                if (!created && !newDir.exists()) {
                    throw IOException("Could not create folder")
                }
                scanPaths(listOf(newDir.absolutePath))
                newDir.absolutePath
            }
        }

    private fun validateOperation(source: File, destDir: File) {
        if (!source.exists()) throw IOException("Source no longer exists")
        if (!destDir.exists() || !destDir.isDirectory) throw IOException("Destination folder not found")
        if (!destDir.canWrite()) throw IOException("Destination folder is not writable")
        if (source.isDirectory) {
            val srcPath = source.absolutePath.trimEnd('/') + "/"
            val dstPath = destDir.absolutePath.trimEnd('/') + "/"
            if (dstPath == srcPath || dstPath.startsWith(srcPath)) {
                throw IOException("Cannot paste a folder into itself")
            }
        }
        if (source.isFile && destDir.freeSpace in 1 until source.length()) {
            throw IOException("Not enough free space on destination")
        }
    }

    /** Resolve name collisions with a "name (1).ext" style suffix. */
    private fun uniqueTarget(destDir: File, name: String): File {
        var candidate = File(destDir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(destDir, "$base ($index)$ext")
            index++
        }
        return candidate
    }

    private fun copyRecursively(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.mkdirs()) throw IOException("Could not create folder ${target.name}")
            source.listFiles()?.forEach { child ->
                copyRecursively(child, File(target, child.name))
            }
        } else {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 256 * 1024)
                }
            }
            if (target.length() != source.length()) {
                target.delete()
                throw IOException("Copy verification failed for ${source.name}")
            }
        }
        target.setLastModified(source.lastModified())
    }

    private fun collectFilePaths(root: File): List<String> = when {
        root.isDirectory -> root.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList()
        else -> listOf(root.absolutePath)
    }

    /** Tell MediaStore about created/removed files so the media library stays in sync. */
    private fun scanPaths(paths: List<String>) {
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }
}
