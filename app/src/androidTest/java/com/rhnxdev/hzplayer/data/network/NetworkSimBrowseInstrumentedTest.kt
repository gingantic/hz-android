package com.rhnxdev.hzplayer.data.network

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rhnxdev.hzplayer.data.datasource.player.ConnectionPool
import com.rhnxdev.hzplayer.data.repository.RemoteBrowseRepositoryImpl
import com.rhnxdev.hzplayer.domain.model.FolderCounts
import com.rhnxdev.hzplayer.domain.model.RemoteAuthException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import javax.net.ssl.SSLException

/**
 * Browsing against the simulated servers on the emulator. Each test runs one
 * capability across every server in its list and reports all failures together.
 * Requires tools/netsim to be running (see its README).
 */
@RunWith(AndroidJUnit4::class)
class NetworkSimBrowseInstrumentedTest {

    private val repo = RemoteBrowseRepositoryImpl()

    @Before
    fun setUp() = resetNetworkState()

    @After
    fun tearDown() = ConnectionPool.releaseAll()

    @Test
    fun listRoot_showsFixtureEntries() {
        val helloBytes = Fixture.HELLO_TEXT.toByteArray(Charsets.UTF_8).size.toLong()
        val failures = simFailures(SimServers.BROWSABLE) { server ->
            val root = repo.listOrThrow(server, server.basePath)
            val hello = root.named(Fixture.HELLO)
            assertFalse("hello.txt must be a file", hello.isDirectory)
            assertEquals("hello.txt size", helloBytes, hello.fileSize)
            Fixture.ROOT_FOLDERS.forEach { folder ->
                assertTrue("$folder must be a folder", root.named(folder).isDirectory)
            }
        }
        assertNoSimFailures("list root", failures)
    }

    @Test
    fun listNestedFolder_reportsKindsAndSizes() {
        val failures = simFailures(SimServers.BROWSABLE) { server ->
            val media = repo.listOrThrow(server, server.basePath).named(Fixture.MEDIA)
            assertTrue("media must be a folder", media.isDirectory)
            val children = repo.listOrThrow(server, media.path)
            assertEquals(
                "clip-4mib.mp4 size",
                Fixture.CLIP_4MIB_SIZE,
                children.named(Fixture.CLIP_4MIB).fileSize,
            )
            assertEquals(
                "short-512kib.mkv size",
                Fixture.SHORT_512KIB_SIZE,
                children.named(Fixture.SHORT_512KIB).fileSize,
            )
            assertEquals(
                "clip with space.mp4 size",
                Fixture.SPACE_CLIP_SIZE,
                children.named(Fixture.SPACE_CLIP).fileSize,
            )
            assertFalse(children.named(Fixture.CLIP_4MIB).isDirectory)
        }
        assertNoSimFailures("list nested folder", failures)
    }

    @Test
    fun unicodeAndSpecialCharacterNames_areListedAndNavigable() {
        val failures = simFailures(SimServers.BROWSABLE) { server ->
            val root = repo.listOrThrow(server, server.basePath)

            val japanese = repo.listOrThrow(server, root.named(Fixture.JAPANESE_DIR).path)
            assertTrue(japanese.any { it.name == Fixture.JAPANESE_FILE })

            val emoji = repo.listOrThrow(server, root.named(Fixture.EMOJI_DIR).path)
            assertTrue(emoji.any { it.name == Fixture.EMOJI_FILE })

            val special = repo.listOrThrow(server, root.named(Fixture.SPECIAL_DIR).path)
            assertTrue(special.any { it.name == Fixture.SPECIAL_FILE })
        }
        assertNoSimFailures("unicode and special names", failures)
    }

    @Test
    fun wrongPassword_isRejectedWithAuthError() {
        val failures = simFailures(SimServers.AUTH_REQUIRED) { server ->
            val wrong = server.withPassword(SimServers.WRONG_PASSWORD)
            val result = runBlocking { repo.listDirectory(wrong.config(), wrong.basePath) }
            assertTrue(
                "expected RemoteAuthException, got success=${result.isSuccess} error=${result.exceptionOrNull()}",
                result.exceptionOrNull() is RemoteAuthException,
            )
        }
        assertNoSimFailures("wrong password", failures)
    }

