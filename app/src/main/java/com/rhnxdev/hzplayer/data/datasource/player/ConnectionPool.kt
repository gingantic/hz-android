package com.rhnxdev.hzplayer.data.datasource.player

import android.net.Uri
import com.rhnxdev.hzplayer.domain.model.RemoteAuthException
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool as OkHttpConnectionPool
import okhttp3.OkHttpClient
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Singleton pool of persistent connections for FTP, SFTP, SMB, and WebDAV.
 *
 * Keyed by `"scheme://host:port:user"`. For SMB, this shares the [CIFSContext]
 * across all [SmbDataSource] instances, reusing the same SMB transport session
 * and avoiding "no more connections" errors from the remote server.
 *
 * Also provides pooled connections for [RemoteBrowserClient] instances
 * to reuse the same control connection across directory listings.
 */
internal object ConnectionPool {

    /**
     * App-private file for the SFTP TOFU known-hosts store. The [Application]
     * sets this at startup (see [com.rhnxdev.hzplayer.HzPlayerApplication]);
     * until then a temp file is used as a fallback.
     */
    var sftpKnownHostsFile: File? = null

    private fun sftpHostKeyVerifier(): HostKeyVerifier =
        SftpTofuVerifier(sftpKnownHostsFile ?: File(System.getProperty("java.io.tmpdir"), "hz_sftp_known_hosts"))

    /** Idle connections older than this are evicted by the sweeper. */
    private const val IDLE_TIMEOUT_MS = 5 * 60_000L

    /**
     * Wraps a pooled value with its last-use time and a checkout count, so the
     * sweeper can evict connections that have been idle (not checked out) longer
     * than [IDLE_TIMEOUT_MS] without ever closing a connection that is still in
     * active use by a running stream.
     */
    private class Timed<V>(val value: V) {
        @Volatile var lastUsed = System.currentTimeMillis()
        @Volatile var inUse = 0
        fun acquire() { inUse = inUse + 1; lastUsed = System.currentTimeMillis() }
        fun release() { inUse = (inUse - 1).coerceAtLeast(0); lastUsed = System.currentTimeMillis() }
    }

    private fun <V> ConcurrentHashMap<String, Timed<V>>.evictIdle(close: (V) -> Unit) {
        val now = System.currentTimeMillis()
        val it = entries.iterator()
        while (it.hasNext()) {
            val (_, timed) = it.next()
            if (timed.inUse == 0 && now - timed.lastUsed > IDLE_TIMEOUT_MS) {
                try { close(timed.value) } catch (_: Exception) {}
                it.remove()
            }
        }
    }

    private val evictionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sweeperStarted = false
    private fun ensureSweeper() {
        if (sweeperStarted) return
        sweeperStarted = true
        evictionScope.launch {
            while (isActive) {
                delay(IDLE_TIMEOUT_MS / 2)
                evictIdle()
            }
        }
    }

    init { ensureSweeper() }

    /** Tear down the sweeper scope. Called from the Application's onTerminate. */
    fun shutdown() {
        evictionScope.cancel()
    }

    private fun evictIdle() {
        ftpPool.evictIdle { c -> try { c.logout() } catch (_: Exception) {}; try { c.disconnect() } catch (_: Exception) {} }
        sftpPool.evictIdle { c -> try { c.disconnect() } catch (_: Exception) {} }
        smbPool.evictIdle { ctx -> try { ctx.close() } catch (_: Exception) {} }
        webdavPool.evictIdle { c ->
            try { c.dispatcher.executorService.shutdownNow() } catch (_: Exception) {}
            try { c.connectionPool.evictAll() } catch (_: Exception) {}
        }
    }

    private val ftpPool = ConcurrentHashMap<String, Timed<FTPClient>>()
    private val sftpPool = ConcurrentHashMap<String, Timed<SSHClient>>()
    private val smbPool = ConcurrentHashMap<String, Timed<CIFSContext>>()
    private val webdavPool = ConcurrentHashMap<String, Timed<OkHttpClient>>()

