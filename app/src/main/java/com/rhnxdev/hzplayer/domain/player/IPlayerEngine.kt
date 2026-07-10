package com.rhnxdev.hzplayer.domain.player

import android.net.Uri
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.DebugStats
import com.rhnxdev.hzplayer.domain.model.PlayerStateInfo
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over a media playback engine. This is the ONLY playback contract:
 * no Media3 type may cross this boundary. A second backend (libVLC, mpv, …) is
 * added by implementing this interface + binding it in [di.PlayerEngineModule].
 */
interface IPlayerEngine {

    /** Which engine this instance is. Used by the rendering seam to pick a surface. */
    val engineType: EngineType

    /** Observable playback state emitted by the engine. */
    val playbackState: StateFlow<PlayerStateInfo>

    // ── Playback control ────────────────────────────────────────

    /** Load a URI for playback. Call [resume] to start. */
    fun play(uri: String, title: String, artist: String? = null, isVideo: Boolean = false, mimeType: String? = null)

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

    // ── Subtitle / CC track selection ───────────────────────────

    /** Get the list of subtitle track names/languages. */
    fun getSubtitleTracks(): List<String>

    /** Get the index of the currently active subtitle track, or -1 if off. */
    fun getSelectedSubtitleTrack(): Int

    /** Select a subtitle track by its index in [getSubtitleTracks], or -1 to disable. */
    fun selectSubtitleTrack(index: Int)

    /**
     * Add an external subtitle file (e.g. .srt, .vtt, .ass) to the current playback.
     *
     * @param uri The content URI or file path of the subtitle file.
     * @return `true` if the subtitle was successfully added, `false` otherwise.
     */
    fun addExternalSubtitle(uri: Uri): Boolean

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
