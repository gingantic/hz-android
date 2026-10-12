package com.rhnxdev.hzplayer.domain.model

enum class PlayerState {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
    ERROR,
}

/**
 * Coarse classification of a playback failure, safe to show the user.
 * Decoupled from Media3's [androidx.media3.common.PlaybackException.ERROR_CODE_*]
 * so the UI never has to inspect raw error codes or raw exception messages.
 */
enum class PlaybackErrorKind {
    NETWORK,
    TIMEOUT,
    CLEARTEXT,
    AUTH,
    FILE_NOT_FOUND,
    FORMAT_UNSUPPORTED,
    DRM,
    DECODER,
    UNKNOWN,
}

data class PlayerStateInfo(
    val state: PlayerState = PlayerState.IDLE,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val bufferedPosition: Long = 0,
    val playbackSpeed: Float = 1.0f,
    val shuffleModeEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val errorMessage: String? = null,
    val errorKind: PlaybackErrorKind? = null,
    val currentTitle: String? = null,
    val currentArtist: String? = null,
    val currentUri: String? = null,
    /** True iff current [MediaItem] declares a `drmConfiguration` (Widevine L1 path, etc.). */
    val drmSessionActive: Boolean = false,
    /**
     * True when the current session is video (as declared at [IPlayerEngine.play]).
     * Lives here — not just in a per-activity ViewModel — so any activity that did
     * not start playback (e.g. the main app's mini player when a browser/URL video
     * is playing in [VideoPlayerActivity]) can still recognize the session as video
     * and offer "reopen as video". See the engine singleton note in PlayerRepositoryImpl.
     */
    val isVideo: Boolean = false,
)
