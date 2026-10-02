package com.rhnxdev.hzplayer.data.repository

import com.rhnxdev.hzplayer.data.datasource.local.room.dao.MediaDao
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.MediaEntity
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

    override fun getAllSongs(forceRefresh: Boolean): Flow<List<AudioItem>> =
        cachedScanFlow(
            forceRefresh = forceRefresh,
            memoryCache = { cachedSongs },
            preview = PreviewMedia.songs,
            readRoom = { mediaDao.getAllAudio() },
            toItem = { it.toAudioItem() },
            scan = { mediaScanner.scanAudio() },
            replaceRoom = { mediaDao.replaceAudio(it) },
            cacheIn = { cachedSongs = it },
        )

    override fun getAlbums(forceRefresh: Boolean, minDurationSecs: Int): Flow<List<Album>> =
        roomBackedFlow(
            readRoom = { mediaDao.getAllAudio() },
            preview = PreviewMedia.albums,
            build = ::buildAlbums,
            memoryCache = { cachedAlbums },
            cacheIn = { cachedAlbums = it },
            forceRefresh = forceRefresh,
            filter = { minDurationSecs <= 0 || it.durationMs >= minDurationSecs * 1000L },
        )

    override fun getArtists(forceRefresh: Boolean, minDurationSecs: Int): Flow<List<Artist>> =
        roomBackedFlow(
            readRoom = { mediaDao.getAllAudio() },
            preview = PreviewMedia.artists,
            build = ::buildArtists,
            memoryCache = { cachedArtists },
            cacheIn = { cachedArtists = it },
            forceRefresh = forceRefresh,
            filter = { minDurationSecs <= 0 || it.durationMs >= minDurationSecs * 1000L },
        )

    /**
     * Room is the source of truth and is **observed**, not read once. The song scan
     * (`cachedScanFlow` → `replaceAudio`) rewrites the audio table after a screen has
     * loaded; a one-shot read pinned the list to the pre-scan snapshot and cached it,
     * so even a tab re-focus kept serving stale data.
     *
     * @param readRoom the query to observe — the whole audio table, or one album/artist.
     * @param preview placeholder for an empty Room; debug builds only, and never cached
     *   — it must not outlive the scan that fills Room. Room's rows always win, so a
     *   preview name colliding with a real album/artist cannot shadow the real tracks.
     * @param build maps the rows into the caller's model.
     * @param memoryCache read lazily and emitted first for an instant value; Room's
     *   current contents follow on the same collection, so the cache cannot pin a
     *   stale list.
     * @param cacheIn writes a freshly built list back to the in-memory cache.
     * @param forceRefresh skip the memory cache and wait for Room.
     * @param filter narrows the rows *before* the empty check, so a list that is empty
     *   only because of filtering still falls back to the preview.
     */
    private fun <T> roomBackedFlow(
        readRoom: () -> Flow<List<MediaEntity>>,
        preview: List<T>,
        build: (List<AudioItem>) -> List<T>,
        memoryCache: () -> List<T>? = { null },
        cacheIn: (List<T>) -> Unit = {},
        forceRefresh: Boolean = false,
        filter: (AudioItem) -> Boolean = { true },
    ): Flow<List<T>> = flow {
        val memCache = memoryCache()
        if (memCache != null && !forceRefresh) emit(memCache)
        var emitted = memCache != null && !forceRefresh

        readRoom().collect { entities ->
            val songs = entities.map { it.toAudioItem() }.filter(filter)
            if (songs.isEmpty() && !emitted && BuildConfig.DEBUG) {
                // Placeholder only — never cached; a cached preview would outlive the
                // scan that populates Room and mask the real library.
                emit(preview)
            } else {
                // Also the "library emptied" path: no songs → nothing to build, so the
                // UI clears rather than freezing on the last known list.
                val list = build(songs)
                cacheIn(list)
                emit(list)
            }
            emitted = true
        }
    }.flowOn(Dispatchers.IO)

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

    override fun getSongsByAlbum(albumTitle: String): Flow<List<AudioItem>> =
        roomBackedFlow(
            readRoom = { mediaDao.getSongsByAlbum(albumTitle) },
            // Only reached while Room has no such album: the placeholder behind a card
            // the grid showed before the scan landed.
            preview = PreviewMedia.songs.filter { it.album == albumTitle },
            build = { it },
        )

    override fun getSongsByArtist(artistName: String): Flow<List<AudioItem>> =
        roomBackedFlow(
            readRoom = { mediaDao.getSongsByArtist(artistName) },
            preview = PreviewMedia.songs.filter { it.artist == artistName },
            build = { it },
        )

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
