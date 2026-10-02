package com.rhnxdev.hzplayer.data.repository

import com.rhnxdev.hzplayer.BuildConfig
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.MediaDao
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.MediaEntity
import com.rhnxdev.hzplayer.data.datasource.media.MediaScanner
import com.rhnxdev.hzplayer.domain.model.Album
import com.rhnxdev.hzplayer.presentation.preview.PreviewMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pins the albums/artists protocol. Room is **observed**, not read once: the song scan
 * rewrites the audio table after a screen has loaded, so a one-shot read pinned the
 * list to the pre-scan snapshot and cached it — the album tab could then show stale
 * albums, or `PreviewMedia.albums`, while the song tab showed real tracks, and no tab
 * re-focus corrected it. The album/artist detail screens share that protocol, where the
 * preview rule cuts the other way: a preview name matching a real one must not shadow
 * the real tracks.
 *
 * Room is modelled as a flow that emits an optional pre-scan snapshot and then the
 * current contents; every emission is mapped, so a two-emission fake reproduces the
 * race without any timing dependency.
 *
 * `kotlinx-coroutines-test` is not a dependency, so these collect through `runBlocking`
 * (see [CachedScanFlowTest] for the same constraint).
 */
@RunWith(RobolectricTestRunner::class)
class AudioRepositoryAlbumTest {

    /** Room already holds audio: albums come straight from it, no preview. */
    @Test
    fun roomPopulated_emitsAlbumsWithoutPreview() {
        val repo = repoWith(audio = listOf(song(1, "Get Lucky", "Random Access Memories", "Daft Punk")))

        val emissions = collectAlbums(repo)

        assertEquals(1, emissions.size)
        assertEquals(listOf("Random Access Memories"), emissions.single().map { it.title })
    }

    /** The preview still acts as a placeholder while the scan is in flight. */
    @Test
    fun roomEmpty_debug_emitsPreviewBeforeScanLands() {
        assumeTrue(BuildConfig.DEBUG)
        val repo = repoWith(
            audio = listOf(song(1, "Get Lucky", "Random Access Memories", "Daft Punk")),
            stale = emptyList(),
        )

        val emissions = collectAlbums(repo)

        assertEquals(2, emissions.size)
        assertEquals(PreviewMedia.albums.map { it.title }, emissions.first().map { it.title })
    }

    /**
     * The device-observed regression: Room held a pre-scan snapshot when the tab loaded
     * and the scan then replaced the table. The one-shot read never re-emitted, so the
     * stale album stayed on screen until the app was restarted.
     */
    @Test
    fun roomReplacedByScan_reEmitsFreshAlbums() {
        val repo = repoWith(
            stale = listOf(song(1, "SoundHelix Song 1", "SoundHelix Demo", "T. Schurrger")),
            audio = listOf(song(2, "SoundHelix Song 1", "SoundHelix Demo", "T. Schürger")),
        )

        val emissions = collectAlbums(repo)

        assertEquals(listOf("T. Schurrger"), emissions.first().map { it.artist })
        assertEquals(listOf("T. Schürger"), emissions.last().map { it.artist })
    }

    /**
     * The other half: the stale snapshot is what the first collection caches, and a tab
     * re-focus used to serve that cache and stop. Room's current contents must supersede
     * it on the next collection.
     */
    @Test
    fun staleMemoryCache_isSupersededByRoom() {
        val dao = FakeMediaDao(listOf(song(1, "SoundHelix Song 1", "SoundHelix Demo", "T. Schurrger")))
        val repo = repoWithDao(dao)

        collectAlbums(repo) // completes, caching the stale snapshot
        dao.audio = listOf(song(2, "SoundHelix Song 1", "SoundHelix Demo", "T. Schürger"))
        val emissions = collectAlbums(repo)

        assertEquals(listOf("T. Schurrger"), emissions.first().map { it.artist })
        assertEquals(listOf("T. Schürger"), emissions.last().map { it.artist })
    }

    /**
     * The regression: the placeholder used to be written to `cachedAlbums`, so every
     * later call short-circuited on the memory cache and returned fake albums even
     * though Room held real ones.
     */
    @Test
    fun previewIsNotCached_debug_secondCallReturnsRealAlbums() {
        assumeTrue(BuildConfig.DEBUG)
        val repo = repoWith(
            audio = listOf(song(1, "Get Lucky", "Random Access Memories", "Daft Punk")),
            stale = emptyList(),
        )

        collectAlbums(repo)
        val second = collectAlbums(repo)

        assertEquals(listOf("Random Access Memories"), second.last().map { it.title })
        assertTrue(
            "the cached albums still contain the debug preview",
            second.none { it.map { album -> album.title } == PreviewMedia.albums.map { it.title } },
        )
    }

    /** Release has no preview to fall back on; an empty library stays empty. */
    @Test
    fun roomEmpty_release_emitsEmptyList() {
        assumeFalse(BuildConfig.DEBUG)
        val repo = repoWith(audio = emptyList())

        assertEquals(listOf(emptyList<Album>()), collectAlbums(repo))
    }

