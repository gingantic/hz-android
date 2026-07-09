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
import com.rhnxdev.hzplayer.BuildConfig
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

    // In-memory caches so re-entering a tab skips the MediaStore re-scan.
    private var cachedSongs: List<AudioItem>? = null
    private var cachedAlbums: List<Album>? = null
    private var cachedArtists: List<Artist>? = null

    override fun getAllSongs(forceRefresh: Boolean): Flow<List<AudioItem>> = flow {
        val memCache = cachedSongs
        if (memCache != null && !forceRefresh) {
            emit(memCache)
            return@flow
        }
        val cached = mediaDao.getAllAudio().first()
        if (cached.isNotEmpty()) {
            val list = cached.map { it.toAudioItem() }
            cachedSongs = list
            emit(list)
        } else if (BuildConfig.DEBUG) {
            // Show preview instantly — debug only
            emit(PreviewMedia.songs)
        }

        try {
            val scanned = mediaScanner.scanAudio().first()
            if (scanned.isNotEmpty()) {
                mediaDao.deleteAudio()
                mediaDao.insertAll(scanned)
                val list = scanned.map { it.toAudioItem() }
                cachedSongs = list
                emit(list)
            }
        } catch (_: Exception) { /* preview already emitted */ }
    }.flowOn(Dispatchers.IO)

    override fun getAlbums(forceRefresh: Boolean): Flow<List<Album>> = flow {
        val memCache = cachedAlbums
        if (memCache != null && !forceRefresh) {
            emit(memCache)
            return@flow
        }
        val cached = mediaDao.getAlbums().first()
        if (cached.isNotEmpty()) {
            val albums = cached.map { projection ->
                val count = mediaDao.getSongsByAlbum(projection.album).first().size
                projection.toAlbum(count)
            }
            cachedAlbums = albums
            emit(albums)
            return@flow
        }
        // Preview fallback — debug only; will be replaced when songs are scanned
        if (BuildConfig.DEBUG) {
            val albums = PreviewMedia.albums
            cachedAlbums = albums
            emit(albums)
        }
    }.flowOn(Dispatchers.IO)

    override fun getArtists(forceRefresh: Boolean): Flow<List<Artist>> = flow {
        val memCache = cachedArtists
        if (memCache != null && !forceRefresh) {
            emit(memCache)
            return@flow
        }
        val cached = mediaDao.getArtists().first()
        if (cached.isNotEmpty()) {
            val artists = cached.map { it.toArtist() }
            cachedArtists = artists
            emit(artists)
            return@flow
        }
        // Preview fallback — debug only
        if (BuildConfig.DEBUG) {
            val artists = PreviewMedia.artists
            cachedArtists = artists
            emit(artists)
        }
    }.flowOn(Dispatchers.IO)

    override fun getSongsByAlbum(albumId: Long): Flow<List<AudioItem>> {
        val album = if (BuildConfig.DEBUG) PreviewMedia.albums.find { it.id == albumId } else null
        return mediaDao.getSongsByAlbum(album?.title ?: "").map { entities ->
            entities.map { it.toAudioItem() }
        }
    }

    override fun getSongsByArtist(artistId: Long): Flow<List<AudioItem>> {
        val artist = if (BuildConfig.DEBUG) PreviewMedia.artists.find { it.id == artistId } else null
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