    /**
     * A correct login primes the browser connection pool. A later attempt with a wrong
     * password to the same host and port must still be rejected, not served from the pool.
     */
    @Test
    fun wrongPasswordAfterCorrectLogin_isStillRejected() {
        val failures = simFailures(SimServers.AUTH_REQUIRED) { server ->
            repo.listOrThrow(server, server.basePath)
            val wrong = server.withPassword(SimServers.WRONG_PASSWORD)
            val result = runBlocking { repo.listDirectory(wrong.config(), wrong.basePath) }
            assertTrue(
                "expected RemoteAuthException after a correct login, got success=${result.isSuccess} " +
                    "error=${result.exceptionOrNull()}",
                result.exceptionOrNull() is RemoteAuthException,
            )
        }
        assertNoSimFailures("wrong password after correct login", failures)
    }

    @Test
    fun unreachableServer_failsWithinOperationTimeout() {
        // RemoteBrowseRepositoryImpl caps each operation at 30 s. A silent port can take about
        // 20 s (two 10 s connect timeouts), so the check is that it fails before the cap.
        val limitMs = 30_000L
        val failures = simFailures(
            listOf(SimServers.FTP, SimServers.SFTP, SimServers.SMB, SimServers.WEBDAVS, SimServers.WEBDAV_ANON)
                .map { it.copy(port = SimServers.CLOSED_PORT) },
        ) { server ->
            val started = SystemClock.elapsedRealtime()
            val result = runBlocking { repo.listDirectory(server.config(), server.basePath) }
            val elapsed = SystemClock.elapsedRealtime() - started
            assertTrue("expected failure, got success", result.isFailure)
            assertTrue("failure took $elapsed ms (limit $limitMs ms)", elapsed < limitMs)
        }
        assertNoSimFailures("unreachable server", failures)
    }

    /**
     * WebDavBrowserClient refuses to send Basic credentials over cleartext HTTP, so a
     * credentialed HTTP share cannot be browsed even with the right password. This
     * documents that behaviour. WEBDAVS is the supported route for credentials.
     */
    @Test
    fun webDavHttpWithCredentials_browseIsRefused() {
        val server = SimServers.WEBDAV_AUTH
        val result = runBlocking { repo.listDirectory(server.config(), server.basePath) }
        assertTrue(
            "HTTP WebDAV with credentials: expected RemoteAuthException, got " +
                "success=${result.isSuccess} error=${result.exceptionOrNull()}",
            result.exceptionOrNull() is RemoteAuthException,
        )
    }

    /** The server certificate is signed by a CA the device does not trust, so the connection must fail. */
    @Test
    fun webDavsCertificateFromUntrustedCa_isRejected() {
        val server = SimServers.WEBDAVS_ROGUE
        val result = runBlocking { repo.listDirectory(server.config(), server.basePath) }
        assertTrue(
            "untrusted HTTPS certificate was accepted: success=${result.isSuccess}",
            result.isFailure,
        )
        assertTrue(
            "expected an SSL error, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is SSLException,
        )
    }

    @Test
    fun folderCounts_countSubfoldersFilesAndMedia() {
        val failures = simFailures(SimServers.BROWSABLE) { server ->
            val root = repo.listOrThrow(server, server.basePath)
            val counts = mutableMapOf<String, FolderCounts>()
            runBlocking {
                repo.enrichDirectory(server.config(), root) { path, value -> counts[path] = value }
            }

            val media = requireNotNull(counts[root.named(Fixture.MEDIA).path]) { "no counts for media" }
            assertEquals("media files", 3, media.files)
            assertEquals("media media-count", 3, media.media)
            assertEquals("media subfolders", 0, media.folders)

            val notes = requireNotNull(counts[root.named(Fixture.NOTES).path]) { "no counts for notes" }
            assertEquals("notes files", 1, notes.files)
            assertEquals("notes media-count", 0, notes.media)
        }
        assertNoSimFailures("folder counts", failures)
    }
}
