package com.rhnxdev.hzplayer.data.datasource.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.rhnxdev.hzplayer.core.util.isNeighborSubtitleName
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.AssHandler
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.SubtitleConverters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers neighbor subtitle files for a given media URI.
 *
 * Extracted from [ExoPlayerEngine] to keep playback control and subtitle
 * discovery separate. Lives in the same package so it retains access to
 * package-private SMB helpers ([SmbPathResolver], [ConnectionPool]).
 */
@Singleton
class NeighborSubtitleDiscoverer @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val assHandler: AssHandler,
    private val playerHolder: MediaPlayerHolder,
) {
    /**
     * Search for subtitle files next to [videoUri] and load them.
     * Libass-eligible formats (ASS/SSA + convertible SRT/VTT) are loaded
     * directly into [AssHandler]; others are returned as ExoPlayer
     * [MediaItem.SubtitleConfiguration] for the built-in renderer.
     */
    suspend fun discover(videoUri: String): List<MediaItem.SubtitleConfiguration> {
        playerHolder.setDiscovering()
        val subUris = try {
            findNeighborSubtitleFiles(videoUri)
        } catch (_: Exception) {
            emptyList<Uri>()
        }

        val exoConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
        for (subUri in subUris) {
            val ext = (subUri.path ?: "").substringAfterLast('.').lowercase()
            val mimeType = inferSubtitleMimeType(subUri)
            val displayName = subUri.lastPathSegment ?: subUri.toString()

            if (ext == "ass" || ext == "ssa" || SubtitleConverters.isConvertibleSubtitleFormat(mimeType)) {
                val data = readUriBytes(subUri)
                if (data != null) {
                    val assBytes = if (ext == "ass" || ext == "ssa") {
                        data
                    } else {
                        SubtitleConverters.convertToAss(
                            data, mimeType,
                            assHandler.getVideoWidth(), assHandler.getVideoHeight()
                        )
                    }
                    if (assBytes != null) {
                        withContext(Dispatchers.Main) {
                            assHandler.loadExternalTrack(assBytes, displayName)
                        }
                    }
                }
            } else {
                val config = MediaItem.SubtitleConfiguration.Builder(subUri)
                    .setMimeType(mimeType)
                    .setLanguage("und")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
                exoConfigs.add(config)
            }
        }
        return exoConfigs
    }

    /** Read a subtitle file's bytes, handling both local and remote URIs. */
    fun readUriBytes(uri: Uri): ByteArray? = runCatching {
        when (uri.scheme?.lowercase()) {
            "content", "file" -> appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            else -> playerHolder.readUriBytes(uri)
        }
    }.getOrNull()

    fun inferSubtitleMimeType(uri: Uri): String {
        val ext = uri.path?.substringAfterLast('.')?.lowercase() ?: return MimeTypes.APPLICATION_SUBRIP
        return when (ext) {
            "vtt" -> MimeTypes.TEXT_VTT
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "sub" -> MimeTypes.APPLICATION_SUBRIP
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    // ── Neighbor file discovery ──

    private fun findNeighborSubtitleFiles(videoUri: String): List<Uri> {
        val androidUri = Uri.parse(videoUri)
        val scheme = androidUri.scheme?.lowercase() ?: "file"
        val found = when (scheme) {
            "file" -> findLocalNeighborSubtitles(androidUri)
            "smb" -> findSmbNeighborSubtitles(androidUri)
            "ftp", "sftp", "webdav", "webdavs" -> findRemoteExtensionSwapSubtitles(androidUri)
            else -> emptyList()
        }
        Log.i(TAG, "[SUBDISC] scheme=$scheme found=${found.size} subs=${found.map { it.lastPathSegment }}")
        return found
    }

    private fun findLocalNeighborSubtitles(androidUri: Uri): List<Uri> {
        val videoPath = androidUri.path ?: return emptyList()
        val videoFile = File(videoPath)
        val parentDir = videoFile.parentFile ?: return emptyList()
        val baseName = videoFile.nameWithoutExtension

        val direct = parentDir.listFiles()
            ?.filter { file -> isNeighborSubtitleName(file.name, baseName) }
            ?.map { Uri.fromFile(it) }
            .orEmpty()
        if (direct.isNotEmpty()) return direct

        return findLocalSubtitlesViaMediaStore(parentDir.absolutePath, baseName)
    }

    private fun findLocalSubtitlesViaMediaStore(parentPath: String, baseName: String): List<Uri> {
        return try {
            val collection = android.provider.MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                android.provider.MediaStore.Files.FileColumns._ID,
                android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME,
                android.provider.MediaStore.Files.FileColumns.DATA,
            )
            val selection = "${android.provider.MediaStore.Files.FileColumns.DATA} LIKE ?"
            val args = arrayOf("$parentPath/%")
            val out = mutableListOf<Uri>()
            appContext.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                c.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns._ID)
                val dataCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns.DATA)
                while (c.moveToNext()) {
                    val data = c.getString(dataCol) ?: continue
                    val name = data.substringAfterLast('/')
                    if (data.substringBeforeLast('/') != parentPath) continue
                    if (isNeighborSubtitleName(name, baseName)) {
                        out.add(Uri.fromFile(File(data)))
                    }
                }
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore subtitle discovery failed for $parentPath", e)
            emptyList()
        }
    }

    private fun findSmbNeighborSubtitles(androidUri: Uri): List<Uri> {
        return try {
            val userInfo = androidUri.userInfo ?: ""
            val parts = userInfo.split(":", limit = 2)
            val user = Uri.decode(parts.getOrNull(0) ?: "")
            val pass = Uri.decode(parts.getOrNull(1) ?: "")
            val host = androidUri.host ?: return emptyList()
            val port = androidUri.port.takeIf { it > 0 } ?: 445

            val path = androidUri.path ?: return emptyList()
            val videoName = path.substringAfterLast('/')
            val baseName = videoName.substringBeforeLast('.')

            val encodedPath = androidUri.encodedPath ?: return emptyList()
            val encodedParentPath = encodedPath.substringBeforeLast('/').ifEmpty { "/" }
            val segments = SmbPathResolver.decodedSegmentsOf(encodedPath)

            val ctx = ConnectionPool.borrowSmbContext(host, port, user, pass)
            val dir = SmbPathResolver.resolveParent(ctx, host, port, segments)
                ?: return emptyList()

            val siblings = dir.listFiles()?.toList() ?: return emptyList()

            siblings
                .filter { file -> isNeighborSubtitleName(file.name.trimEnd('/'), baseName) }
                .map { file ->
                    val encodedName = Uri.encode(file.name.trimEnd('/'))
                    androidUri.buildUpon()
                        .encodedPath("$encodedParentPath/$encodedName")
                        .build()
                }
        } catch (e: Exception) {
            Log.w(TAG, "SMB subtitle discovery failed for $androidUri", e)
            emptyList()
        }
    }

    private fun findRemoteExtensionSwapSubtitles(androidUri: Uri): List<Uri> {
        val path = androidUri.path ?: return emptyList()
        val basePath = path.substringBeforeLast('.')
        val extensions = setOf("srt", "vtt", "ass", "ssa", "sub")
        return extensions.map { ext ->
            androidUri.buildUpon().path("$basePath.$ext").build()
        }
    }

    companion object {
        private const val TAG = "SubDiscoverer"
    }
}
