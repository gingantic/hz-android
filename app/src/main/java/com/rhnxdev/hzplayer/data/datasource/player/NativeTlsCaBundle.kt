package com.rhnxdev.hzplayer.data.datasource.player

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * Provides a PEM CA bundle for the native FFmpeg (mbedTLS) HTTPS stack.
 *
 * FFmpeg's mbedTLS backend loads no system trust store and has no `ca_path`, only `ca_file`.
 * Android keeps its roots as a directory, so the system roots are exported once into a single
 * PEM file under `cacheDir`. mbedTLS rejects the WHOLE file when one certificate fails to
 * parse, so [isMbedtlsFriendly] drops certificates it could not parse instead.
 */
object NativeTlsCaBundle {
    private const val TAG = "NativeTlsCaBundle"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val PEM_HEAD = "-----BEGIN CERTIFICATE-----\n"
    private const val PEM_TAIL = "-----END CERTIFICATE-----\n"

    private val SIGNATURE_ALGORITHMS =
        Regex("^SHA(1|224|256|384|512)WITH(RSA|ECDSA)$|^SHA(224|256|384|512)WITHRSAANDMGF1$")
    private val PSS_ALGORITHMS = setOf("RSASSA-PSS", "1.2.840.113549.1.1.10")

    /** Critical extension OIDs mbedTLS understands; any other critical OID fails the parse. */
    private val KNOWN_CRITICAL_OIDS = setOf(
        "2.5.29.19", "2.5.29.15", "2.5.29.37", "2.5.29.17", "2.16.840.1.113730.1.1",
    )

    /**
     * Returns the absolute path of a usable PEM bundle, rebuilding it when missing, corrupt,
     * or older than 7 days.
     *
     * @param context — any context; only `cacheDir` is used
     * @return the bundle path, or null when no usable bundle could be written
     */
    @Synchronized
    fun ensure(context: Context): String? {
        val dir = File(context.cacheDir, "tls")
        val dst = File(dir, "cacert.pem")
        if (dst.isFile && isWellFormedBundle(dst) &&
            System.currentTimeMillis() - dst.lastModified() < MAX_AGE_MS
        ) {
            return dst.absolutePath
        }
        return try {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val ders = ArrayList<ByteArray>()
            var total = 0
            for (alias in store.aliases()) {
                if (!alias.startsWith("system:")) continue
                val cert = store.getCertificate(alias) as? X509Certificate ?: continue
                total++
                if (isMbedtlsFriendly(cert)) ders.add(cert.encoded)
            }
            if (ders.isEmpty()) {
                Log.w(TAG, "No usable system CA certificates (total=$total)")
                return null
            }
            dir.mkdirs()
            val tmp = File(dir, "cacert.pem.tmp")
            tmp.writeText(encodePem(ders), Charsets.US_ASCII)
            Files.move(
                tmp.toPath(), dst.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
            Log.i(TAG, "CA bundle written: ${ders.size} of $total system roots (skipped ${total - ders.size})")
            dst.absolutePath
        } catch (e: Exception) {
            // The whole keystore/export path is a platform boundary: any failure here must
            // fail closed (no bundle) rather than crash the playback open.
            Log.w(TAG, "CA bundle failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * True when [file] still carries the PEM markers at both ends.
     *
     * mbedTLS rejects the WHOLE file when one certificate fails to parse, so a truncated
     * cache entry would break every https open until it ages out.
     *
     * @param file — candidate bundle
     */
    internal fun isWellFormedBundle(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < PEM_HEAD.length + PEM_TAIL.length) {
                false
            } else {
                val head = ByteArray(PEM_HEAD.length)
                raf.seek(0)
                raf.readFully(head)
                val tail = ByteArray(PEM_TAIL.length)
                raf.seek(length - PEM_TAIL.length)
                raf.readFully(tail)
                String(head, Charsets.US_ASCII) == PEM_HEAD &&
                    String(tail, Charsets.US_ASCII) == PEM_TAIL
            }
        }
    } catch (_: IOException) {
        false
    }

    /** PEM-encodes DER certificates with 64-column lines. */
    internal fun encodePem(ders: List<ByteArray>): String {
        val encoder = Base64.getMimeEncoder(64, "\n".toByteArray())
        return buildString {
            for (der in ders) {
                append(PEM_HEAD)
                append(encoder.encodeToString(der))
                append("\n")
                append(PEM_TAIL)
            }
        }
    }

    private fun isMbedtlsFriendly(cert: X509Certificate): Boolean {
        val key = cert.publicKey
        val ecBits = (key as? ECPublicKey)?.params?.curve?.field?.fieldSize ?: 0
        return isMbedtlsFriendly(
            isCa = cert.basicConstraints >= 0,
            keyAlgorithm = key.algorithm,
            ecFieldBits = ecBits,
            sigAlgName = cert.sigAlgName,
            criticalOids = cert.criticalExtensionOIDs,
        )
    }

    /**
     * True when mbedTLS 3.6 can parse a certificate with these properties.
     *
     * @param isCa — basicConstraints CA bit (v1 roots without extensions are skipped)
     * @param keyAlgorithm — public key algorithm, `RSA` or `EC`
     * @param ecFieldBits — EC field size in bits, ignored for RSA
     * @param sigAlgName — certificate signature algorithm name or OID
     * @param criticalOids — critical extension OIDs, null when none
     */
    internal fun isMbedtlsFriendly(
        isCa: Boolean,
        keyAlgorithm: String,
        ecFieldBits: Int,
        sigAlgName: String,
        criticalOids: Set<String>?,
    ): Boolean {
        if (!isCa) return false
        val keyOk = keyAlgorithm == "RSA" ||
            (keyAlgorithm == "EC" && ecFieldBits in setOf(256, 384, 521))
        if (!keyOk) return false
        val sig = sigAlgName.uppercase()
        val sigOk = SIGNATURE_ALGORITHMS.matches(sig) || sig in PSS_ALGORITHMS
        if (!sigOk) return false
        return criticalOids.isNullOrEmpty() || KNOWN_CRITICAL_OIDS.containsAll(criticalOids)
    }
}
