package com.rhnxdev.hzplayer.domain.player

import android.net.Uri
import com.rhnxdev.hzplayer.domain.model.PlayerStateInfo
import kotlinx.coroutines.flow.StateFlow

/** Abstraction over a media playback engine (only ExoPlayer). */
interface IPlayerEngine {

    /** Observable playback state emitted by the engine. */
    val playbackState: StateFlow<PlayerStateInfo>

    // ── Playback control ────────────────────────────────────────

    /** Load a URI for playback. Call [resume] to start. */
    fun play(uri: String, title: String, isVideo: Boolean = false)

    /** Pause the active playback. */
    fun pause()

    /** Resume from paused state (no-op if already playing). */
    fun resume()

    /** Stop playback and reset. */
    fun stop()

    /** Seek to an absolute position in milliseconds. */
    fun seekTo(positionMs: Long)

    /** Skip forward by [ms] milliseconds. */
    fun skipForward(ms: Long = 10000)

    /** Skip backward by [ms] milliseconds. */
    fun skipBackward(ms: Long = 10000)

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
    fun setSubtitleDelay(delayMs: Long) {}

    /** Get current subtitle timing offset in milliseconds, or 0 if unset. */
    fun getSubtitleDelay(): Long = 0

    // ── Audio track selection ──────────────────────────────────

    /** Get the list of audio track names/languages. */
    fun getAudioTracks(): List<String>

    /** Get the index of the currently active audio track, or -1 if none. */
    fun getSelectedAudioTrack(): Int

    /** Select an audio track by its index in [getAudioTracks], or -1 to disable. */
    fun selectAudioTrack(index: Int)

    // ── Lifecycle ───────────────────────────────────────────────

    /** Release all native and framework resources. */
    fun release()
}
