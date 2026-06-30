package com.rhnxdev.hzplayer.data.repository

import com.rhnxdev.hzplayer.data.datasource.local.room.dao.MediaDao
import com.rhnxdev.hzplayer.data.datasource.media.MediaScanner
import com.rhnxdev.hzplayer.data.mapper.toAlbum
import com.rhnxdev.hzplayer.data.mapper.toArtist
import com.rhnxdev.hzplayer.data.mapper.toAudioItem
import com.rhnxdev.hzplayer.domain.model.Album
import com.rhnxdev.hzplayer.domain.model.Artist
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.repository.AudioRepository
import com.rhnxdev.hzplayer.presentation.preview.PreviewMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioRepositoryImpl @Inject constructor(
    private val mediaDao: MediaDao,
    private val mediaScanner: MediaScanner,
) : AudioRepository {

    override fun getAllSongs(): Flow<List<AudioItem>> = flow {
        val cached = mediaDao.getAllAudio().first()
        if (cached.isNotEmpty()) {
            emit(cached.map { it.toAudioItem() })
            return@flow
        }

        val scanned = mediaScanner.scanAudio().first()
        if (scanned.isNotEmpty()) {
            mediaDao.insertAll(scanned)
            emit(scanned.map { it.toAudioItem() })
            return@flow
        }

        emit(PreviewMedia.songs)
    }.flowOn(Dispatchers.IO)

    override fun getAlbums(): Flow<List<Album>> = flow {
        val cached = mediaDao.getAlbums().first()
        if (cached.isNotEmpty()) {
            // Get track counts per album
            val albums = cached.map { projection ->
                val count = mediaDao.getSongsByAlbum(projection.album).first().size
                projection.toAlbum(count)
            }
            emit(albums)
            return@flow
        }
        emit(PreviewMedia.albums)
    }.flowOn(Dispatchers.IO)

    override fun getArtists(): Flow<List<Artist>> = flow {
        val cached = mediaDao.getArtists().first()
        if (cached.isNotEmpty()) {
            emit(cached.map { it.toArtist() })
            return@flow
        }
        emit(PreviewMedia.artists)
    }.flowOn(Dispatchers.IO)

    override fun getSongsByAlbum(albumId: Long): Flow<List<AudioItem>> {
        val album = PreviewMedia.albums.find { it.id == albumId }
        return mediaDao.getSongsByAlbum(album?.title ?: "").map { entities ->
            entities.map { it.toAudioItem() }
        }
    }

    override fun getSongsByArtist(artistId: Long): Flow<List<AudioItem>> {
        val artist = PreviewMedia.artists.find { it.id == artistId }
        return mediaDao.getSongsByArtist(artist?.name ?: "").map { entities ->
            entities.map { it.toAudioItem() }
        }
    }

    override suspend fun toggleFavorite(songId: Long, isFavorite: Boolean) {
        mediaDao.updateFavorite(songId, isFavorite)
    }

    override fun searchSongs(query: String): Flow<List<AudioItem>> {
        return mediaDao.searchAudio(query).map { entities ->
            entities.map { it.toAudioItem() }
        }
    }
}
