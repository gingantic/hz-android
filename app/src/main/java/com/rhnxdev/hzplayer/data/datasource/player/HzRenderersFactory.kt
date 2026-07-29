package com.rhnxdev.hzplayer.data.datasource.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextRenderer
import com.rhnxdev.hzplayer.data.datasource.player.ffmpeg.FfmpegAudioRenderer
import com.rhnxdev.hzplayer.data.datasource.player.ffmpeg.FfmpegLibrary
import com.rhnxdev.hzplayer.data.datasource.player.ffmpeg.FfmpegVideoRenderer
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.AssHandler
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.AssTimeRenderer

/**
 * The app's single [DefaultRenderersFactory]. Media3 allows exactly one
 * factory per player, so every renderer/sink customization — subtitle AND
 * audio — has to live here:
 *
 * Subtitles:
 * 1. Enables legacy decoding on text renderers so raw text/x-ssa data doesn't crash
 * 2. Adds [AssTimeRenderer] to sync playback time to [AssHandler]
 *
 * Audio:
 * 3. Injects the [TenBandEqualizerProcessor] into the audio sink's processor chain
 * 4. Wraps the audio sink in an [AudioDelaySink] for adjustable A/V audio delay
 *
 * FFmpeg software fallback:
 * 5. Appends [FfmpegVideoRenderer]/[FfmpegAudioRenderer] after the MediaCodec
 *    renderers, so codecs without a device decoder (ProRes, DNxHD, MJPEG,
 *    CineForm, DTS, ALAC, …) decode in software — hardware always wins when
 *    available because renderer order breaks capability ties. When
 *    [preferFfmpeg] is set (the "FFmpeg" engine selection), the FFmpeg
 *    renderers are inserted *before* the MediaCodec ones instead — Media3's
 *    extension-prefer semantics — forcing all FFmpeg-supported media through
 *    software decode.
 */
@UnstableApi
class HzRenderersFactory(
    context: Context,
    private val assHandler: AssHandler,
    private val eqProcessor: TenBandEqualizerProcessor,
    private val preferFfmpeg: Boolean = false,
) : DefaultRenderersFactory(context) {

    /** The delay wrapper created for this player instance; set during build. */
    var audioDelaySink: AudioDelaySink? = null
        private set

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        // Mirrors super.buildAudioSink() but adds the EQ processor. Custom
        // processors only run on the PCM path, so float output stays off
        // (the factory default) — otherwise the EQ would be silently bypassed.
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(eqProcessor))
            .build()
        return AudioDelaySink(sink).also { audioDelaySink = it }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: android.os.Handler,
        eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val insertIndex = out.size
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out,
        )
        // Appended last: only claims formats MediaCodec renderers can't handle.
        // In preferFfmpeg mode it goes first instead, so FFmpeg wins the
        // capability tie for every format it supports.
        if (FfmpegLibrary.isAvailable()) {
            val ffmpegRenderer = FfmpegVideoRenderer(
                allowedVideoJoiningTimeMs,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            )
            if (preferFfmpeg) out.add(insertIndex, ffmpegRenderer) else out.add(ffmpegRenderer)
        }
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: android.os.Handler,
        eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        val insertIndex = out.size
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
        // Shares the factory's AudioSink, so the EQ processor and the audio
        // delay wrapper apply to FFmpeg-decoded PCM too. Ordered like video:
        // fallback by default, first in preferFfmpeg mode.
        if (FfmpegLibrary.isAvailable()) {
            val ffmpegRenderer = FfmpegAudioRenderer(eventHandler, eventListener, audioSink)
            if (preferFfmpeg) out.add(insertIndex, ffmpegRenderer) else out.add(ffmpegRenderer)
        }
    }

    override fun buildTextRenderers(
        context: Context,
        output: androidx.media3.exoplayer.text.TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)

        for (renderer in out) {
            if (renderer is TextRenderer) {
                renderer.experimentalSetLegacyDecodingEnabled(true)
            }
        }
    }

    override fun createRenderers(
        eventHandler: android.os.Handler,
        videoRendererEventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
        audioRendererEventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
        textRendererOutput: androidx.media3.exoplayer.text.TextOutput,
        metadataRendererOutput: androidx.media3.exoplayer.metadata.MetadataOutput,
    ): Array<Renderer> {
        val renderers = super.createRenderers(
            eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textRendererOutput,
            metadataRendererOutput,
        )
        return renderers + AssTimeRenderer(assHandler)
    }
}
