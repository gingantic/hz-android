package com.rhnxdev.hzplayer.data.datasource.player.ffmpeg

import android.view.Surface
import androidx.annotation.Keep
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * JNI wrapper for the standalone native FFmpeg player (libffplayer.so).
 * Owns the native context pointer, demuxing, decoding, and direct rendering.
 */
@Keep
class FfmpegNativePlayer {
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

    @Volatile
    var listener: Listener? = null
    @Volatile
    var onAudioSessionId: ((Int) -> Unit)? = null

    // Native callbacks are delivered synchronously from demux/decode threads.
    // Queue application callbacks so listener code never re-enters native APIs
    // while native codec, subtitle, or window locks are held.
    private val callbackExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FfmpegNativePlayer-callback").apply {
            isDaemon = true
        }
    }
    @Volatile
    private var callbacksEnabled = true
    private val callbackGeneration = AtomicLong(0L)

    private fun dispatchNativeCallback(callback: () -> Unit) {
        if (!callbacksEnabled) return
        val generation = callbackGeneration.get()
        try {
            callbackExecutor.execute {
                if (callbacksEnabled && callbackGeneration.get() == generation) {
                    try {
                        callback()
                    } catch (error: Throwable) {
                        android.util.Log.e("FfmpegNativePlayer", "Listener callback failed", error)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Release may close the executor between the flag check and enqueue.
        }
    }

    // Every JNI call acquires an in-flight reference before reading this handle.
    // Release marks the wrapper closed, waits for those calls to finish, and only
    // then deletes the native context. This avoids check-then-release UAFs without
    // holding a Kotlin lock while nativeRelease joins callback-owning threads.
    private val nativeLifecycleLock = ReentrantLock()
    private val nativeCallsDrained = nativeLifecycleLock.newCondition()
    private var nativeContext: Long = 0L
    private var nativeCallCount = 0
    private var releasing = false
    @Volatile
    private var audioDelayMs: Long = 0L

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
        val handle = nativeCreate()
        nativeLifecycleLock.lock()
        try {
            nativeContext = handle
        } finally {
            nativeLifecycleLock.unlock()
        }
    }

    /**
     * Run one JNI operation while keeping the native context alive. Calls that
     * arrive after release begins receive [defaultValue] instead of using the
     * handle being torn down.
     */
    private fun <T> withNativeContext(defaultValue: T, block: (Long) -> T): T {
        val handle: Long
        nativeLifecycleLock.lock()
        try {
            if (releasing || nativeContext == 0L) return defaultValue
            handle = nativeContext
            nativeCallCount++
        } finally {
            nativeLifecycleLock.unlock()
        }

        return try {
            block(handle)
        } finally {
            nativeLifecycleLock.lock()
            try {
                nativeCallCount--
                if (nativeCallCount == 0) {
                    nativeCallsDrained.signalAll()
                }
            } finally {
                nativeLifecycleLock.unlock()
            }
        }
    }

    fun open(
        bridge: Any?,
        url: String?,
        surface: Surface?,
        startPositionMs: Long,
        headers: Map<String, String>? = null
    ): Boolean {
        callbackGeneration.incrementAndGet()
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
        return withNativeContext(false) { handle ->
            nativeOpen(handle, bridge, url, surface, startPositionMs, headersArray)
        }
    }

    fun setSurface(surface: Surface?) {
        withNativeContext(Unit) { handle ->
            nativeSetSurface(handle, surface)
        }
    }

    fun play() {
        withNativeContext(Unit) { handle ->
            nativePlay(handle)
        }
    }

    fun pause() {
        withNativeContext(Unit) { handle ->
            nativePause(handle)
        }
    }

    fun seekTo(positionMs: Long) {
        withNativeContext(Unit) { handle ->
            nativeSeek(handle, positionMs)
        }
    }

    fun stop() {
        callbackGeneration.incrementAndGet()
        withNativeContext(Unit) { handle ->
            nativeStop(handle)
        }
    }

    fun release() {
        callbackGeneration.incrementAndGet()
        var handle = 0L
        var interrupted = false

        nativeLifecycleLock.lock()
        try {
            if (releasing || nativeContext == 0L) return
            releasing = true
            callbacksEnabled = false
            handle = nativeContext
            nativeContext = 0L

            while (nativeCallCount > 0) {
                try {
                    nativeCallsDrained.await()
                } catch (_: InterruptedException) {
                    // Finish the teardown before restoring the interrupt flag.
                    interrupted = true
                }
            }
        } finally {
            nativeLifecycleLock.unlock()
        }

        callbackExecutor.shutdownNow()
        try {
            nativeRelease(handle)
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    fun getDuration(): Long = withNativeContext(0L) { handle -> nativeGetDuration(handle) }
    fun getPosition(): Long = withNativeContext(0L) { handle -> nativeGetPosition(handle) }
    fun isPlaying(): Boolean = withNativeContext(false) { handle -> nativeIsPlaying(handle) }

    fun setSpeed(speed: Float) {
        withNativeContext(Unit) { handle ->
            nativeSetSpeed(handle, speed)
        }
    }

    fun setFastSeek(enabled: Boolean) {
        withNativeContext(Unit) { handle ->
            nativeSetFastSeek(handle, enabled)
        }
    }

    fun setAudioDelay(delayMs: Long) {
        audioDelayMs = delayMs
        withNativeContext(Unit) { handle ->
            nativeSetAudioDelay(handle, delayMs)
        }
    }

    fun getAudioDelay(): Long = audioDelayMs

    fun getAudioTracks(): List<String> {
        val arr = withNativeContext<Array<String>?>(null) { handle ->
            nativeGetAudioTracks(handle)
        } ?: return emptyList()
        return arr.toList()
    }

    fun selectAudioTrack(index: Int): Boolean =
        withNativeContext(false) { handle -> nativeSelectAudioTrack(handle, index) }

    fun getVideoWidth(): Int = withNativeContext(0) { handle -> nativeGetVideoWidth(handle) }
    fun getVideoHeight(): Int = withNativeContext(0) { handle -> nativeGetVideoHeight(handle) }

    // ─── Native Callbacks ───────────────────────────────────────────────────

    @Keep
    private fun onVideoSizeChanged(width: Int, height: Int, rotationDegrees: Int, sarNum: Int, sarDen: Int) {
        val callback = listener ?: return
        dispatchNativeCallback {
            callback.onVideoSizeChanged(width, height, rotationDegrees, sarNum, sarDen)
        }
    }

    @Keep
    private fun onStateChanged(state: Int) {
        val callback = listener ?: return
        dispatchNativeCallback {
            callback.onStateChanged(state)
        }
    }

    @Keep
    private fun onError(message: String) {
        val callback = listener ?: return
        dispatchNativeCallback {
            callback.onError(message)
        }
    }

    @Keep
    private fun onPositionUpdate(positionMs: Long, durationMs: Long) {
        val callback = listener ?: return
        dispatchNativeCallback {
            callback.onPositionUpdate(positionMs, durationMs)
        }
    }

    @Keep
    private fun onSubtitleHeader(trackId: Int, header: ByteArray, title: String) {
        val callback = listener ?: return
        dispatchNativeCallback {
            callback.onSubtitleHeader(trackId, header, title)
        }
    }

    @Keep
    private fun onSubtitleData(trackId: Int, timeUs: Long, durationUs: Long, data: ByteArray) {
        val callback = listener ?: return
        dispatchNativeCallback {
            callback.onSubtitleData(trackId, timeUs, durationUs, data)
        }
    }

    @Keep
    private fun onBitmapSubtitle(trackId: Int, startPtsUs: Long, endPtsUs: Long, x: Int, y: Int, w: Int, h: Int, argb: IntArray?, canvasW: Int, canvasH: Int) {
        val callback = listener ?: return
        dispatchNativeCallback {
            callback.onBitmapSubtitle(trackId, startPtsUs, endPtsUs, x, y, w, h, argb, canvasW, canvasH)
        }
    }

    @Keep
    private fun onFontAttachment(name: String, data: ByteArray) {
        val callback = listener ?: return
        dispatchNativeCallback {
            callback.onFontAttachment(name, data)
        }
    }

    @Keep
    private fun onAudioSessionId(sessionId: Int) {
        val callback = onAudioSessionId ?: return
        dispatchNativeCallback {
            callback(sessionId)
        }
    }

    @Keep
    private fun onFrameRendered(ptsUs: Long) {
        val callback = listener ?: return
        dispatchNativeCallback {
            callback.onFrameRendered(ptsUs)
        }
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
        val arr = withNativeContext<Array<String>?>(null) { handle ->
            nativeGetDebugInfo(handle)
        } ?: return null
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

    fun selectSubtitleTrack(index: Int): Boolean =
        withNativeContext(false) { handle -> nativeSelectSubtitleTrack(handle, index) }

    fun setHardwareAcceleration(enabled: Boolean) {
        withNativeContext(Unit) { handle ->
            nativeSetHardwareAcceleration(handle, enabled)
        }
    }

    fun setForceSdr(forceSdr: Boolean) {
        withNativeContext(Unit) { handle ->
            nativeSetForceSdr(handle, forceSdr)
        }
    }

    fun setScrubbing(isScrubbing: Boolean) {
        withNativeContext(Unit) { handle ->
            nativeSetScrubbing(handle, isScrubbing)
        }
    }

    fun setEqualizer(enabled: Boolean, bandLevelsMb: IntArray) {
        withNativeContext(Unit) { handle ->
            nativeSetEqualizer(handle, enabled, bandLevelsMb)
        }
    }

    fun getAudioSessionId(): Int =
        withNativeContext(0) { handle -> nativeGetAudioSessionId(handle) }

    fun getVideoRotation(): Int =
        withNativeContext(0) { handle -> nativeGetVideoRotation(handle) }

    fun getSarNum(): Int =
        withNativeContext(1) { handle -> nativeGetSarNum(handle) }

    fun getSarDen(): Int =
        withNativeContext(1) { handle -> nativeGetSarDen(handle) }

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
