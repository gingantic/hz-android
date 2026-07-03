package com.rhnxdev.hzplayer.data.datasource.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.rhnxdev.hzplayer.domain.model.NetworkTraffic
import com.rhnxdev.hzplayer.domain.model.PlayerState
import com.rhnxdev.hzplayer.domain.model.PlayerStateInfo
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaPlayerHolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(
            // EXTENSION_RENDERER_MODE_PREFER: picks extension (or best hardware) decoders
            // first, which correctly handle limited-range YUV → RGB color conversion.
            // Without this, some devices fall back to software decoders that skip the
            // color-range fixup and produce muted / washed-out colors on BT.709 content.
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true)
        )
        .setMediaSourceFactory(
            // Composite DataSource: smb:// URIs go to SmbDataSource (jcifs-ng) so that
            // ExoPlayer can play SMB files directly with full HDR10/HDR10+ colour support.
            // All other schemes (file://, http://, https://, content://) use the
            // default Android DataSource stack.
            DefaultMediaSourceFactory(context)
                .setDataSourceFactory(buildCompositeDataSourceFactory(context))
        )
        .setAudioAttributes(AudioAttributes.DEFAULT, true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _playbackStateInfo = MutableStateFlow(PlayerStateInfo())
    val playbackStateInfo: StateFlow<PlayerStateInfo> = _playbackStateInfo.asStateFlow()

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    _playbackStateInfo.value = _playbackStateInfo.value.copy(
                        state = when (state) {
                            Player.STATE_IDLE -> PlayerState.IDLE
                            Player.STATE_BUFFERING -> PlayerState.BUFFERING
                            Player.STATE_READY -> PlayerState.READY
                            Player.STATE_ENDED -> PlayerState.ENDED
                            else -> PlayerState.IDLE
                        },
                        bufferedPosition = player.bufferedPosition.coerceAtLeast(0),
                    )
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    _playbackStateInfo.value = _playbackStateInfo.value.copy(
                        state = PlayerState.ERROR,
                        isPlaying = false,
                        errorMessage = when (error.errorCode) {
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
                            -> "Network error — check your connection"
                            else -> "Playback error: ${error.localizedMessage ?: "unknown"}"
                        },
                    )
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playbackStateInfo.value = _playbackStateInfo.value.copy(
                        isPlaying = isPlaying,
                    )
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    _playbackStateInfo.value = _playbackStateInfo.value.copy(
                        shuffleModeEnabled = shuffleModeEnabled,
                    )
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    _playbackStateInfo.value = _playbackStateInfo.value.copy(
                        repeatMode = when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                            else -> RepeatMode.NONE
                        },
                    )
                }

                // Bug 2 fix: update bufferedPosition on every event, not just state changes.
                // Keeps the StateFlow snapshot fresh for the position poller (important for
                // HLS/DASH where buffer fills continuously, not just at state transitions).
                override fun onEvents(player: Player, events: Player.Events) {
                    _playbackStateInfo.value = _playbackStateInfo.value.copy(
                        bufferedPosition = player.bufferedPosition.coerceAtLeast(0),
                    )
                }
            },
        )
    }

    fun updatePosition(positionMs: Long, durationMs: Long, bufferedMs: Long) {
        _playbackStateInfo.value = _playbackStateInfo.value.copy(
            currentPosition = positionMs,
            duration = durationMs,
            bufferedPosition = bufferedMs,
        )
    }

    fun updateSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _playbackStateInfo.value = _playbackStateInfo.value.copy(
            playbackSpeed = speed,
        )
    }

    fun buildMediaItem(uri: String, title: String): MediaItem {
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title)
                    .build(),
            )
            .build()
    }

    fun release() {
        player.release()
    }

    companion object {
        /**
         * Builds a [DataSource.Factory] that routes requests by URI scheme:
         * - `smb://` → [SmbDataSourceFactory] (jcifs-ng, HDR-capable ExoPlayer path)
         * - everything else → [DefaultDataSource.Factory] (Android HTTP / file stack)
         */
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        fun buildCompositeDataSourceFactory(context: Context): DataSource.Factory {
            val defaultFactory = DefaultDataSource.Factory(context)
            val smbFactory = SmbDataSourceFactory()
            return DataSource.Factory {
                // The DataSpec is not yet available at factory-creation time, so we
                // return a delegating DataSource that picks the right backend on open().
                SmbRoutingDataSource(defaultFactory, smbFactory)
            }
        }
    }
}

/**
 * A [DataSource] that delegates to [SmbDataSource] for `smb://` URIs and to the
 * default [DefaultDataSource] stack for everything else. The delegation decision
 * is made in [open] where the full [DataSpec] (and therefore the URI) is available.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class SmbRoutingDataSource(
    private val defaultFactory: DefaultDataSource.Factory,
    private val smbFactory: SmbDataSourceFactory,
) : androidx.media3.datasource.DataSource {

    private var delegate: androidx.media3.datasource.DataSource? = null

    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
        delegate = if (dataSpec.uri.scheme?.lowercase() == "smb") {
            smbFactory.createDataSource()
        } else {
            defaultFactory.createDataSource()
        }
        return delegate!!.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: androidx.media3.common.C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        delegate?.addTransferListener(transferListener)
    }

    override fun close() {
        try { delegate?.close() } finally { delegate = null }
    }
}

