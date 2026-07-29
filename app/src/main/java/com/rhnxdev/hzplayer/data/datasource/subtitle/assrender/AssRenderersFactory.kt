package com.rhnxdev.hzplayer.data.datasource.subtitle.assrender

import android.content.Context
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.text.TextRenderer
import com.rhnxdev.hzplayer.data.datasource.player.AudioDelaySink

/**
 * A [DefaultRenderersFactory] that:
 * 1. Enables legacy decoding on text renderers so raw text/x-ssa data doesn't crash
 * 2. Adds [AssTimeRenderer] to sync playback time to [AssHandler]
 * 3. Wraps the audio sink in an [AudioDelaySink] for adjustable A/V audio delay
 */
@UnstableApi
class AssRenderersFactory(
    context: Context,
    private val handler: AssHandler,
) : DefaultRenderersFactory(context) {

    /** The delay wrapper created for this player instance; set during build. */
    var audioDelaySink: AudioDelaySink? = null
        private set

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val sink = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
            ?: return null
        return AudioDelaySink(sink).also { audioDelaySink = it }
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
        return renderers + AssTimeRenderer(handler)
    }
}
