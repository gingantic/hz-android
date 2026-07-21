package com.rhnxdev.hzplayer.domain.player

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.media3.common.Player
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.DebugStats
import com.rhnxdev.hzplayer.domain.model.DecoderMode
import com.rhnxdev.hzplayer.domain.model.PlayerStateInfo
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over a media playback engine. This is the ONLY playback contract:
 * no Media3 type crosses the boundary for playback logic or state. The two
 * MediaSession-integration methods ([getMedia3Player], [setOnPlayerReplacedListener])
 * are the sole, deliberate exception — they hand the underlying Media3 [Player]
 * to the system MediaSession for lock-screen controls. Non-Media3 backends leave
 * both as their `null`/no-op defaults and simply opt out. A second backend
 * (libVLC, mpv, …) is added by implementing this interface + binding it in
 * [di.PlayerEngineModule].
 */
interface IPlayerEngine {

    /** Which engine this instance is. Used by the rendering seam to pick a surface. */
    val engineType: EngineType

    /** Observable playback state emitted by the engine. */
    val playbackState: StateFlow<PlayerStateInfo>

    // ── Playback control ────────────────────────────────────────

    /** Load a URI for playback. Call [resume] to start.
     *  @param resumePositionMs if > 0, seek to this position right after prepare
     *         (used to resume where the user left off). The engine applies the
     *         seek itself once the media item is set, avoiding a race with load.
     *  @param headers HTTP request headers (e.g. `Authorization` / a stream token
     *         forwarded from a VIEW intent) applied to network requests for this
     *         URI. An empty map clears any previously applied headers. */
    fun play(uri: String, title: String, artist: String? = null, isVideo: Boolean = false, mimeType: String? = null, resumePositionMs: Long = 0, headers: Map<String, String> = emptyMap())

    /** Load a playlist (video) and start at [startIndex] / [startPositionMs]. */
    fun playPlaylist(items: List<Pair<String, String>>, startIndex: Int = 0, startPositionMs: Long = 0)

    /** Load a playlist (audio) and start at [startIndex]. */
    fun playAudioPlaylist(items: List<AudioItem>, startIndex: Int = 0)

    /** Pause the active playback. */
    fun pause()

    /** Resume from paused state (no-op if already playing). */
    fun resume()

    /** Stop playback and reset. */
    fun stop()

    // ── Seek ────────────────────────────────────────────────────

    /** Seek to an absolute position in milliseconds. */
    fun seekTo(positionMs: Long)

    /** Skip forward by [ms] milliseconds. */
    fun skipForward(ms: Long = 10000)

    /** Skip backward by [ms] milliseconds. */
    fun skipBackward(ms: Long = 10000)

    /** Advance to the next media item in the current playlist. */
    fun skipToNext()

    /** Return to the previous media item in the current playlist. */
    fun skipToPrevious()

    /** Index of the currently active media item, or 0. */
    fun getCurrentMediaItemIndex(): Int

    /** Total number of media items in the current playlist, or 0. */
    fun getMediaItemCount(): Int

    /** Jump to the media item at [index] in the current playlist (starts from 0 ms). */
    fun seekToMediaItem(index: Int)

    // ── Queries ─────────────────────────────────────────────────

    /** Whether the engine is currently playing. */
    fun isPlaying(): Boolean

    /** Current media duration in ms, or 0 if unknown. */
    fun getDuration(): Long

    /** Current playback position in ms. */
    fun getCurrentPosition(): Long

    /** Currently buffered position in ms, or 0 if unknown. */
    fun getBufferedPosition(): Long

    // ── Configuration ───────────────────────────────────────────

    /** Set playback speed (1.0 = normal). */
    fun setPlaybackSpeed(speed: Float)

    /** Enable/disable shuffle for the current playlist. */
    fun setShuffleEnabled(enabled: Boolean)

    /** Set the playlist repeat mode. */
    fun setRepeatMode(mode: RepeatMode)

    /** Current shuffle state (default false). */
    fun isShuffleEnabled(): Boolean = false

    /** Current repeat mode (default [RepeatMode.NONE]). */
    fun getRepeatMode(): RepeatMode = RepeatMode.NONE

    /** Select the decoder implementation (e.g. Media3 software vs hardware).
     *  Default no-op — only engines with selectable decoders override it. */
    fun setDecoderMode(mode: com.rhnxdev.hzplayer.domain.model.DecoderMode) {}

