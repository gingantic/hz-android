package com.rhnxdev.hzplayer.data.network

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhnxdev.hzplayer.data.datasource.player.ConnectionPool
import com.rhnxdev.hzplayer.data.datasource.player.FtpDataSource
import com.rhnxdev.hzplayer.data.datasource.player.SftpDataSource
import com.rhnxdev.hzplayer.data.datasource.player.SmbDataSource
import com.rhnxdev.hzplayer.data.datasource.player.WebDavDataSource
import com.rhnxdev.hzplayer.data.repository.RemoteBrowseRepositoryImpl
import com.rhnxdev.hzplayer.domain.model.NetworkProtocol
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Playback through the Media3 DataSources on the emulator, using the same playback
 * URIs the player gets from RemoteBrowseRepository.buildPlaybackUri.
 * Requires tools/netsim to be running (see its README).
 */
@RunWith(AndroidJUnit4::class)
class NetworkSimStreamInstrumentedTest {

    private val repo = RemoteBrowseRepositoryImpl()

    @Before
    fun setUp() = resetNetworkState()

    @After
    fun tearDown() = ConnectionPool.releaseAll()

    @Test
    fun fullRead_returnsExactFixtureBytes() {
        val expected = Fixture.pattern(Fixture.CLIP_4MIB_SIZE)
        val failures = simFailures(SimServers.PLAYABLE) { server ->
            val actual = read(server, clipPath(server))
            assertArrayEquals("bytes differ", expected, actual)
        }
        assertNoSimFailures("full read", failures)
    }

    /** Models a seek: the player reopens at a position and reads to the end. */
    @Test
    fun seekRead_fromOffset_returnsTail() {
        val position = 1L shl 20
        val expected = Fixture.pattern(Fixture.CLIP_4MIB_SIZE)
            .copyOfRange(position.toInt(), Fixture.CLIP_4MIB_SIZE.toInt())
        val failures = simFailures(SimServers.PLAYABLE) { server ->
            val actual = read(server, clipPath(server), position = position)
            assertArrayEquals("tail bytes differ after seek to $position", expected, actual)
        }
        assertNoSimFailures("seek read", failures)
    }

    @Test
    fun boundedRead_returnsRequestedSlice() {
        val position = 12_345L
        val length = 4_096L
        val expected = Fixture.pattern(Fixture.CLIP_4MIB_SIZE)
            .copyOfRange(position.toInt(), (position + length).toInt())
        val failures = simFailures(SimServers.PLAYABLE) { server ->
            val actual = read(server, clipPath(server), position = position, length = length)
            assertArrayEquals("slice differs", expected, actual)
        }
        assertNoSimFailures("bounded read", failures)
    }

    /**
     * The player receives this path from a folder listing, so WebDAV paths arrive
     * percent-encoded. buildPlaybackUri encodes them again, which can break WebDAV
     * for names with spaces.
     */
    @Test
    fun fileWithSpaceInName_playsFromBrowsedPath() {
        val expected = Fixture.pattern(Fixture.SPACE_CLIP_SIZE)
        val failures = simFailures(SimServers.BROWSABLE) { server ->
            val root = repo.listOrThrow(server, server.basePath)
            val media = repo.listOrThrow(server, root.named(Fixture.MEDIA).path)
            val item = media.named(Fixture.SPACE_CLIP)
            val actual = read(server, item.path)
            assertArrayEquals("bytes differ for browsed path '${item.path}'", expected, actual)
        }
        assertNoSimFailures("file with space in name", failures)
    }

