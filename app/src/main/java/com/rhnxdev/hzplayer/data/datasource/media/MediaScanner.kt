package com.rhnxdev.hzplayer.data.datasource.media

import android.content.Context
import android.provider.MediaStore
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.MediaEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun scanVideos(): Flow<List<MediaEntity>> = flow {
        val videos = mutableListOf<MediaEntity>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.RESOLUTION,
        )

        val cursor = context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )

        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val dataCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val durationCol = it.getColumnIndex(MediaStore.Video.Media.DURATION)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateAddedCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val dateModifiedCol = it.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
            val mimeCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val resolutionCol = it.getColumnIndex(MediaStore.Video.Media.RESOLUTION)

            while (it.moveToNext()) {
                videos.add(
                    MediaEntity(
                        id = it.getLong(idCol),
                        title = it.getString(titleCol) ?: "Unknown",
                        uri = it.getString(dataCol) ?: "",
                        mediaType = "video",
                        durationMs = if (durationCol >= 0) it.getLong(durationCol) else 0,
                        fileSize = it.getLong(sizeCol),
                        mimeType = if (mimeCol >= 0) it.getString(mimeCol) else null,
                        resolution = if (resolutionCol >= 0) it.getString(resolutionCol) else null,
                        dateAdded = it.getLong(dateAddedCol),
                        dateModified = if (dateModifiedCol >= 0) it.getLong(dateModifiedCol) else 0,
                    ),
                )
            }
        }
        emit(videos)
    }.flowOn(Dispatchers.IO)

    fun scanAudio(): Flow<List<MediaEntity>> = flow {
        val audio = mutableListOf<MediaEntity>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.MIME_TYPE,
        )

        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )

        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val dataCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val durationCol = it.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val artistCol = it.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumCol = it.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val trackCol = it.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val dateAddedCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val dateModifiedCol = it.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
            val mimeCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

            while (it.moveToNext()) {
                audio.add(
                    MediaEntity(
                        id = it.getLong(idCol),
                        title = it.getString(titleCol) ?: "Unknown",
                        uri = it.getString(dataCol) ?: "",
                        mediaType = "audio",
                        durationMs = if (durationCol >= 0) it.getLong(durationCol) else 0,
                        fileSize = it.getLong(sizeCol),
                        mimeType = if (mimeCol >= 0) it.getString(mimeCol) else null,
                        artist = if (artistCol >= 0) it.getString(artistCol) else null,
                        album = if (albumCol >= 0) it.getString(albumCol) else null,
                        trackNumber = if (trackCol >= 0) it.getInt(trackCol) else 0,
                        dateAdded = it.getLong(dateAddedCol),
                        dateModified = if (dateModifiedCol >= 0) it.getLong(dateModifiedCol) else 0,
                    ),
                )
            }
        }
        emit(audio)
    }.flowOn(Dispatchers.IO)

    fun getAlbumArtUri(albumId: Long): String? {
        val uri = android.net.Uri.parse("content://media/external/audio/albumart/$albumId")
        return if (albumId > 0) uri.toString() else null
    }
}