    /**
     * The collision this guards: the preview branch used to be checked *before* Room, so
     * a real album whose title matched a preview album returned `PreviewMedia` songs for
     * good and the real tracks never appeared.
     */
    @Test
    fun albumTitleCollidingWithPreview_realSongsWin() {
        assumeTrue(BuildConfig.DEBUG)
        val title = PreviewMedia.songs.first().album!! // a title the preview also owns
        val repo = repoWith(audio = listOf(song(1, "Real Track", title, "Real Artist")))

        val songs = runBlocking { repo.getSongsByAlbum(title).first() }

        assertEquals(listOf("Real Track"), songs.map { it.title })
    }

    /** Same collision on the artist path. */
    @Test
    fun artistNameCollidingWithPreview_realSongsWin() {
        assumeTrue(BuildConfig.DEBUG)
        val name = PreviewMedia.songs.first().artist!!
        val repo = repoWith(audio = listOf(song(1, "Real Track", "Real Album", name)))

        val songs = runBlocking { repo.getSongsByArtist(name).first() }

        assertEquals(listOf("Real Track"), songs.map { it.title })
    }

    /**
     * The detail screen still gets a placeholder for a card the grid showed while Room
     * was empty — replaced by the real tracks once the scan lands.
     */
    @Test
    fun albumDetail_roomEmpty_debug_emitsPreviewBeforeScanLands() {
        assumeTrue(BuildConfig.DEBUG)
        val title = PreviewMedia.songs.first().album!!
        val repo = repoWith(
            stale = emptyList(),
            audio = listOf(song(1, "Real Track", title, "Real Artist")),
        )

        val emissions = runBlocking { repo.getSongsByAlbum(title).toList() }

        assertEquals(2, emissions.size)
        assertEquals(PreviewMedia.songs.filter { it.album == title }, emissions.first())
        assertEquals(listOf("Real Track"), emissions.last().map { it.title })
    }

    private fun collectAlbums(repo: AudioRepositoryImpl) = runBlocking {
        repo.getAlbums().toList()
    }

    /**
     * @param stale the pre-scan snapshot, emitted before `audio` once the scan's
     *   `replaceAudio` has landed. `null` models Room already holding the final rows.
     */
    private fun repoWith(audio: List<MediaEntity>, stale: List<MediaEntity>? = null) =
        repoWithDao(FakeMediaDao(audio, stale))

    private fun repoWithDao(dao: MediaDao) = AudioRepositoryImpl(
        mediaDao = dao,
        mediaScanner = MediaScanner(RuntimeEnvironment.getApplication()),
    )

    private fun song(id: Long, title: String, album: String?, artist: String?) = MediaEntity(
        id = id,
        title = title,
        uri = "/storage/emulated/0/Music/$title.mp3",
        mediaType = "audio",
        durationMs = 180_000,
        fileSize = 1024,
        album = album,
        artist = artist,
    )

    /**
     * Only `getAllAudio` is exercised by the listing paths under test; everything else
     * fails loudly so an accidental dependency on it is caught rather than silently
     * returning an empty result.
     */
    private class FakeMediaDao(
        /** Mutable so a test can model the scan replacing the table mid-session. */
        var audio: List<MediaEntity>,
        private val stale: List<MediaEntity>? = null,
    ) : MediaDao {
        override fun getAllAudio(): Flow<List<MediaEntity>> = flow {
            stale?.let { emit(it) }
            emit(audio)
        }

        override fun getSongsByAlbum(albumTitle: String): Flow<List<MediaEntity>> = flow {
            stale?.let { emit(it.filter { e -> e.album == albumTitle }) }
            emit(audio.filter { it.album == albumTitle })
        }

        override fun getSongsByArtist(artistName: String): Flow<List<MediaEntity>> = flow {
            stale?.let { emit(it.filter { e -> e.artist == artistName }) }
            emit(audio.filter { it.artist == artistName })
        }

        override fun getAllVideos(): Flow<List<MediaEntity>> = unsupported()
        override suspend fun getById(id: Long): MediaEntity? = unsupported()
        override suspend fun getByUri(uri: String): MediaEntity? = unsupported()
        override suspend fun getByUris(uris: List<String>): List<MediaEntity> = unsupported()
        override fun searchVideos(query: String): Flow<List<MediaEntity>> = unsupported()
        override fun searchAudio(query: String): Flow<List<MediaEntity>> = unsupported()
        override suspend fun insertAll(media: List<MediaEntity>) = unsupported()
        override suspend fun insert(media: MediaEntity) = unsupported()
        override suspend fun update(media: MediaEntity) = unsupported()
        override suspend fun updateFavorite(id: Long, favorite: Boolean) = unsupported()
        override suspend fun updateWatchedProgress(id: Long, progress: Float) = unsupported()
        override suspend fun deleteById(id: Long) = unsupported()
        override suspend fun deleteAll() = unsupported()
        override suspend fun deleteVideos() = unsupported()
        override suspend fun deleteAudio() = unsupported()

        private fun unsupported(): Nothing =
            throw NotImplementedError("FakeMediaDao: not exercised by the listing paths")
    }
}
