package com.rhnxdev.hzplayer.data.datasource.player

import android.net.Uri
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
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

    private val ftpPool = ConcurrentHashMap<String, PooledFtpConnection>()
    private val sftpPool = ConcurrentHashMap<String, PooledSshConnection>()
    private val smbPool = ConcurrentHashMap<String, CIFSContext>()
    private val webdavPool = ConcurrentHashMap<String, OkHttpClient>()

    // Browser-level pooling — separate from DataSource pool to allow
    // simultaneous browse + stream connections to the same server.
    private val ftpBrowserPool = ConcurrentHashMap<String, FTPClient>()
    private val sftpBrowserPool = ConcurrentHashMap<String, SSHClient>()
    private val smbBrowserPool = ConcurrentHashMap<String, CIFSContext>()
    private val webdavBrowserPool = ConcurrentHashMap<String, OkHttpClient>()

    private fun key(scheme: String, host: String, port: Int, user: String, pass: String): String {
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
        if (existing != null && existing.client.isConnected && !existing.client.isAvailable) {
            return existing.client
        }
        existing?.let {
            try { it.client.logout() } catch (_: Exception) {}
            try { it.client.disconnect() } catch (_: Exception) {}
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
        ftpPool[k] = PooledFtpConnection(ftp)
        android.util.Log.d("ConnectionPool", "FTP new connection $k")
        return ftp
    }

    fun returnFtp(host: String, port: Int, user: String) {} // keep alive

    // ── SFTP (DataSource) ──────────────────────────────────────────

    /** Borrow a reusable [SSHClient] for the given server. */
    fun borrowSsh(host: String, port: Int, user: String, pass: String): SSHClient {
        val k = key("sftp", host, port, user, pass)
        val existing = sftpPool[k]
        if (existing != null && existing.client.isConnected && existing.client.isAuthenticated) {
            return existing.client
        }
        existing?.let {
            try { it.client.disconnect() } catch (_: Exception) {}
        }
        val ssh = SSHClient().apply {
            addHostKeyVerifier(PromiscuousVerifier())
            connectTimeout = 15000
            connect(host, port)
            authPassword(user, pass)
        }
        sftpPool[k] = PooledSshConnection(ssh)
        android.util.Log.d("ConnectionPool", "SFTP new connection $k")
        return ssh
    }

    fun returnSsh(host: String, port: Int, user: String) {} // keep alive

    // ── SMB (DataSource) ──────────────────────────────────────────

    /** Borrow (or create) a shared [CIFSContext] for the given server. */
    fun borrowSmbContext(host: String, port: Int, user: String, pass: String): CIFSContext {
        val k = key("smb", host, port, user, pass)
        return smbPool.getOrPut(k) {
            val props = Properties().apply {
                setProperty("jcifs.smb.client.minVersion", "SMB202")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
                setProperty("jcifs.smb.client.responseTimeout", "15000")
                setProperty("jcifs.smb.client.soTimeout", "15000")
                setProperty("jcifs.smb.client.dfs.disabled", "true")
                setProperty("jcifs.smb.client.signingEnforced", "true")
                setProperty("jcifs.resolveOrder", "DNS")
                setProperty("jcifs.encoding", "UTF-8")
            }
            val base = BaseContext(PropertyConfiguration(props))
            val ctx = if (user.isNotEmpty()) {
                val auth = NtlmPasswordAuthenticator("", user, pass)
                base.withCredentials(auth)
            } else {
                base.withGuestCrendentials()
            }
            android.util.Log.d("ConnectionPool", "SMB new context $k")
            ctx
        }
    }

    /** Borrow (or create) a shared [CIFSContext] for remote SMB thumbnails with tight timeouts. */
    fun borrowSmbThumbnailContext(host: String, port: Int, user: String, pass: String): CIFSContext {
        val k = key("smb_thumb", host, port, user, pass)
        return smbPool.getOrPut(k) {
            val props = Properties().apply {
                setProperty("jcifs.smb.client.minVersion", "SMB202")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
                setProperty("jcifs.smb.client.responseTimeout", "10000") // 10 seconds timeout for thumbnails
                setProperty("jcifs.smb.client.soTimeout", "10000")       // 10 seconds socket timeout for thumbnails
                setProperty("jcifs.smb.client.dfs.disabled", "true")
                setProperty("jcifs.smb.client.signingEnforced", "true")
                setProperty("jcifs.resolveOrder", "DNS")
                setProperty("jcifs.encoding", "UTF-8")
            }
            val base = BaseContext(PropertyConfiguration(props))
            val ctx = if (user.isNotEmpty()) {
                val auth = NtlmPasswordAuthenticator("", user, pass)
                base.withCredentials(auth)
            } else {
                base.withGuestCrendentials()
            }
            android.util.Log.d("ConnectionPool", "SMB new thumbnail context $k")
            ctx
        }
    }

    fun returnSmbContext(host: String, port: Int, user: String) {} // keep alive

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
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
                .also { android.util.Log.d("ConnectionPool", "WebDAV new client $k") }
        }
    }

    fun returnWebDavClient(host: String, port: Int, useTls: Boolean) {} // keep alive

    // ── Browser-level pooling ─────────────────────────────────────

    fun borrowFtpBrowser(host: String, port: Int, user: String, pass: String): FTPClient {
        val k = browserKey(host, port, "ftp")
        return ftpBrowserPool.getOrPut(k) {
            FTPClient().apply {
                autodetectUTF8 = true // negotiate UTF-8 from server FEAT — emoji/CJK filenames
                connectTimeout = 10000
                defaultTimeout = 10000
                connect(host, port)
                login(user.ifEmpty { "anonymous" }, pass.ifEmpty { "" })
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
                addHostKeyVerifier(PromiscuousVerifier())
                connectTimeout = 10000
                connect(host, port)
                authPassword(user, pass)
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
        return smbBrowserPool.getOrPut(k) {
            val props = Properties().apply {
                setProperty("jcifs.smb.client.minVersion", "SMB202")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
                setProperty("jcifs.smb.client.responseTimeout", "10000")
                setProperty("jcifs.smb.client.soTimeout", "10000")
                setProperty("jcifs.smb.client.dfs.disabled", "true")
                setProperty("jcifs.smb.client.signingEnforced", "true")
                setProperty("jcifs.resolveOrder", "DNS")
                setProperty("jcifs.encoding", "UTF-8")
            }
            val base = BaseContext(PropertyConfiguration(props))
            if (user.isNotEmpty()) {
                val auth = NtlmPasswordAuthenticator("", user, pass)
                base.withCredentials(auth)
            } else {
                base.withGuestCrendentials()
            }
        }
    }

    fun returnSmbBrowser(host: String, port: Int, user: String) {
        val k = key("smb-brw", host, port, user, "")
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
            try { it.client.logout() } catch (_: Exception) {}
            try { it.client.disconnect() } catch (_: Exception) {}
        }
        ftpPool.clear()
        ftpBrowserPool.values.forEach {
            try { it.logout() } catch (_: Exception) {}
            try { it.disconnect() } catch (_: Exception) {}
        }
        ftpBrowserPool.clear()

        sftpPool.values.forEach {
            try { it.client.disconnect() } catch (_: Exception) {}
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
            try { it.dispatcher.executorService.shutdownNow() } catch (_: Exception) {}
            try { it.connectionPool.evictAll() } catch (_: Exception) {}
        }
        webdavPool.clear()
        webdavBrowserPool.values.forEach {
            try { it.dispatcher.executorService.shutdownNow() } catch (_: Exception) {}
            try { it.connectionPool.evictAll() } catch (_: Exception) {}
        }
        webdavBrowserPool.clear()
    }

    private class PooledFtpConnection(val client: FTPClient)
    private class PooledSshConnection(val client: SSHClient)
}
