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
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.Tracks
import com.rhnxdev.hzplayer.domain.model.NetworkTraffic
import com.rhnxdev.hzplayer.domain.player.PlaybackErrorMapper
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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaPlayerHolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private var trackSelector: DefaultTrackSelector = buildTrackSelector()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildTrackSelector(): DefaultTrackSelector =
        DefaultTrackSelector(context).apply {
            // Tunneling disabled: on 4K HDR HEVC the tunneled codec stalls ~1-2s
            // after a seek (shows the seek frame while audio keeps playing), then
            // resumes already synced. 1080p hides it. Off = default, fixes the stall.
            setParameters(buildUponParameters().setTunnelingEnabled(false))
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

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    var player: ExoPlayer = buildPlayer()
        private set

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildPlayer(): ExoPlayer {
        return ExoPlayer.Builder(context)
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
                exo.addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializationDurationMs: Long,
                        codecInitializationDurationMs: Long,
                    ) { _videoDecoderName.value = decoderName }
                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializationDurationMs: Long,
                    ) { _videoDecoderName.value = decoderName }
                    override fun onAudioDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializationDurationMs: Long,
                        codecInitializationDurationMs: Long,
                    ) { _audioDecoderName.value = decoderName }
                    override fun onAudioDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializationDurationMs: Long,
                    ) { _audioDecoderName.value = decoderName }
                    override fun onVideoEnabled(
                        eventTime: AnalyticsListener.EventTime,
                        decoderCounters: DecoderCounters,
                    ) { videoDecoderCounters = decoderCounters }
                    override fun onVideoDisabled(
                        eventTime: AnalyticsListener.EventTime,
                        decoderCounters: DecoderCounters,
                    ) { videoDecoderCounters = null }
                })
                val display = context.getSystemService(Context.DISPLAY_SERVICE)?.let {
                    (it as? android.hardware.display.DisplayManager)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                }
                if (display != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
                    val hdrCaps = display.isHdr
                    val hdrTypes = display.hdrCapabilities?.supportedHdrTypes
                    val typesStr = hdrTypes?.joinToString() ?: "none"
                    android.util.Log.d(TAG, "Display HDR supported=$hdrCaps types=[$typesStr] sdk=${android.os.Build.VERSION.SDK_INT}")
                }
                android.util.Log.d(TAG, "ExoPlayer built")
            }
    }

    private val _playbackStateInfo = MutableStateFlow(PlayerStateInfo())
    val playbackStateInfo: StateFlow<PlayerStateInfo> = _playbackStateInfo.asStateFlow()

    private val _subtitleCues = MutableStateFlow<List<Cue>>(emptyList())
    val subtitleCues: StateFlow<List<Cue>> = _subtitleCues.asStateFlow()

    private val _videoDecoderName = MutableStateFlow("")
    val videoDecoderName: StateFlow<String> = _videoDecoderName.asStateFlow()

    private val _audioDecoderName = MutableStateFlow("")
    val audioDecoderName: StateFlow<String> = _audioDecoderName.asStateFlow()

    /** Video decoder counters — set by AnalyticsListener.onVideoEnabled.
     *  @Volatile: written on the player/analytics thread, read on the FPS poll thread. */
    @Volatile private var videoDecoderCounters: DecoderCounters? = null

    fun readFrameCounters(): Pair<Long, Long> {
        val dc = videoDecoderCounters ?: return 0L to 0L
        return dc.renderedOutputBufferCount.toLong() to dc.droppedBufferCount.toLong()
    }

    init {
        attachListeners(player)
    }

    /** Register all playback listeners on [target]. Called at init and after every player rebuild. */
    private fun attachListeners(target: ExoPlayer) {
        target.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val metadata = mediaItem?.mediaMetadata
                    val uri = mediaItem?.localConfiguration?.uri?.toString()
                    val drmActive = mediaItem?.localConfiguration?.drmConfiguration != null
                    _playbackStateInfo.value = _playbackStateInfo.value.copy(
                        currentTitle = metadata?.title?.toString(),
                        currentArtist = metadata?.artist?.toString(),
                        currentUri = uri,
                        drmSessionActive = drmActive,
                    )
                }

                @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
                override fun onTracksChanged(tracks: Tracks) {
                    // No consumer for track-changed notifications in the current
                    // engine wiring — kept as no-op for future extensions.
                }

                override fun onPlaybackStateChanged(state: Int) {
                    val isNewPlayback = state == Player.STATE_BUFFERING || state == Player.STATE_READY
                    _playbackStateInfo.value = _playbackStateInfo.value.copy(
                        state = when (state) {
                            Player.STATE_IDLE -> PlayerState.IDLE
                            Player.STATE_BUFFERING -> PlayerState.BUFFERING
                            Player.STATE_READY -> PlayerState.READY
                            Player.STATE_ENDED -> PlayerState.ENDED
                            else -> PlayerState.IDLE
                        },
                        bufferedPosition = target.bufferedPosition.coerceAtLeast(0),
                        errorMessage = if (isNewPlayback) null else _playbackStateInfo.value.errorMessage
                    )
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e(TAG, "ExoPlayer playback error [Code: ${error.errorCode} (${error.errorCodeName})]: ${error.message}", error)

                    // Map to a safe, user-facing error. The visible message is a
                    // localized resource (resolved by the UI); the raw cause is never
                    // shown. Credentials/hostnames are stripped in the mapper.
                    val mapped = PlaybackErrorMapper.map(error)
                    val ctx = context
                    val message = ctx.getString(
                        ctx.resources.getIdentifier(mapped.stringResName, "string", ctx.packageName)
                    )

                    _playbackStateInfo.value = _playbackStateInfo.value.copy(
                        state = PlayerState.ERROR,
                        isPlaying = false,
                        errorMessage = message,
                        errorKind = mapped.kind,
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

        target.addListener(object : Player.Listener {
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

    fun clearError() {
        _playbackStateInfo.value = _playbackStateInfo.value.copy(
            state = PlayerState.IDLE,
            errorMessage = null
        )
    }

    /** Signal subtitle discovery is in progress — surface BUFFERING to the UI. */
    fun setDiscovering() {
        _playbackStateInfo.value = _playbackStateInfo.value.copy(state = PlayerState.BUFFERING)
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
        val resolved = when (dataSpec.uri.scheme?.lowercase()) {
            "smb" -> SmbDataSource()
            "ftp" -> FtpDataSource()
            "sftp" -> SftpDataSource()
            "webdav", "webdavs" -> WebDavDataSource()
            else -> defaultFactory.createDataSource()
        }
        delegate = resolved
        android.util.Log.d("ProtocolRoutingDataSource", "routing scheme=${dataSpec.uri.scheme} -> ${resolved::class.java.simpleName}")
        return resolved.open(dataSpec)
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
