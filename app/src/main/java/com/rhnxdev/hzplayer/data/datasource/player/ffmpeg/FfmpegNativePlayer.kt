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
        fun onVideoSizeChanged(width: Int, height: Int, rotationDegrees: Int = 0, sarNum: Int = 1, sarDen: Int = 1)
        fun onStateChanged(state: Int)
        fun onError(message: String)
        fun onPositionUpdate(positionMs: Long, durationMs: Long)
        fun onSubtitleHeader(trackId: Int, header: ByteArray, title: String) {}
        fun onSubtitleData(trackId: Int, timeUs: Long, durationUs: Long, data: ByteArray) {}
        fun onBitmapSubtitle(trackId: Int, startPtsUs: Long, endPtsUs: Long, x: Int, y: Int, w: Int, h: Int, argb: IntArray?, canvasW: Int, canvasH: Int) {}
        fun onFontAttachment(name: String, data: ByteArray) {}
        fun onFrameRendered(ptsUs: Long) {}
    }

    var listener: Listener? = null
    var onAudioSessionId: ((Int) -> Unit)? = null
    private var nativeContext: Long = 0L

    companion object {
        init {
            try {
                System.loadLibrary("dav1d")
            } catch (t: Throwable) {
                android.util.Log.w("FfmpegNativePlayer", "Failed to load libdav1d: ${t.message}")
            }
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
        audioSink.onAudioSessionId = { sessionId ->
            onAudioSessionId?.invoke(sessionId)
        }
    }

    fun open(
        bridge: Any?,
        url: String?,
        surface: Surface?,
        startPositionMs: Long,
        headers: Map<String, String>? = null
    ): Boolean {
        if (nativeContext == 0L) return false
        val headersArray = if (!headers.isNullOrEmpty()) {
            val list = ArrayList<String>(headers.size * 2)
            for ((k, v) in headers) {
                list.add(k)
                list.add(v)
            }
            list.toTypedArray()
        } else {
            null
        }
        return nativeOpen(nativeContext, bridge, url, surface, startPositionMs, headersArray)
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

    fun setFastSeek(enabled: Boolean) {
        if (nativeContext != 0L) {
            nativeSetFastSeek(nativeContext, enabled)
        }
    }

    fun setAudioDelay(delayMs: Long) {
        audioSink.audioDelayMs = delayMs
        if (nativeContext != 0L) {
            nativeSetAudioDelay(nativeContext, delayMs)
        }
    }

    fun getAudioDelay(): Long = audioSink.audioDelayMs

    fun getAudioTracks(): List<String> {
        if (nativeContext == 0L) return emptyList()
        val arr = nativeGetAudioTracks(nativeContext) ?: return emptyList()
        return arr.toList()
    }

    fun selectAudioTrack(index: Int): Boolean {
        if (nativeContext == 0L) return false
        return nativeSelectAudioTrack(nativeContext, index)
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
    private fun getAudioLatencyUs(): Long {
        return audioSink.getAudioPlaybackLatencyUs()
    }

    @Keep
    private fun onAudioFlush() {
        audioSink.flush()
    }

    @Keep
    private fun onVideoSizeChanged(width: Int, height: Int, rotationDegrees: Int, sarNum: Int, sarDen: Int) {
        listener?.onVideoSizeChanged(width, height, rotationDegrees, sarNum, sarDen)
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

    @Keep
    private fun onSubtitleHeader(trackId: Int, header: ByteArray, title: String) {
        listener?.onSubtitleHeader(trackId, header, title)
    }

    @Keep
    private fun onSubtitleData(trackId: Int, timeUs: Long, durationUs: Long, data: ByteArray) {
        listener?.onSubtitleData(trackId, timeUs, durationUs, data)
    }

    @Keep
    private fun onBitmapSubtitle(trackId: Int, startPtsUs: Long, endPtsUs: Long, x: Int, y: Int, w: Int, h: Int, argb: IntArray?, canvasW: Int, canvasH: Int) {
        listener?.onBitmapSubtitle(trackId, startPtsUs, endPtsUs, x, y, w, h, argb, canvasW, canvasH)
    }

    @Keep
    private fun onFontAttachment(name: String, data: ByteArray) {
        listener?.onFontAttachment(name, data)
    }

    @Keep
    private fun onAudioSessionId(sessionId: Int) {
        onAudioSessionId?.invoke(sessionId)
    }

    @Keep
    private fun onFrameRendered(ptsUs: Long) {
        listener?.onFrameRendered(ptsUs)
    }

    data class DebugInfo(
        val videoCodec: String,
        val resolution: String,
        val frameRate: Float,
        val videoBitrate: Long,
        val audioCodec: String,
        val sampleRate: Int,
        val channels: Int,
        val audioLanguage: String,
        val audioBitrate: Long,
        val renderedFrames: Long,
        val droppedFrames: Long
    )

    fun getDebugInfo(): DebugInfo? {
        if (nativeContext == 0L) return null
        val arr = nativeGetDebugInfo(nativeContext) ?: return null
        if (arr.size < 11) return null
        return DebugInfo(
            videoCodec = arr[0] ?: "",
            resolution = arr[1] ?: "",
            frameRate = arr[2]?.toFloatOrNull() ?: 0f,
            videoBitrate = arr[3]?.toLongOrNull() ?: 0L,
            audioCodec = arr[4] ?: "",
            sampleRate = arr[5]?.toIntOrNull() ?: 0,
            channels = arr[6]?.toIntOrNull() ?: 0,
            audioLanguage = arr[7] ?: "",
            audioBitrate = arr[8]?.toLongOrNull() ?: 0L,
            renderedFrames = arr[9]?.toLongOrNull() ?: 0L,
            droppedFrames = arr[10]?.toLongOrNull() ?: 0L
        )
    }

    fun selectSubtitleTrack(index: Int): Boolean {
        if (nativeContext == 0L) return false
        return nativeSelectSubtitleTrack(nativeContext, index)
    }

    fun setHardwareAcceleration(enabled: Boolean) {
        if (nativeContext != 0L) {
            nativeSetHardwareAcceleration(nativeContext, enabled)
        }
    }

    fun setForceSdr(forceSdr: Boolean) {
        if (nativeContext != 0L) {
            nativeSetForceSdr(nativeContext, forceSdr)
        }
    }

    fun setScrubbing(isScrubbing: Boolean) {
        if (nativeContext != 0L) {
            nativeSetScrubbing(nativeContext, isScrubbing)
        }
    }

    fun setEqualizer(enabled: Boolean, bandLevelsMb: IntArray) {
        if (nativeContext != 0L) {
            nativeSetEqualizer(nativeContext, enabled, bandLevelsMb)
        }
    }

    fun getAudioSessionId(): Int {
        if (nativeContext == 0L) return 0
        return nativeGetAudioSessionId(nativeContext)
    }

    fun getVideoRotation(): Int = if (nativeContext != 0L) nativeGetVideoRotation(nativeContext) else 0
    fun getSarNum(): Int = if (nativeContext != 0L) nativeGetSarNum(nativeContext) else 1
    fun getSarDen(): Int = if (nativeContext != 0L) nativeGetSarDen(nativeContext) else 1
    fun getSampleAspectRatio(): Float {
        val den = getSarDen()
        val num = getSarNum()
        return if (num > 0 && den > 0) num.toFloat() / den.toFloat() else 1.0f
    }

    // ─── JNI Method Declarations ────────────────────────────────────────────

    private external fun nativeCreate(): Long
    private external fun nativeOpen(
        handle: Long,
        bridge: Any?,
        url: String?,
        surface: Surface?,
        startPositionMs: Long,
        headers: Array<String>?
    ): Boolean
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
    private external fun nativeSetFastSeek(handle: Long, enabled: Boolean)
    private external fun nativeGetAudioTracks(handle: Long): Array<String>?
    private external fun nativeSelectAudioTrack(handle: Long, trackIndex: Int): Boolean
    private external fun nativeSelectSubtitleTrack(handle: Long, trackIndex: Int): Boolean
    private external fun nativeGetVideoWidth(handle: Long): Int
    private external fun nativeGetVideoHeight(handle: Long): Int
    private external fun nativeGetVideoRotation(handle: Long): Int
    private external fun nativeGetSarNum(handle: Long): Int
    private external fun nativeGetSarDen(handle: Long): Int
    private external fun nativeGetDebugInfo(handle: Long): Array<String>?
    private external fun nativeSetHardwareAcceleration(handle: Long, enabled: Boolean)
    private external fun nativeSetForceSdr(handle: Long, forceSdr: Boolean)
    private external fun nativeSetScrubbing(handle: Long, isScrubbing: Boolean)
    private external fun nativeSetAudioDelay(handle: Long, delayMs: Long)
    private external fun nativeSetEqualizer(handle: Long, enabled: Boolean, gainsMb: IntArray)
    private external fun nativeGetAudioSessionId(handle: Long): Int
}
