package com.rhnxdev.hzplayer.data.datasource.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.text.Cue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.SeekParameters
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
    private val trackSelector = DefaultTrackSelector(context).apply {
        setParameters(
            buildUponParameters()
                .setTunnelingEnabled(true)
        )
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 15_000,
            /* maxBufferMs = */ 30_000,
            /* bufferForPlaybackMs = */ 500,
            /* bufferForPlaybackAfterUserActionMs = */ 500
        )
        .build()

    /** Whether the device display supports HDR (queried once at init). */
    private val _displayNeedsSurfaceView = MutableStateFlow(false)
    val displayNeedsSurfaceView: StateFlow<Boolean> = _displayNeedsSurfaceView.asStateFlow()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .setRenderersFactory(
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)
        )
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context)
                .setDataSourceFactory(buildCompositeDataSourceFactory(context))
        )
        .setAudioAttributes(AudioAttributes.DEFAULT, true)
        .setHandleAudioBecomingNoisy(true)
        .build().also { exo ->
            exo.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            val display = context.getSystemService(Context.DISPLAY_SERVICE)?.let {
                (it as? android.hardware.display.DisplayManager)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            }
            if (display != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
                val hdrCaps = display.isHdr
                val hdrTypes = display.hdrCapabilities?.supportedHdrTypes
                val typesStr = hdrTypes?.joinToString() ?: "none"
                android.util.Log.d(TAG, "Display HDR supported=$hdrCaps types=[$typesStr] sdk=${android.os.Build.VERSION.SDK_INT}")
                // If display supports HDR, SurfaceView is required for 10-bit passthrough
                _displayNeedsSurfaceView.value = hdrCaps
            }
            android.util.Log.d(TAG, "ExoPlayer built: extensionRendererMode=ON enableDecoderFallback=true tunneling=${trackSelector.parameters.tunnelingEnabled}")
        }

    private val _playbackStateInfo = MutableStateFlow(PlayerStateInfo())
    val playbackStateInfo: StateFlow<PlayerStateInfo> = _playbackStateInfo.asStateFlow()

    private val _subtitleCues = MutableStateFlow<List<Cue>>(emptyList())
    val subtitleCues: StateFlow<List<Cue>> = _subtitleCues.asStateFlow()

    init {
        player.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val metadata = mediaItem?.mediaMetadata
                    val uri = mediaItem?.localConfiguration?.uri?.toString()
                    _playbackStateInfo.value = _playbackStateInfo.value.copy(
                        currentTitle = metadata?.title?.toString(),
                        currentArtist = metadata?.artist?.toString(),
                        currentUri = uri
                    )
                }

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

                override fun onEvents(player: Player, events: Player.Events) {
                    _playbackStateInfo.value = _playbackStateInfo.value.copy(
                        bufferedPosition = player.bufferedPosition.coerceAtLeast(0),
                    )
                }
            },
        )

        player.addListener(object : Player.Listener {
            override fun onCues(cues: MutableList<Cue>) {
                _subtitleCues.value = cues.toList()
            }
        })
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
        private const val TAG = "MediaPlayerHolder"

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        fun buildCompositeDataSourceFactory(context: Context): DataSource.Factory {
            val defaultFactory = DefaultDataSource.Factory(context)
            return DataSource.Factory {
                ProtocolRoutingDataSource(defaultFactory)
            }
        }
    }
}

/**
 * A [DataSource] that delegates to the correct backend based on URI scheme:
 * - `smb://` → [SmbDataSource] (jcifs-ng)
 * - `ftp://` → [FtpDataSource] (Apache Commons Net)
 * - `sftp://` → [SftpDataSource] (SSHJ)
 * - `webdav://` / `webdavs://` → [WebDavDataSource] (OkHttp)
 * - everything else → [DefaultDataSource] (native HTTP/file/content)
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class ProtocolRoutingDataSource(
    private val defaultFactory: DefaultDataSource.Factory,
) : androidx.media3.datasource.DataSource {

    private var delegate: androidx.media3.datasource.DataSource? = null

    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
        delegate = when (dataSpec.uri.scheme?.lowercase()) {
            "smb" -> SmbDataSource()
            "ftp" -> FtpDataSource()
            "sftp" -> SftpDataSource()
            "webdav", "webdavs" -> WebDavDataSource()
            else -> defaultFactory.createDataSource()
        }
        android.util.Log.d("ProtocolRoutingDataSource", "routing scheme=${dataSpec.uri.scheme} -> ${delegate!!::class.java.simpleName}")
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
