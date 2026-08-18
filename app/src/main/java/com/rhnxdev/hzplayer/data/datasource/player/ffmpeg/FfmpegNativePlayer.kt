package com.rhnxdev.hzplayer.data.datasource.player.ffmpeg

import android.view.Surface
import androidx.annotation.Keep

/**
 * JNI wrapper for the standalone native FFmpeg player (libffplayer.so).
 * Owns the native context pointer, demuxing, decoding, and direct rendering.
 */
@Keep
class FfmpegNativePlayer(
    private val audioSink: FfmpegAudioSink = FfmpegAudioSink()
) {
    interface Listener {
        fun onVideoSizeChanged(width: Int, height: Int)
        fun onStateChanged(state: Int)
        fun onError(message: String)
        fun onPositionUpdate(positionMs: Long, durationMs: Long)
    }

    var listener: Listener? = null
    private var nativeContext: Long = 0L

    companion object {
        init {
            System.loadLibrary("ffplayer")
        }

        const val STATE_IDLE = 0
        const val STATE_BUFFERING = 1
        const val STATE_READY = 2
        const val STATE_ENDED = 3
        const val STATE_ERROR = 4
    }

    init {
        nativeContext = nativeCreate()
    }

    fun open(bridge: Any?, url: String?, surface: Surface?, startPositionMs: Long): Boolean {
        if (nativeContext == 0L) return false
        return nativeOpen(nativeContext, bridge, url, surface, startPositionMs)
    }

    fun setSurface(surface: Surface?) {
        if (nativeContext != 0L) {
            nativeSetSurface(nativeContext, surface)
        }
    }

    fun play() {
        if (nativeContext != 0L) {
            audioSink.play()
            nativePlay(nativeContext)
        }
    }

    fun pause() {
        if (nativeContext != 0L) {
            audioSink.pause()
            nativePause(nativeContext)
        }
    }

    fun seekTo(positionMs: Long) {
        if (nativeContext != 0L) {
            nativeSeek(nativeContext, positionMs)
        }
    }

    fun stop() {
        if (nativeContext != 0L) {
            audioSink.pause()
            audioSink.flush()
            nativeStop(nativeContext)
        }
    }

    fun release() {
        if (nativeContext != 0L) {
            audioSink.release()
            nativeRelease(nativeContext)
            nativeContext = 0L
        }
    }

    fun getDuration(): Long = if (nativeContext != 0L) nativeGetDuration(nativeContext) else 0L
    fun getPosition(): Long = if (nativeContext != 0L) nativeGetPosition(nativeContext) else 0L
    fun isPlaying(): Boolean = if (nativeContext != 0L) nativeIsPlaying(nativeContext) else false

    fun setSpeed(speed: Float) {
        if (nativeContext != 0L) {
            audioSink.setSpeed(speed)
            nativeSetSpeed(nativeContext, speed)
        }
    }

    fun getAudioTracks(): List<String> {
        if (nativeContext == 0L) return emptyList()
        val arr = nativeGetAudioTracks(nativeContext) ?: return emptyList()
        return arr.toList()
    }

    fun getVideoWidth(): Int = if (nativeContext != 0L) nativeGetVideoWidth(nativeContext) else 0
    fun getVideoHeight(): Int = if (nativeContext != 0L) nativeGetVideoHeight(nativeContext) else 0

    // ─── Native Callbacks ───────────────────────────────────────────────────

    @Keep
    private fun onAudioInit(sampleRate: Int, channelCount: Int) {
        audioSink.init(sampleRate, channelCount)
    }

    @Keep
    private fun onAudioData(pcm: ByteArray, size: Int): Int {
        return audioSink.write(pcm, size)
    }

    @Keep
    private fun onAudioFlush() {
        audioSink.flush()
    }

    @Keep
    private fun onVideoSizeChanged(width: Int, height: Int) {
        listener?.onVideoSizeChanged(width, height)
    }

    @Keep
    private fun onStateChanged(state: Int) {
        listener?.onStateChanged(state)
    }

    @Keep
    private fun onError(message: String) {
        listener?.onError(message)
    }

    @Keep
    private fun onPositionUpdate(positionMs: Long, durationMs: Long) {
        listener?.onPositionUpdate(positionMs, durationMs)
    }

    // ─── JNI Method Declarations ────────────────────────────────────────────

    private external fun nativeCreate(): Long
    private external fun nativeOpen(handle: Long, bridge: Any?, url: String?, surface: Surface?, startPositionMs: Long): Boolean
    private external fun nativeSetSurface(handle: Long, surface: Surface?)
    private external fun nativePlay(handle: Long)
    private external fun nativePause(handle: Long)
    private external fun nativeSeek(handle: Long, posMs: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeRelease(handle: Long)
    private external fun nativeGetDuration(handle: Long): Long
    private external fun nativeGetPosition(handle: Long): Long
    private external fun nativeIsPlaying(handle: Long): Boolean
    private external fun nativeSetSpeed(handle: Long, speed: Float)
    private external fun nativeGetAudioTracks(handle: Long): Array<String>?
    private external fun nativeGetVideoWidth(handle: Long): Int
    private external fun nativeGetVideoHeight(handle: Long): Int
}
