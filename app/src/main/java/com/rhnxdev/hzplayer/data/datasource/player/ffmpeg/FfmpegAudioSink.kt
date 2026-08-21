package com.rhnxdev.hzplayer.data.datasource.player.ffmpeg

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * Audio output sink for the native FFmpeg player using Android's [AudioTrack].
 * Receives resampled 16-bit PCM stereo data from native code and streams it to the device speakers/headphones.
 */
class FfmpegAudioSink {
    companion object {
        private const val TAG = "FfmpegAudioSink"
    }

    private var audioTrack: AudioTrack? = null
    private var sampleRate: Int = 48000
    private var channelCount: Int = 2
    private var channelConfig: Int = AudioFormat.CHANNEL_OUT_STEREO
    private var isPlaying = false
    private var currentSpeed: Float = 1.0f
    private var totalFramesWritten: Long = 0L
    private var rampInFramesRemaining: Int = 0
    private var totalRampInFrames: Int = 0

    var onAudioSessionId: ((Int) -> Unit)? = null
    @Volatile var audioDelayMs: Long = 0L

    @Synchronized
    private fun triggerRampIn(durationMs: Int = 80) {
        totalRampInFrames = ((sampleRate * durationMs) / 1000).coerceAtLeast(1)
        rampInFramesRemaining = totalRampInFrames
    }

    @Synchronized
    fun init(sampleRate: Int, channelCount: Int) {
        if (this.sampleRate == sampleRate && this.channelCount == channelCount && audioTrack != null) {
            triggerRampIn(60)
            return
        }
        release()
        totalFramesWritten = 0L
        headPositionOffset = 0L
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        this.channelConfig = when (channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) AudioFormat.CHANNEL_OUT_7POINT1_SURROUND else AudioFormat.CHANNEL_OUT_5POINT1
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (minBufferSize * 4).coerceAtLeast(8192)

        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            setSpeed(currentSpeed)
            triggerRampIn(80)
            if (isPlaying) {
                audioTrack?.play()
            }
            val sessionId = audioTrack?.audioSessionId ?: 0
            if (sessionId != 0) {
                onAudioSessionId?.invoke(sessionId)
            }
            Log.d(TAG, "AudioTrack initialized (sampleRate=$sampleRate, channels=$channelCount, bufferSize=$bufferSize, sessionId=$sessionId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack: ${e.message}", e)
        }
    }

    @Synchronized
    fun write(pcm: ByteArray, size: Int): Int {
        val track = audioTrack ?: return 0
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING && isPlaying) {
            try {
                track.play()
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack.play failed: ${e.message}")
            }
        }

        val bytesWritten = try {
            track.write(pcm, 0, size, AudioTrack.WRITE_BLOCKING)
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack.write failed: ${e.message}")
            -1
        }
        if (bytesWritten > 0) {
            val bytesPerFrame = (channelCount * 2).coerceAtLeast(2)
            totalFramesWritten += bytesWritten / bytesPerFrame
        }
        return bytesWritten
    }

    private var headPositionOffset: Long = 0L

    @Synchronized
    fun getAudioPlaybackLatencyUs(): Long {
        val track = audioTrack ?: return 0L
        val rawHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val headPos = (rawHead - headPositionOffset).coerceAtLeast(0L)
        val bufferedFrames = (totalFramesWritten - headPos).coerceAtLeast(0L)
        val rawLatencyUs = (bufferedFrames * 1_000_000L) / sampleRate
        val delayUs = audioDelayMs * 1_000L
        return rawLatencyUs - delayUs
    }

    @Synchronized
    fun play() {
        if (!isPlaying) {
            triggerRampIn(50)
        }
        isPlaying = true
        try {
            audioTrack?.play()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack play failed: ${e.message}")
        }
    }

    @Synchronized
    fun pause() {
        isPlaying = false
        try {
            audioTrack?.pause()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack pause failed: ${e.message}")
        }
    }

    @Synchronized
    fun flush() {
        totalFramesWritten = 0L
        triggerRampIn(60)
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            val rawHead = audioTrack?.playbackHeadPosition?.toLong()?.and(0xFFFFFFFFL) ?: 0L
            headPositionOffset = rawHead
            if (isPlaying) {
                audioTrack?.play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack flush failed: ${e.message}")
        }
    }

    @Synchronized
    fun setSpeed(speed: Float) {
        currentSpeed = speed
        // Note: Audio tempo/speed is handled in the native C++ filtergraph (atempo filter).
        // AudioTrack must always consume PCM frames at normal rate (1.0x) without applying
        // playbackParams speed, which would cause duplicate speed scaling.
    }

    @Synchronized
    fun release() {
        isPlaying = false
        totalFramesWritten = 0L
        headPositionOffset = 0L
        onAudioSessionId?.invoke(0)
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }
}