    // ── Subtitle / CC track selection ───────────────────────────

    /** Get the list of subtitle track names/languages. */
    fun getSubtitleTracks(): List<String>

    /** Get the index of the currently active subtitle track, or -1 if off. */
    fun getSelectedSubtitleTrack(): Int

    /** Select a subtitle track by its index in [getSubtitleTracks], or -1 to disable. */
    fun selectSubtitleTrack(index: Int)

    /** Load an external `.ass`/`.ssa` file into libass (bypasses ExoPlayer parsing). */
    fun loadExternalAss(uri: android.net.Uri)

    /**
     * Per-track sample MIME type, aligned 1:1 with [getSubtitleTracks] indices.
     * Used to detect embedded ASS/SSA tracks that should route to libass instead
     * of the built-in text renderer. Default empty (engine opts out).
     */
    fun getSubtitleTrackMimeTypes(): List<String?> = emptyList()

    /**
     * Add an external subtitle file (e.g. .srt, .vtt, .ass) to the current playback.
     *
     * @param uri The content URI or file path of the subtitle file.
     * @return `true` if the subtitle was successfully added, `false` otherwise.
     */
    fun addExternalSubtitle(uri: Uri): Boolean

    /**
     * Fired when the subtitle track list changes (embedded tracks via ExoPlayer,
     * external tracks via libass). The UI observes this to refresh its merged
     * track list. Default no-op.
     */
    var subtitleTrackChangeListener: (() -> Unit)?

    /** Set subtitle timing offset in milliseconds (positive = later, negative = earlier). */
    fun setSubtitleDelay(delayMs: Long)

    /** Get current subtitle timing offset in milliseconds, or 0 if unset. */
    fun getSubtitleDelay(): Long = 0

    // ── Audio track selection ──────────────────────────────────

    /** Get the list of audio track names/languages. */
    fun getAudioTracks(): List<String>

    /** Get the index of the currently active audio track, or -1 if none. */
    fun getSelectedAudioTrack(): Int

    /** Select an audio track by its index in [getAudioTracks], or -1 to disable. */
    fun selectAudioTrack(index: Int)

    // ── Engine-specific extras ──────────────────────────────────

    /**
     * Engine debug stats for the "stats for nerds" overlay, or `null` if the
     * engine cannot produce them. Defaults to `null` so engines need not implement.
     */
    fun getDebugStats(): DebugStats? = null

    // ── Render seam ─────────────────────────────────────────────
    // These bridge the engine's native view to the Compose surface. They are part
    // of the contract (not per-engine casts) so a second backend implements them
    // and the rendering code stays engine-agnostic. Non-Media3 engines MUST
    // override all four; there is no safe default for a native view.

    /** Create the engine's native render [View] (SurfaceView or TextureView). */
    fun createRenderView(context: Context, useSurfaceView: Boolean): View

    /** Push config (aspect ratio, subtitle style) into the render [view]. */
    fun updateRenderView(view: View, config: RenderViewConfig)

    /** Surface paused — release/hold the underlying view (e.g. PlayerView.onPause). */
    fun onRenderViewPaused(view: View)

    /** Surface resumed — reattach the underlying view (e.g. PlayerView.onResume). */
    fun onRenderViewResumed(view: View)

    /**
     * The Media3 [Player] to wrap in a system MediaSession for lock-screen / media
     * controls, or `null` if this engine cannot back one. Defaults to `null` so a
     * non-Media3 backend simply opts out and the service skips the MediaSession.
     */
    fun getMedia3Player(): Player? = null

    /**
     * Register a callback fired when the engine swaps its underlying [Player]
     * (e.g. a decoder-mode rebuild). The system MediaSession must re-point at the
     * new player; otherwise lock-screen controls die after the swap. No-op for
     * engines that never replace their player.
     */
    fun setOnPlayerReplacedListener(listener: ((Player) -> Unit)?) {}

    // ── Lifecycle ───────────────────────────────────────────────

    /** Clear current playback error. */
    fun clearError()

    /**
     * Re-attempt the last playback after a recoverable error (network/timeout/auth/
     * file-not-found). No-op if there is no current media item to retry.
     */
    fun retry()

    /** Release all native and framework resources. */
    fun release()
}
