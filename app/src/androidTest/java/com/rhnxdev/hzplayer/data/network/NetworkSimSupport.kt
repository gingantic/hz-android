package com.rhnxdev.hzplayer.data.network

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.rhnxdev.hzplayer.data.datasource.player.ConnectionPool
import com.rhnxdev.hzplayer.domain.model.NetworkProtocol
import com.rhnxdev.hzplayer.domain.model.RemoteAuthException
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.domain.model.ServerConfig
import com.rhnxdev.hzplayer.domain.repository.RemoteBrowseRepository
import jcifs.smb.SmbAuthException
import java.io.File
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Assert.assertTrue

/**
 * One server the simulator exposes. The listing root is [basePath].
 *
 * Servers are started by `tools/netsim` (see its README). The emulator reaches the
 * host at [SimServers.HOST].
 */
internal data class SimServer(
    val label: String,
    val protocol: NetworkProtocol,
    val port: Int,
    val username: String,
    val password: String,
    val basePath: String,
    val host: String = SimServers.HOST,
) {
    fun config(): ServerConfig = ServerConfig(
        name = label,
        protocol = protocol,
        host = host,
        port = port,
        username = username,
        password = password,
        allowAnonymous = username.isEmpty(),
        basePath = basePath,
    )

    fun withPassword(value: String): SimServer = copy(password = value)
}

internal object SimServers {
    /** The host loopback as seen from the Android emulator. */
    const val HOST = "10.0.2.2"

    /** Test-only credentials. They match the throwaway value in tools/netsim. */
    const val USER = "hztest"
    const val PASSWORD = "HzTest-Sim-2026"
    const val WRONG_PASSWORD = "definitely-not-the-password"

    /** A port nothing listens on, so connections are refused. */
    const val CLOSED_PORT = 2199

    /**
     * FTP is reached through `adb reverse` at the emulator's own 127.0.0.1, not 10.0.2.2.
     * The emulator's user-mode network (slirp) drops single bytes from long TCP streams, which
     * corrupts any large transfer; adb's transport is byte-exact. See tools/netsim/README.md.
     */
    const val FTP_HOST = "127.0.0.1"

    val FTP = SimServer("FTP", NetworkProtocol.FTP, 2121, USER, PASSWORD, "/", FTP_HOST)
    val FTP_ANON = SimServer("FTP anonymous", NetworkProtocol.FTP, 2121, "", "", "/", FTP_HOST)
    val SFTP = SimServer("SFTP", NetworkProtocol.SFTP, 2222, USER, PASSWORD, "/srv/hz-netsim-data")

    /** Same server on a second sshd that offers no curve25519 key exchange. See wsl-setup.sh. */
    val SFTP_NO_X25519 = SimServer("SFTP (no curve25519 KEX)", NetworkProtocol.SFTP, 2223, USER, PASSWORD, "/srv/hz-netsim-data")
    val SMB = SimServer("SMB", NetworkProtocol.SMB, 1445, USER, PASSWORD, "/data")
    val WEBDAV_AUTH = SimServer("WebDAV HTTP auth", NetworkProtocol.WEBDAV, 8080, USER, PASSWORD, "/")
    val WEBDAV_ANON = SimServer("WebDAV HTTP anonymous", NetworkProtocol.WEBDAV, 8081, "", "", "/")
    val WEBDAVS = SimServer("WebDAVS", NetworkProtocol.WEBDAVS, 8443, USER, PASSWORD, "/")
    val WEBDAVS_ROGUE = SimServer("WebDAVS untrusted CA", NetworkProtocol.WEBDAVS, 8444, USER, PASSWORD, "/")

    /** Servers that accept anonymous or authenticated browsing and can list folders. */
    val BROWSABLE = listOf(FTP, FTP_ANON, SFTP, SFTP_NO_X25519, SMB, WEBDAV_ANON, WEBDAVS)

    /** Servers that refuse anonymous access, so a wrong password must be rejected. */
    val AUTH_REQUIRED = listOf(FTP, SFTP, SFTP_NO_X25519, SMB, WEBDAVS)

    /** Servers a player can open by path. Credentialed HTTP WebDAV is excluded because it is refused on purpose. */
    val PLAYABLE = listOf(FTP, FTP_ANON, SFTP, SFTP_NO_X25519, SMB, WEBDAV_ANON, WEBDAVS)
}

