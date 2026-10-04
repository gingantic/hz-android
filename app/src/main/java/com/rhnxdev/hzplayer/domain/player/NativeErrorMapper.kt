package com.rhnxdev.hzplayer.domain.player

import com.rhnxdev.hzplayer.domain.model.PlaybackErrorKind

/**
 * Error classes reported by `libffplayer.so` (`nativeGetLastErrorClass`).
 *
 * The numbers mirror `NativeErrorClass` in `app/src/main/cpp/ffplayer/NetworkIo.h`.
 */
object NativeErrorClass {
    const val NONE = 0
    const val NETWORK = 1
    const val TIMEOUT = 2
    const val AUTH = 3
    const val NOT_FOUND = 4
    const val SERVER = 5
    const val FORMAT = 6
    const val UNKNOWN = 8
}

/**
 * Maps a native error class to the same user-facing errors ExoPlayer shows, so raw FFmpeg
 * text (which can contain URLs) never reaches the UI for web sources.
 */
object NativeErrorMapper {

    /** Shown for `.mpd` manifests: the native build has no DASH demuxer. */
    val DASH_UNSUPPORTED = PlaybackErrorMapper.MappedError(
        PlaybackErrorKind.FORMAT_UNSUPPORTED,
        "player_error_dash_native",
        "",
    )

    /**
     * @param errorClass — a [NativeErrorClass] value
     * @return the mapped error, or null for [NativeErrorClass.NONE]
     */
    fun map(errorClass: Int): PlaybackErrorMapper.MappedError? = when (errorClass) {
        NativeErrorClass.NONE -> null
        NativeErrorClass.NETWORK -> mapped(PlaybackErrorKind.NETWORK, "player_error_network")
        NativeErrorClass.TIMEOUT -> mapped(PlaybackErrorKind.TIMEOUT, "player_error_timeout")
        NativeErrorClass.AUTH -> mapped(PlaybackErrorKind.AUTH, "player_error_auth")
        NativeErrorClass.NOT_FOUND -> mapped(PlaybackErrorKind.FILE_NOT_FOUND, "player_error_not_found")
        NativeErrorClass.SERVER -> mapped(PlaybackErrorKind.NETWORK, "player_error_server")
        NativeErrorClass.FORMAT -> mapped(PlaybackErrorKind.FORMAT_UNSUPPORTED, "player_error_format")
        else -> mapped(PlaybackErrorKind.UNKNOWN, "player_error_unknown")
    }

    private fun mapped(kind: PlaybackErrorKind, resName: String) =
        PlaybackErrorMapper.MappedError(kind, resName, "")
}

/**
 * True when [uri] or [mimeType] identifies a DASH manifest.
 *
 * @param uri — media URI; only the path before `?`/`#` is inspected
 * @param mimeType — optional MIME type hint
 */
fun isDashManifest(uri: String, mimeType: String?): Boolean {
    val path = uri.substringBefore('#').substringBefore('?')
    return path.endsWith(".mpd", ignoreCase = true) ||
        mimeType?.lowercase()?.contains("dash") == true
}
