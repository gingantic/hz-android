package com.rhnxdev.hzplayer.presentation.player

import android.net.Uri
import com.rhnxdev.hzplayer.domain.model.AspectRatioMode
import com.rhnxdev.hzplayer.domain.model.PlaybackErrorKind
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.domain.player.EngineType

import androidx.compose.runtime.Immutable

@Immutable
data class PlayerUiState(
    val currentTitle: String? = null,
    val currentArtist: String? = null,
    val currentPlaybackUri: String? = null,
    val currentArtworkUri: String? = null,
    val isVideo: Boolean = false,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    /**
     * Playback position is intentionally NOT part of this state. It changes every
     * 250 ms, so keeping it here would force the whole player UI to recompose on
     * every tick. Consume it separately via [PlayerViewModel.position] (a dedicated
     * StateFlow) so only the seek bar re-renders.
     */
    val duration: Long = 0,
    val bufferedPercentage: Int = 0,
    val playbackSpeed: Float = 1.0f,
    val shuffleMode: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val subtitleTracks: List<String> = emptyList(),
    val audioTracks: List<String> = emptyList(),
    val selectedSubtitleTrack: Int = -1,
    val selectedAudioTrack: Int = -1,
    val showControls: Boolean = true,
    val externalSubtitles: List<Pair<String, Uri>> = emptyList(),
    val subtitleDelayMs: Long = 0,
    val playerLocked: Boolean = false,
    val errorMessage: String? = null,
    val errorKind: PlaybackErrorKind? = null,
    val seekSensitivity: Float = 1.0f,
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.AUTO,
    val useSurfaceView: Boolean = true,
    val videoPlaylist: List<VideoItem> = emptyList(),
    val currentPlaylistIndex: Int = 0,
    val showPlaylistDrawer: Boolean = false,
    /**
     * Derived surface-selection hint: `true` if the player should render through a
     * `TextureView` (composited), `false` for `SurfaceView`.
     *
     * Currently driven only by DRM status (`setSecure(true)` is incompatible with
     * compositing). HDR passthrough selection was removed.
     */
    val useTextureView: Boolean = false,
    /** True if the active MediaItem declares `drmConfiguration` (Widevine L1 etc.). */
    val drmSessionActive: Boolean = false,
    val debugMode: Boolean = false,
    val debugOverlayVisible: Boolean = false,
    /**
     * True while the player is actively presenting video frames (state == READY
     * or BUFFERING and content is video).  False once the video ends, the player
     * goes idle, or an error occurs.  Used to manage the window HDR color mode:
     * the display stays in HDR/wide-gamut while this is true and reverts to SDR
     * (so the app's custom accent color renders correctly) once it flips false.
     */
    val isVideoSurfaceActive: Boolean = false,
    /** Active playback engine — drives the surface selection in [VideoPlayerScreen]. */
    val activeEngineType: EngineType = EngineType.EXO_PLAYER,
    /**
     * When resume mode is ASK and the opened media has a saved position, this holds
     * the pending resume info until the user confirms (or dismisses). Null otherwise.
     */
    val pendingResume: PendingResume? = null,
)

/** A saved playback position awaiting the user's confirmation to resume. */
data class PendingResume(
    val uri: String,
    val resumePositionMs: Long,
    val title: String,
    val isVideo: Boolean = true,
    val mimeType: String? = null,
    val artist: String? = null,
    val id: Long = 0,
)