/** Fixture tree written by tools/netsim/netsim.py. Names must match that script. */
internal object Fixture {
    const val HELLO = "hello.txt"
    const val HELLO_TEXT = "hello from the hz network simulator\n"
    const val MEDIA = "media"
    const val NOTES = "notes"
    const val EMPTY = "empty"
    const val CLIP_4MIB = "clip-4mib.mp4"
    const val CLIP_4MIB_SIZE = 4L * 1024 * 1024
    const val SHORT_512KIB = "short-512kib.mkv"
    const val SHORT_512KIB_SIZE = 512L * 1024
    const val SPACE_CLIP = "clip with space.mp4"
    const val SPACE_CLIP_SIZE = 256L * 1024
    const val JAPANESE_DIR = "名前 (Japanese)"
    const val JAPANESE_FILE = "【テスト】ファイル.txt"
    const val EMOJI_DIR = "spaces and 🎬 emoji"
    const val EMOJI_FILE = "file with spaces.txt"
    const val SPECIAL_DIR = "special #1 100% done"
    const val SPECIAL_FILE = "Episode #1 100% done.txt"

    /** Top-level folders in the fixture tree. */
    val ROOT_FOLDERS = listOf(EMPTY, MEDIA, NOTES, JAPANESE_DIR, EMOJI_DIR, SPECIAL_DIR)

    /** Byte i equals i % 251, the same pattern tools/netsim/netsim.py writes for media files. */
    fun pattern(length: Long): ByteArray = ByteArray(length.toInt()) { (it % 251).toByte() }
}

/** Resets app-side pools so each test starts with no cached sessions or credentials. */
internal fun resetNetworkState() {
    val cache = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
    val knownHosts = File(cache, "netsim_known_hosts")
    knownHosts.delete() // trust-on-first-use is re-learned each run
    ConnectionPool.sftpKnownHostsFile = knownHosts
    ConnectionPool.releaseAll()
}

/**
 * Runs [verify] for every server and returns one line per failing server. Each
 * check runs even when an earlier one fails, so a single run reports the full matrix.
 */
internal fun simFailures(servers: List<SimServer>, verify: (SimServer) -> Unit): List<String> =
    servers.mapNotNull { server ->
        val error = runCatching { verify(server) }.exceptionOrNull()
        Log.i(TAG, if (error == null) "PASS ${server.label}" else "FAIL ${server.label}: $error")
        error?.let { "${server.label}: ${it::class.java.simpleName}: ${it.message}" }
    }

internal fun assertNoSimFailures(capability: String, failures: List<String>) {
    assertTrue(
        "$capability failed for ${failures.size} server(s):\n" + failures.joinToString("\n"),
        failures.isEmpty(),
    )
}

internal fun RemoteBrowseRepository.listOrThrow(server: SimServer, path: String): List<RemoteFileItem> =
    runBlocking { listDirectory(server.config(), path) }.getOrThrow()

internal fun List<RemoteFileItem>.named(name: String): RemoteFileItem =
    requireNotNull(firstOrNull { it.name == name }) {
        "no entry named '$name' in ${map(RemoteFileItem::name)}"
    }

/** Joins [base] and [segments] with exactly one slash between parts. Base "/" yields no leading "//". */
internal fun pathIn(base: String, vararg segments: String): String =
    (listOf(base.trimEnd('/')) + segments).joinToString("/")

/** Every exception in the cause chain, starting with this one. */
private fun Throwable.chain(): List<Throwable> = generateSequence(this) { it.cause?.takeIf { c -> c !== it } }.toList()

/** True when the error is a rejected login, whatever the protocol reports. */
internal fun Throwable.isAuthFailure(): Boolean = chain().any { e ->
    e is RemoteAuthException || e is UserAuthException || e is SmbAuthException ||
        e.message.orEmpty().let { it.contains("401") || it.contains("authentication failed", ignoreCase = true) }
}

/** True when the error says the file does not exist, whatever the protocol reports. */
internal fun Throwable.isNotFound(): Boolean = chain().any { e ->
    e.message.orEmpty().let {
        it.contains("404") || it.contains("not found", ignoreCase = true) ||
            it.contains("no such file", ignoreCase = true) || it.contains("Cannot retrieve file")
    }
}

private const val TAG = "NetSim"
