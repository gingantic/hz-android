package com.rhnxdev.hzplayer.data.datasource.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * [AudioSink] wrapper that shifts the audio clock reported to ExoPlayer.
 *
 * ExoPlayer syncs video to the audio renderer's clock, so offsetting
 * [getCurrentPositionUs] by [delayUs] shifts A/V sync without touching the
 * audio pipeline: a positive delay reports a clock ahead of the hardware,
 * making video render ahead so the audio is heard later than its frame.
 */
@UnstableApi
class AudioDelaySink(sink: AudioSink) : ForwardingAudioSink(sink) {

    /** Audio timing offset in microseconds; positive = audio heard later. */
    @Volatile
    var delayUs: Long = 0

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val positionUs = super.getCurrentPositionUs(sourceEnded)
        return if (positionUs == AudioSink.CURRENT_POSITION_NOT_SET || delayUs == 0L) {
            positionUs
        } else {
            positionUs + delayUs
        }
    }
}