    /**
     * Listings return decoded paths and buildPlaybackUri encodes them once, so names with
     * `#`, `%`, spaces and CJK characters must open and return their own bytes.
     */
    @Test
    fun filesWithSpecialNames_playFromBrowsedPaths() {
        val failures = simFailures(SimServers.BROWSABLE) { server ->
            val root = repo.listOrThrow(server, server.basePath)
            val cases = listOf(
                Triple(Fixture.SPECIAL_DIR, Fixture.SPECIAL_FILE, "special characters\n"),
                Triple(Fixture.JAPANESE_DIR, Fixture.JAPANESE_FILE, "テスト\n"),
                Triple(Fixture.EMOJI_DIR, Fixture.EMOJI_FILE, "space test\n"),
            )
            cases.forEach { (dir, file, text) ->
                val item = repo.listOrThrow(server, root.named(dir).path).named(file)
                val actual = read(server, item.path)
                assertArrayEquals(
                    "bytes differ for '${item.path}'",
                    text.toByteArray(Charsets.UTF_8),
                    actual,
                )
            }
        }
        assertNoSimFailures("special file names", failures)
    }

    /** Credentials are never sent over cleartext HTTP, so a credentialed HTTP share cannot be opened. */
    @Test
    fun webDavHttpWithCredentials_playbackIsRefused() {
        val server = SimServers.WEBDAV_AUTH
        val error = openError(server, clipPath(server))
        assertTrue("expected open to fail", error != null)
        assertTrue("expected an auth failure (HTTP 401), got $error", error is IOException && error.isAuthFailure())
    }

    @Test
    fun missingFile_failsToOpenAsNotFound() {
        val failures = simFailures(SimServers.PLAYABLE) { server ->
            val error = openError(server, pathIn(server.basePath, Fixture.MEDIA, "does-not-exist.mp4"))
            assertTrue("expected open to fail", error != null)
            assertTrue(
                "expected an IOException reporting not-found, got $error",
                error is IOException && error.isNotFound(),
            )
        }
        assertNoSimFailures("missing file", failures)
    }

    @Test
    fun wrongPassword_failsToOpenAsAuthFailure() {
        val servers = SimServers.AUTH_REQUIRED
        val failures = simFailures(servers.map { it.withPassword(SimServers.WRONG_PASSWORD) }) { server ->
            val error = openError(server, clipPath(server))
            assertTrue("expected open to fail", error != null)
            assertTrue(
                "expected an IOException reporting a rejected login, got $error",
                error is IOException && error.isAuthFailure(),
            )
        }
        assertNoSimFailures("wrong password playback", failures)
    }

    private fun clipPath(server: SimServer): String =
        pathIn(server.basePath, Fixture.MEDIA, Fixture.CLIP_4MIB)

    private fun dataSourceFor(protocol: NetworkProtocol): DataSource = when (protocol) {
        NetworkProtocol.FTP -> FtpDataSource()
        NetworkProtocol.SFTP -> SftpDataSource()
        NetworkProtocol.SMB -> SmbDataSource()
        NetworkProtocol.WEBDAV, NetworkProtocol.WEBDAVS -> WebDavDataSource()
    }

    private fun specFor(
        server: SimServer,
        path: String,
        position: Long = 0,
        length: Long = C.LENGTH_UNSET.toLong(),
    ): DataSpec = DataSpec.Builder()
        .setUri(Uri.parse(repo.buildPlaybackUri(server.config(), path)))
        .setPosition(position)
        .setLength(length)
        .build()

    /** Returns the error from opening [path], or null if the open succeeded. */
    private fun openError(server: SimServer, path: String): Throwable? {
        val source = dataSourceFor(server.protocol)
        return try {
            source.open(specFor(server, path))
            null
        } catch (e: Throwable) {
            e
        } finally {
            source.close()
        }
    }

    /** Opens [path] and reads until end of input. Fails if the source keeps returning 0 bytes. */
    private fun read(
        server: SimServer,
        path: String,
        position: Long = 0,
        length: Long = C.LENGTH_UNSET.toLong(),
    ): ByteArray {
        val source = dataSourceFor(server.protocol)
        try {
            source.open(specFor(server, path, position, length))
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var emptyReads = 0
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                if (count == 0) {
                    emptyReads++
                    assertTrue("source returned 0 bytes repeatedly", emptyReads < 1_000)
                    continue
                }
                emptyReads = 0
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        } finally {
            source.close()
        }
    }
}