    // Browser-level pooling — separate from DataSource pool to allow
    // simultaneous browse + stream connections to the same server. These are
    // removed from the map on return, so they need no idle sweeper.
    private val ftpBrowserPool = ConcurrentHashMap<String, FTPClient>()
    private val sftpBrowserPool = ConcurrentHashMap<String, SSHClient>()
    private val smbBrowserPool = ConcurrentHashMap<String, CIFSContext>()
    private val webdavBrowserPool = ConcurrentHashMap<String, OkHttpClient>()

    // ponytail: internal so the borrow/return key contract is unit-testable
    // without a live SMB server (guards the sdk-brw pass-mismatch leak fix).
    internal fun key(scheme: String, host: String, port: Int, user: String, pass: String): String {
        val passHash = pass.let { p ->
            try {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                md.digest(p.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
            } catch (_: Exception) { p.length.toString() }
        }
        return "$scheme://$host:$port:$user:$passHash"
    }

    private fun browserKey(host: String, port: Int, scheme: String): String =
        "$scheme://$host:$port"

    // ── FTP (DataSource) ───────────────────────────────────────────

    /** Borrow a reusable [FTPClient] for the given server. */
    fun borrowFtp(host: String, port: Int, user: String, pass: String): FTPClient {
        val k = key("ftp", host, port, user, pass)
        val existing = ftpPool[k]
        // Reuse a pooled connection only when it is connected AND idle (available).
        // The old predicate reused a busy (in-transfer) connection and let a fresh
        // idle one fall through to disconnect()+new, defeating FTP pooling.
        if (existing != null && existing.value.isConnected && existing.value.isAvailable) {
            existing.acquire()
            return existing.value
        }
        existing?.let {
            try { it.value.logout() } catch (_: Exception) {}
            try { it.value.disconnect() } catch (_: Exception) {}
        }
        val ftp = FTPClient().apply {
            autodetectUTF8 = true // negotiate UTF-8 from server FEAT — emoji/CJK filenames
            connectTimeout = 15000
            defaultTimeout = 15000
            dataTimeout = java.time.Duration.ofMillis(15000)
            connect(host, port)
            login(user, pass)
            enterLocalPassiveMode()
            setFileType(FTP.BINARY_FILE_TYPE)
        }
        ftpPool[k] = Timed(ftp).also { it.acquire() }
        android.util.Log.d("ConnectionPool", "FTP new connection $k")
        return ftp
    }

    fun returnFtp(host: String, port: Int, user: String, pass: String) {
        ftpPool[key("ftp", host, port, user, pass)]?.release() // keep alive; sweeper evicts when idle
    }

    // ── SFTP (DataSource) ──────────────────────────────────────────

    /** Borrow a reusable [SSHClient] for the given server. */
    fun borrowSsh(host: String, port: Int, user: String, pass: String): SSHClient {
        val k = key("sftp", host, port, user, pass)
        val existing = sftpPool[k]
        if (existing != null && existing.value.isConnected && existing.value.isAuthenticated) {
            existing.acquire()
            return existing.value
        }
        existing?.let {
            try { it.value.disconnect() } catch (_: Exception) {}
        }
        val ssh = SSHClient().apply {
            addHostKeyVerifier(sftpHostKeyVerifier())
            connectTimeout = 15000
            connect(host, port)
            authPassword(user, pass)
        }
        sftpPool[k] = Timed(ssh).also { it.acquire() }
        android.util.Log.d("ConnectionPool", "SFTP new connection $k")
        return ssh
    }

    fun returnSsh(host: String, port: Int, user: String, pass: String) {
        sftpPool[key("sftp", host, port, user, pass)]?.release() // keep alive; sweeper evicts when idle
    }

    // ── SMB (DataSource) ──────────────────────────────────────────

    /**
     * Force-close + drop a pooled [CIFSContext] for [host]. Called when a borrow
     * turns out to be a stale session (network drop / SMB timeout): the next
     * borrow rebuilds a fresh context instead of reusing the dead one. jcifs-ng
     * has no `isConnected`, so staleness only surfaces on the next I/O — we
     * can't probe cheaply, so we drop on the first failure that signals it.
     */
    fun dropSmbContext(host: String, port: Int, user: String, pass: String) {
        val k = key("smb", host, port, user, pass)
        smbPool.remove(k)?.let { timed ->
            try { timed.value.close() } catch (_: Exception) {}
        }
    }

    /** Borrow (or create) a shared [CIFSContext] for the given server. */
    fun borrowSmbContext(host: String, port: Int, user: String, pass: String): CIFSContext {
        val k = key("smb", host, port, user, pass)
        return smbPool.getOrPut(k) { Timed(newSmbContext(host, user, pass, 15000, 15000, k)) }
            .also { it.acquire() }.value
    }

    /** Borrow (or create) a shared [CIFSContext] for remote SMB thumbnails with tight timeouts. */
    fun borrowSmbThumbnailContext(host: String, port: Int, user: String, pass: String): CIFSContext {
        val k = key("smb_thumb", host, port, user, pass)
        return smbPool.getOrPut(k) { Timed(newSmbContext(host, user, pass, 10000, 10000, k)) }
            .also { it.acquire() }.value
    }

    fun returnSmbThumbnailContext(host: String, port: Int, user: String, pass: String) {
        smbPool[key("smb_thumb", host, port, user, pass)]?.release() // keep alive; sweeper evicts when idle
    }

    // ── WebDAV (DataSource) ──────────────────────────────────────

    /** Borrow a reusable [OkHttpClient] for WebDAV streaming. */
    fun borrowWebDavClient(
        host: String,
        port: Int,
        useTls: Boolean,
        user: String,
        pass: String,
    ): OkHttpClient {
        val k = webdavKey(host, port, useTls, user)
        return webdavPool.getOrPut(k) {
            Timed(
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    // Bounded idle socket eviction at the HTTP layer so pooled
                    // clients don't hold open sockets for the app lifetime.
                    .connectionPool(OkHttpConnectionPool(8, 5, TimeUnit.MINUTES))
                    .build(),
            ).also { it.acquire() }
        }.value
    }

    fun returnWebDavClient(host: String, port: Int, useTls: Boolean, user: String, pass: String) {
        webdavPool[webdavKey(host, port, useTls, user)]?.release() // keep alive; sweeper evicts when idle
    }

    // ── Browser-level pooling ─────────────────────────────────────

    fun borrowFtpBrowser(host: String, port: Int, user: String, pass: String): FTPClient {
        val k = browserKey(host, port, "ftp")
        return ftpBrowserPool.getOrPut(k) {
            FTPClient().apply {
                autodetectUTF8 = true // negotiate UTF-8 from server FEAT — emoji/CJK filenames
                connectTimeout = 10000
                defaultTimeout = 10000
                connect(host, port)
                // login() returns false on bad credentials without throwing — check it,
                // else getOrPut caches a connected-but-unauthenticated client.
                if (!login(user.ifEmpty { "anonymous" }, pass.ifEmpty { "" })) {
                    try { disconnect() } catch (_: Exception) {}
                    throw RemoteAuthException()
                }
                enterLocalPassiveMode()
                setFileType(FTP.BINARY_FILE_TYPE)
            }.also { android.util.Log.d("ConnectionPool", "FTP browser new $k") }
        }.also { android.util.Log.d("ConnectionPool", "FTP browser reuse $k") }
    }

    fun returnFtpBrowser(host: String, port: Int) {
        val k = browserKey(host, port, "ftp")
        ftpBrowserPool[k]?.let {
            try {
                if (it.isConnected) {
                    it.logout()
                    it.disconnect()
                }
            } catch (_: Exception) {}
        }
        ftpBrowserPool.remove(k)
    }

    fun borrowSftpBrowser(host: String, port: Int, user: String, pass: String): SSHClient {
        val k = browserKey(host, port, "sftp")
        return sftpBrowserPool.getOrPut(k) {
            SSHClient().apply {
                addHostKeyVerifier(sftpHostKeyVerifier())
                connectTimeout = 10000
                connect(host, port)
                try {
                    authPassword(user, pass)
                } catch (e: net.schmizz.sshj.userauth.UserAuthException) {
                    try { disconnect() } catch (_: Exception) {}
                    throw RemoteAuthException()
                }
            }.also { android.util.Log.d("ConnectionPool", "SFTP browser new $k") }
        }
    }

    fun returnSftpBrowser(host: String, port: Int) {
        val k = browserKey(host, port, "sftp")
        sftpBrowserPool[k]?.let {
            try { it.disconnect() } catch (_: Exception) {}
        }
        sftpBrowserPool.remove(k)
    }

    fun borrowSmbBrowser(host: String, port: Int, user: String, pass: String): CIFSContext {
        val k = key("smb-brw", host, port, user, pass)
        return smbBrowserPool.getOrPut(k) { newSmbContext(host, user, pass, 10000, 10000, k) }
    }

    /**
     * Build a [CIFSContext] with the shared jcifs property set, varying only the
     * response/socket timeouts. Auth uses the given credentials, or guest access
     * when the username is empty. Used by all three SMB borrow paths.
     */
    private fun newSmbContext(
        host: String,
        user: String,
        pass: String,
        responseTimeout: Int,
        soTimeout: Int,
        k: String,
    ): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", responseTimeout.toString())
            setProperty("jcifs.smb.client.soTimeout", soTimeout.toString())
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            setProperty("jcifs.smb.client.signingEnforced", "true")
            setProperty("jcifs.resolveOrder", "DNS")
            setProperty("jcifs.encoding", "UTF-8")
        }
        val base = BaseContext(PropertyConfiguration(props))
        val ctx = if (user.isNotEmpty()) {
            base.withCredentials(NtlmPasswordAuthenticator("", user, pass))
        } else {
            base.withGuestCrendentials()
        }
        android.util.Log.d("ConnectionPool", "SMB new context $k")
        return ctx
    }

    // ponytail: borrow keys with the real password (line 329), so return must match
    // it exactly or the CIFSContext is never closed and leaks for the process lifetime.
    fun returnSmbBrowser(host: String, port: Int, user: String, pass: String) {
        val k = key("smb-brw", host, port, user, pass)
        smbBrowserPool.remove(k)?.let { ctx ->
            try { ctx.close() } catch (_: Exception) {}
        }
    }

    // ── WebDAV (Browser) ────────────────────────────────────────

    fun borrowWebDavBrowser(host: String, port: Int, useTls: Boolean): OkHttpClient {
        val k = webdavBrowserKey(host, port, useTls)
        return webdavBrowserPool.getOrPut(k) {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
                .also { android.util.Log.d("ConnectionPool", "WebDAV browser new $k") }
        }
    }

    fun returnWebDavBrowser(host: String, port: Int, useTls: Boolean) {
        val k = webdavBrowserKey(host, port, useTls)
        webdavBrowserPool.remove(k)
    }

    // ── WebDAV keys ─────────────────────────────────────────────
    // WebDAV uses OkHttp which is stateless (auth goes in headers),
    // so browser and data-source clients share the same key space.

    private fun webdavKey(host: String, port: Int, useTls: Boolean, user: String): String =
        "webdav${if (useTls) "s" else ""}://$host:$port:$user"

    private fun webdavBrowserKey(host: String, port: Int, useTls: Boolean): String =
        "webdav${if (useTls) "s" else ""}://$host:$port"

    // ── Cleanup ─────────────────────────────────────────────────

    /** Release all pooled connections (call from application onDestroy). */
    fun releaseAll() {
        ftpPool.values.forEach {
            try { it.value.logout() } catch (_: Exception) {}
            try { it.value.disconnect() } catch (_: Exception) {}
        }
        ftpPool.clear()
        ftpBrowserPool.values.forEach {
            try { it.logout() } catch (_: Exception) {}
            try { it.disconnect() } catch (_: Exception) {}
        }
        ftpBrowserPool.clear()

        sftpPool.values.forEach {
            try { it.value.disconnect() } catch (_: Exception) {}
        }
        sftpPool.clear()
        sftpBrowserPool.values.forEach {
            try { it.disconnect() } catch (_: Exception) {}
        }
        sftpBrowserPool.clear()

        smbPool.clear()
        smbBrowserPool.clear()
        SmbPathResolver.clearCache()

        webdavPool.values.forEach {
            try { it.value.dispatcher.executorService.shutdownNow() } catch (_: Exception) {}
            try { it.value.connectionPool.evictAll() } catch (_: Exception) {}
        }
        webdavPool.clear()
        webdavBrowserPool.values.forEach {
            try { it.dispatcher.executorService.shutdownNow() } catch (_: Exception) {}
            try { it.connectionPool.evictAll() } catch (_: Exception) {}
        }
        webdavBrowserPool.clear()
    }

}
