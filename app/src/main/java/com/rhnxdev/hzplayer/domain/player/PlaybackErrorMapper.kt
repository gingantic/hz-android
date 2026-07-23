package com.rhnxdev.hzplayer.domain.player

import android.util.Log
import androidx.media3.common.PlaybackException
import com.rhnxdev.hzplayer.domain.model.PlaybackErrorKind

/**
 * Maps a Media3 [PlaybackException] to a safe, user-facing error.
 *
 * The visible message is built from a localized string resource (keyed by
 * [PlaybackErrorKind]) and never embeds the raw exception text. Raw cause
 * messages routinely contain server hostnames, share paths, and — for SMB/FTP/
 * WebDAV auth failures — credentials; those are stripped before anything leaves
 * this mapper.
 *
 * No Android dependencies live here so the mapping is unit-testable in a JVM test.
 */
object PlaybackErrorMapper {

    data class MappedError(
        val kind: PlaybackErrorKind,
        /** Localized string resource name, e.g. "player_error_network". */
        val stringResName: String,
        /**
         * Optional sanitized debug fragment (host/share only, no credentials),
         * gated behind [BuildConfig.DEBUG] by the caller. Empty when nothing safe
         * to show.
         */
        val sanitizedDetail: String,
    )

    /** Convenience wrapper for the live Media3 exception. */
    fun map(error: PlaybackException): MappedError {
        Log.w(TAG, "Playback error: code=${error.errorCode}(${error.errorCodeName})", error)
        return map(error.errorCode, error.cause)
    }

    /**
     * Core mapping. Decoupled from the [PlaybackException] constructor (which touches
     * Android clocks) so it runs in plain JVM unit tests.
     */
    fun map(errorCode: Int, cause: Throwable?): MappedError {
        val errorCause = cause
        val causeBased = classifyByCause(errorCause)
        if (causeBased != null) {
            return MappedError(causeBased.first, causeBased.second, sanitizeDetail(errorCause))
        }

        val specific = when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                PlaybackErrorKind.NETWORK to "player_error_network"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                PlaybackErrorKind.TIMEOUT to "player_error_timeout"
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                PlaybackErrorKind.CLEARTEXT to "player_error_cleartext"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                PlaybackErrorKind.NETWORK to "player_error_server"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                PlaybackErrorKind.FILE_NOT_FOUND to "player_error_not_found"
            // Manifest / Container parsing errors (Media3 3001..3004)
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                PlaybackErrorKind.FORMAT_UNSUPPORTED to "player_error_format"
            // DRM errors (Media3 range 0x3000..)
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR ->
                PlaybackErrorKind.DRM to "player_error_drm"
            // Decoder / format errors
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
                PlaybackErrorKind.DECODER to "player_error_decoder"
            else -> null
        }
        if (specific != null) {
            return MappedError(specific.first, specific.second, sanitizeDetail(errorCause))
        }

        return MappedError(PlaybackErrorKind.UNKNOWN, "player_error_unknown", sanitizeDetail(errorCause))
    }

    /**
     * Walk the cause chain looking for auth / file-not-found / unsupported-format
     * hints. Returns null when nothing recognizable is found.
     */
    private fun classifyByCause(cause: Throwable?): Pair<PlaybackErrorKind, String>? {
        var c = cause
        while (c != null) {
            val msg = c.message ?: c.javaClass.simpleName
            val kind = when {
                msg.contains("401", ignoreCase = true) ||
                    msg.contains("403", ignoreCase = true) ||
                    msg.contains("auth", ignoreCase = true) ||
                    msg.contains("credential", ignoreCase = true) ||
                    msg.contains("login", ignoreCase = true) ->
                    PlaybackErrorKind.AUTH to "player_error_auth"
                msg.contains("not found", ignoreCase = true) ||
                    msg.contains("404", ignoreCase = true) ||
                    msg.contains("no such file", ignoreCase = true) ->
                    PlaybackErrorKind.FILE_NOT_FOUND to "player_error_not_found"
                msg.contains("500", ignoreCase = true) ||
                    msg.contains("502", ignoreCase = true) ||
                    msg.contains("503", ignoreCase = true) ||
                    msg.contains("504", ignoreCase = true) ||
                    msg.contains("server error", ignoreCase = true) ->
                    PlaybackErrorKind.NETWORK to "player_error_server"
                msg.contains("unsupported", ignoreCase = true) ||
                    msg.contains("no decoder", ignoreCase = true) ||
                    msg.contains("codec", ignoreCase = true) ||
                    msg.contains("#EXTM3U", ignoreCase = true) ||
                    msg.contains("manifest", ignoreCase = true) ||
                    msg.contains("playlist", ignoreCase = true) ||
                    msg.contains("ParserException", ignoreCase = true) ->
                    PlaybackErrorKind.FORMAT_UNSUPPORTED to "player_error_format"
                else -> null
            }
            if (kind != null) return kind
            c = c.cause
        }
        return null
    }

    /**
     * Extract a safe-to-show detail: host + share path only, with userinfo
     * (credentials) stripped and hostnames masked to "server". Never returns the
     * raw exception text.
     */
    private fun sanitizeDetail(cause: Throwable?): String {
        val raw = buildString {
            var c = cause
            while (c != null) {
                c.message?.let { append(it); append("\n") }
                c = c.cause
            }
        }.trim()
        if (raw.isEmpty()) return ""

        // 1. Strip userinfo from any smb/ftp/sftp/webdav URI: smb://user:pass@host/...
        //    Replace "<scheme>://<user:pass>@" with "<scheme>://" (credentials gone, host kept).
        val noCreds = USERINFO_RE.replace(raw) { mr -> "${mr.groupValues[1]}://" }

        // 2. Mask hostnames (FQDN) and IPv4 addresses — keep only the share path.
        var masked = HOSTNAME_RE.replace(noCreds) { "server" }
        masked = IPV4_RE.replace(masked) { "server" }

        // 3. Collapse whitespace and cap length so a malformed cause can't flood the UI.
        return masked.replace(Regex("\\s+"), " ").take(160)
    }

    private val USERINFO_RE = Regex("""(smb|ftp|sftp|webdavs?)://[^/@]+@""", RegexOption.IGNORE_CASE)
    private val HOSTNAME_RE = Regex("""\b[\w-]+\.[\w-]+\.[a-z]{2,}\b""", RegexOption.IGNORE_CASE)
    private val IPV4_RE = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""")

    private const val TAG = "PlaybackErrorMapper"
}
