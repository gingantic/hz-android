package com.rhnxdev.hzplayer.data.repository

import com.rhnxdev.hzplayer.data.datasource.local.room.dao.MediaDao
import com.rhnxdev.hzplayer.data.datasource.media.MediaScanner
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
        var emitted = false
        val cached = mediaDao.getAllAudio().first()
        if (cached.isNotEmpty()) {
            val list = cached.map { it.toAudioItem() }
            cachedSongs = list
            emit(list)
            emitted = true
        } else if (BuildConfig.DEBUG) {
            // Show preview instantly — debug only
            emit(PreviewMedia.songs)
            emitted = true
        }

        try {
            val scanned = mediaScanner.scanAudio().first()
            if (scanned.isNotEmpty()) {
                mediaDao.replaceAudio(scanned)
                val list = scanned.map { it.toAudioItem() }
                cachedSongs = list
                emit(list)
                emitted = true
            } else if (!emitted) {
                emit(emptyList())
                emitted = true
            }
        } catch (e: Exception) {
            if (!emitted) {
                throw e
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun getAlbums(forceRefresh: Boolean, minDurationSecs: Int): Flow<List<Album>> = flow {
        val memCache = cachedAlbums
        if (memCache != null && !forceRefresh) {
            emit(memCache)
            return@flow
        }
        val songs = loadFilteredSongs(minDurationSecs)
        if (songs.isNotEmpty()) {
            val albums = buildAlbums(songs)
            cachedAlbums = albums
            emit(albums)
        } else if (BuildConfig.DEBUG) {
            // Preview fallback — debug only; will be replaced when songs are scanned
            val albums = PreviewMedia.albums
            cachedAlbums = albums
            emit(albums)
        } else {
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    override fun getArtists(forceRefresh: Boolean, minDurationSecs: Int): Flow<List<Artist>> = flow {
        val memCache = cachedArtists
        if (memCache != null && !forceRefresh) {
            emit(memCache)
            return@flow
        }
        val songs = loadFilteredSongs(minDurationSecs)
        if (songs.isNotEmpty()) {
            val artists = buildArtists(songs)
            cachedArtists = artists
            emit(artists)
        } else if (BuildConfig.DEBUG) {
            // Preview fallback — debug only
            val artists = PreviewMedia.artists
            cachedArtists = artists
            emit(artists)
        } else {
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    /** Full song list from Room, filtered by the minimum-duration preference. */
    private suspend fun loadFilteredSongs(minDurationSecs: Int): List<AudioItem> =
        mediaDao.getAllAudio().first().map { it.toAudioItem() }
            .filter { minDurationSecs <= 0 || it.durationMs >= minDurationSecs * 1000L }

    // Group by album title only. A single album can have per-track artist tags
    // (compilations, "feat." credits) — DISTINCT album+artist would split it into
    // many cards. One card per title; artist = the album's common/first artist.
    private fun buildAlbums(songs: List<AudioItem>): List<Album> = songs
        .filter { !it.album.isNullOrBlank() }
        .groupBy { it.album.orEmpty() }
        .map { (title, albumSongs) ->
            val artists = albumSongs.mapNotNull { it.artist }.distinct()
            Album(
                id = title.hashCode().toLong(),
                title = title,
                // Single artist → name; multiple → "Various artists".
                artist = if (artists.size == 1) artists.first() else "Various artists",
                albumArtUri = albumSongs.firstNotNullOfOrNull { it.albumArtUri },
                trackCount = albumSongs.size,
            )
        }
        .sortedBy { it.title.lowercase() }

    // Build artists from the full song list so we can compute real album/track
    // counts + a cover (first song's album art). DISTINCT-artist projection alone
    // can't give counts. Artist identity is the name string (no ARTIST_ID scanned).
    private fun buildArtists(songs: List<AudioItem>): List<Artist> = songs
        .filter { !it.artist.isNullOrBlank() }
        .groupBy { it.artist.orEmpty() }
        .map { (name, artistSongs) ->
            Artist(
                id = name.hashCode().toLong(),
                name = name,
                albumCount = artistSongs.mapNotNull { it.album }.distinct().size,
                trackCount = artistSongs.size,
                albumArtUri = artistSongs.firstNotNullOfOrNull { it.albumArtUri },
            )
        }
        .sortedBy { it.name.lowercase() }

    override fun getSongsByAlbum(albumTitle: String): Flow<List<AudioItem>> {
        if (BuildConfig.DEBUG) {
            val preview = PreviewMedia.songs.filter { it.album == albumTitle }
            if (preview.isNotEmpty()) return flow { emit(preview) }
        }
        return mediaDao.getSongsByAlbum(albumTitle).map { entities ->
            entities.map { it.toAudioItem() }
        }
    }

    override fun getSongsByArtist(artistName: String): Flow<List<AudioItem>> {
        if (BuildConfig.DEBUG) {
            val preview = PreviewMedia.songs.filter { it.artist == artistName }
            if (preview.isNotEmpty()) return flow { emit(preview) }
        }
        return mediaDao.getSongsByArtist(artistName).map { entities ->
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

    override fun searchAlbums(query: String): Flow<List<Album>> = flow {
        // Serve from the in-memory cache when possible — avoids re-reading and
        // re-grouping the whole audio table on every keystroke.
        val albums = cachedAlbums ?: getAlbums().first()
        emit(albums.filter { it.title.contains(query, ignoreCase = true) })
    }.flowOn(Dispatchers.IO)

    override fun searchArtists(query: String): Flow<List<Artist>> = flow {
        val artists = cachedArtists ?: getArtists().first()
        emit(artists.filter { it.name.contains(query, ignoreCase = true) })
    }.flowOn(Dispatchers.IO)
}
