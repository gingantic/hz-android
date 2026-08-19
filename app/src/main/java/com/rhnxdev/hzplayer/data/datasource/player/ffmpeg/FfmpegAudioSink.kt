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
    private var channelConfig: Int = AudioFormat.CHANNEL_OUT_STEREO
    private var isPlaying = false
    private var currentSpeed: Float = 1.0f
    private var totalFramesWritten: Long = 0L

    @Synchronized
    fun init(sampleRate: Int, channelCount: Int) {
        if (this.sampleRate == sampleRate && audioTrack != null) {
            return
        }
        release()
        totalFramesWritten = 0L
        this.sampleRate = sampleRate
        this.channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

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
            if (isPlaying) {
                audioTrack?.play()
            }
            Log.d(TAG, "AudioTrack initialized (sampleRate=$sampleRate, bufferSize=$bufferSize)")
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
            val bytesPerFrame = if (channelConfig == AudioFormat.CHANNEL_OUT_MONO) 2 else 4
            totalFramesWritten += bytesWritten / bytesPerFrame
        }
        return bytesWritten
    }

    @Synchronized
    fun getAudioPlaybackLatencyUs(): Long {
        val track = audioTrack ?: return 0L
        val headPos = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val bufferedFrames = (totalFramesWritten - headPos).coerceAtLeast(0L)
        return (bufferedFrames * 1_000_000L) / sampleRate
    }

    @Synchronized
    fun play() {
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
        try {
            audioTrack?.pause()
            audioTrack?.flush()
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
        val track = audioTrack ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val params = track.playbackParams
                params.speed = speed.coerceIn(0.25f, 4.0f)
                track.playbackParams = params
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set playback speed on AudioTrack: ${e.message}")
            }
        }
    }

    @Synchronized
    fun release() {
        isPlaying = false
        totalFramesWritten = 0L
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }
}

