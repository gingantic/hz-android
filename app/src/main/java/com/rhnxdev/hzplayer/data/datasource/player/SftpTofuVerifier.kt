package com.rhnxdev.hzplayer.data.datasource.player

import android.util.Log
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.PublicKey
import java.util.Base64

/**
 * Trust-on-first-use host-key verifier for SFTP.
 *
 * Replaces SSHJ's [net.schmizz.sshj.transport.verification.PromiscuousVerifier]
 * (which accepted ANY key and enabled silent MITM). On first connect to a host
 * we accept and persist its key; on any later connect whose key differs we
 * REJECT (that is the actual MITM / key-rotation threat). State lives in an
 * app-private file as `host=algo:base64(encodedKey)` lines — no dependency on
 * SSHJ's own known-hosts parser, so the contract is explicit and testable.
 */
internal class SftpTofuVerifier(
    private val storeFile: File,
) : HostKeyVerifier {

    /** Maps hostname -> (keyAlgorithm, base64(encodedKey)). */
    private val known = mutableMapOf<String, Pair<String, String>>()

    init {
        load()
    }

    @Synchronized
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val algo = key.algorithm
        val encoded = Base64.getEncoder().encodeToString(key.encoded)
        val existing = known[hostname]
        return when {
            existing == null -> {
                // First sighting of this host — trust and record (TOFU).
                known[hostname] = algo to encoded
                persist()
                true
            }
            existing.second == encoded -> true // known + unchanged
            else -> {
                // Key changed → possible MITM or server re-key. Reject.
                Log.w(TAG, "SFTP host key mismatch for $hostname:$port — rejecting (possible MITM)")
                false
            }
        }
    }

    /**
     * SSHJ calls this to pick a key algorithm matching a known host entry before
     * negotiation, so it can offer the right one and then verify against it.
     */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        known[hostname]?.first?.let { listOf(it) } ?: emptyList()

    private fun load() {
        if (!storeFile.exists()) return
        try {
            storeFile.readLines().forEach { line ->
                val eq = line.indexOf('=')
                val colon = line.indexOf(':', eq + 1)
                if (eq > 0 && colon > eq) {
                    val host = line.substring(0, eq)
                    val algo = line.substring(eq + 1, colon)
                    val key = line.substring(colon + 1)
                    known[host] = algo to key
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load SFTP known_hosts, starting empty", e)
        }
    }

    @Synchronized
    private fun persist() {
        try {
            storeFile.parentFile?.mkdirs()
            storeFile.writeText(
                known.entries.joinToString("\n") { "${it.key}=${it.value.first}:${it.value.second}" }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist SFTP known_hosts", e)
        }
    }

    companion object {
        private const val TAG = "SftpTofuVerifier"
    }
}
