package com.rhnxdev.hzplayer.data.datasource.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.text.Cue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.SeekParameters
import com.rhnxdev.hzplayer.data.datasource.archive.ArchiveDataSource
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.AssExtractorsFactory
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.AssHandler
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.AssRenderersFactory
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.AssSubtitleParserFactory
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.isLibassSubtitleFormat
import com.rhnxdev.hzplayer.domain.model.DecoderMode
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
    private val assHandler: AssHandler,
) {
    /** Current decoder preference. Drives the [MediaCodecSelector] used when
     *  the player is (re)built. Changing it mid-playback defers the rebuild
     *  until playback returns to idle, so it takes effect on the next play
     *  without disturbing the current media. */
    @Volatile var decoderMode: DecoderMode = DecoderMode.AUTO
        set(value) {
            if (field == value) return
            field = value
            requestDecoderRebuild()
        }

    /** Set when a [decoderMode] change arrived during active playback. */
    private var pendingRebuild = false
    private var isCurrentTrackAss = false

    /**
     * Build a TrackSelector with tunneling disabled. Tunneling on 4K HDR HEVC
     * stalls 1-2s after a seek (frame shows while audio continues). 1080p hides
     * it; off = safe default.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildTrackSelector(): DefaultTrackSelector =
        DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setTunnelingEnabled(false))
        }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private val loadControl = run {
        // ponytail: 90s max buffer holds tens of MB on a high-bitrate stream —
        // too much for low-RAM SoCs. Halve it there to free memory under pressure.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val (minMs, maxMs) = if (am.isLowRamDevice) 25_000 to 45_000 else 50_000 to 90_000
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ minMs,
                /* maxBufferMs = */ maxMs,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterUserActionMs = */ 5_000
            )
            .build()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    var player: ExoPlayer = buildPlayer()
        private set

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildCodecSelector(): MediaCodecSelector = when (decoderMode) {
        DecoderMode.AUTO -> MediaCodecSelector.DEFAULT
        DecoderMode.SOFTWARE -> MediaCodecSelector.PREFER_SOFTWARE
        DecoderMode.HARDWARE -> MediaCodecSelector { mimeType, secure, tunneling ->
            val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            // HW mode targets video only. Many devices expose no hardware audio
            // codec, so filtering software-only decoders from audio mimes would
            // leave audio without a decoder (silent playback). Keep audio/etc.
            // on the default list; strip software-only decoders for video only.
            if (mimeType.startsWith("video/")) {
                infos.filter { !it.softwareOnly && it.name != "OMX.google.raw.decoder" }
            } else {
                infos
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildPlayer(): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setTrackSelector(buildTrackSelector())
            .setLoadControl(loadControl)
            .setRenderersFactory(
                AssRenderersFactory(context, assHandler)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                    .setEnableDecoderFallback(true)
                    .setMediaCodecSelector(buildCodecSelector())
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context, AssExtractorsFactory(assHandler))
                    .setDataSourceFactory(buildCompositeDataSourceFactory(context))
                    .setSubtitleParserFactory(AssSubtitleParserFactory())
            )
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build().also { exo ->
                exo.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                assHandler.player = exo
                exo.addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoSizeChanged(
                        eventTime: AnalyticsListener.EventTime,
                        videoSize: VideoSize,
                    ) {
                        assHandler.setVideoSize(videoSize.width, videoSize.height)
                    }

                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializationDurationMs: Long,
                        codecInitializationDurationMs: Long,
                    ) { _videoDecoderName.value = decoderName }
                    override fun onAudioDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializationDurationMs: Long,
                        codecInitializationDurationMs: Long,
                    ) { _audioDecoderName.value = decoderName }
                    override fun onAudioSessionIdChanged(
                        eventTime: AnalyticsListener.EventTime,
                        audioSessionId: Int,
                    ) { _audioSessionId.value = audioSessionId }
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

    /**
     * Apply a [decoderMode] change: always defer the rebuild to [flushPendingDecoderRebuild]
     * (called on the main thread from play()), which runs on the next play. Reading
     * player state here would be off-main (this setter is public API and may be
     * called from any thread) — a Media3 threading violation — so we never touch
     * the player from this path. The rebuild itself only ever happens on main.
     */
    private fun requestDecoderRebuild() {
        pendingRebuild = true
        android.util.Log.d(TAG, "Decoder rebuild scheduled — applied on next play")
    }

    /**
     * Rebuild the [ExoPlayer] with the current [decoderMode] so a codec-mode
     * change takes effect without losing the singleton. The engine reads [player]
     * live, so the next play uses the new selector.
     */
    /**
     * Apply a deferred decoder-mode rebuild before new media is loaded. This is
     * the sole place a rebuild is triggered and it always runs on the main thread
     * (called from play()). Call before setMediaItem so the new selector takes
     * effect on the upcoming play.
     */
    fun flushPendingDecoderRebuild() {
        if (pendingRebuild) rebuildPlayer()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun rebuildPlayer() {
        val newPlayer = buildPlayer()
        val old = player
        player = newPlayer
        attachListeners(newPlayer)
        old.release()
        pendingRebuild = false
        // Notify observers (MediaPlaybackService) so a live MediaSession can be
        // re-pointed at the new player instead of the now-released one.
        onPlayerReplacedListener?.invoke(newPlayer)
        assHandler.player = newPlayer
        android.util.Log.d(TAG, "ExoPlayer rebuilt for decoderMode=$decoderMode")
    }

    /** Reset the subtitle handler state and prepare for playing [uri]. */
    fun prepareForUri(uri: String) {
        lastTransitionUri = uri
        assHandler.reset()
    }

    /** Last media item URI we reset [assHandler] for, to avoid redundant resets. */
    private var lastTransitionUri: String? = null

    /** Set when the underlying [ExoPlayer] is swapped (decoder rebuild). */
    private var onPlayerReplacedListener: ((ExoPlayer) -> Unit)? = null
    fun setOnPlayerReplacedListener(listener: ((ExoPlayer) -> Unit)?) {
        onPlayerReplacedListener = listener
    }

    private val _playbackStateInfo = MutableStateFlow(PlayerStateInfo())
    val playbackStateInfo: StateFlow<PlayerStateInfo> = _playbackStateInfo.asStateFlow()

    private val _subtitleCues = MutableStateFlow<List<Cue>>(emptyList())
    val subtitleCues: StateFlow<List<Cue>> = _subtitleCues.asStateFlow()

    private val _videoDecoderName = MutableStateFlow("")
    val videoDecoderName: StateFlow<String> = _videoDecoderName.asStateFlow()

    private val _audioDecoderName = MutableStateFlow("")
    val audioDecoderName: StateFlow<String> = _audioDecoderName.asStateFlow()

    private val _audioSessionId = MutableStateFlow(0)
    val audioSessionId: StateFlow<Int> = _audioSessionId.asStateFlow()

    /** Video decoder counters — set by AnalyticsListener.onVideoEnabled.
     *  @Volatile: written on the player/analytics thread, read on the FPS poll thread. */
    @Volatile private var videoDecoderCounters: DecoderCounters? = null

    fun readFrameCounters(): Pair<Long, Long> {
        val dc = videoDecoderCounters ?: return 0L to 0L
        // renderedOutputBufferCount = frames actually shown; skippedOutputBufferCount
        // = output buffers skipped at render time (arrived too late) — this is the
        // "dropped frames" figure, not droppedBufferCount (pre-render discards) or
        // droppedToKeyframeCount (decoder-triggered reseeks).
        return dc.renderedOutputBufferCount.toLong() to dc.skippedOutputBufferCount.toLong()
    }

    init {
        attachListeners(player)
    }

    /** Register all playback listeners on [target]. Called at init and after every player rebuild. */
    private fun attachListeners(target: ExoPlayer) {
        target.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val newUri = mediaItem?.localConfiguration?.uri?.toString()
                    // ExoPlayer fires this on initial prepare too; resetting then wipes an
                    // external subtitle the engine just loaded. Only reset on a genuinely
                    // different item.
                    if (newUri != null && newUri != lastTransitionUri) {
                        lastTransitionUri = newUri
                        assHandler.reset()
                    }
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

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        assHandler.onSeek()
                    }
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
                    // Decoder rebuild is deferred to flushPendingDecoderRebuild()
                    // (main thread, from play()) — never rebuild from inside this
                    // callback, as releasing the player here races the dispatch.
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e(TAG, "ExoPlayer playback error [Code: ${error.errorCode} (${error.errorCodeName})]: ${error.message}", error)

                    // Map to a safe, user-facing error. The visible message is a
                    // localized resource (resolved by the UI); the raw cause is never
                    // shown. Credentials/hostnames are stripped in the mapper.
                    val mapped = PlaybackErrorMapper.map(error)
                    val ctx = context
                    // getIdentifier returns 0 when the mapped resource is absent;
                    // getString(0) throws Resources.NotFoundException inside this
                    // callback, turning every playback error into a crash. Guard it.
                    val resId = ctx.resources.getIdentifier(mapped.stringResName, "string", ctx.packageName)
                    val message = if (resId != 0) {
                        ctx.getString(resId)
                    } else {
                        mapped.sanitizedDetail.ifBlank { "Playback failed." }
                    }

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

                override fun onTracksChanged(tracks: Tracks) {
                    var selectedAssFormat: androidx.media3.common.Format? = null
                    for (group in tracks.groups) {
                        if (group.type == androidx.media3.common.C.TRACK_TYPE_TEXT && group.isSelected) {
                            for (i in 0 until group.length) {
                                if (group.isTrackSelected(i)) {
                                    val format = group.getTrackFormat(i)
                                    val isAss = isLibassSubtitleFormat(format)
                                    if (isAss) {
                                        selectedAssFormat = format
                                    }
                                    break
                                }
                            }
                        }
                    }

                    isCurrentTrackAss = selectedAssFormat != null

                    if (selectedAssFormat != null) {
                        assHandler.selectTrackByFormat(selectedAssFormat)
                    } else if (assHandler.getActiveExternalTrackIndex() < 0) {
                        assHandler.clearOverlay()
                    }
                }

                override fun onCues(cues: MutableList<Cue>) {
                    if (isCurrentTrackAss) {
                        _subtitleCues.value = emptyList()
                    } else {
                        _subtitleCues.value = cues.toList()
                    }
                }
            },
        )
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

    fun release() {
        _audioSessionId.value = 0
        player.release()
        assHandler.reset()
    }

    /**
     * Read the full bytes of [uri] via the same scheme-routing [DataSource] the
     * player uses, so custom `smb`/`ftp`/`sftp`/`webdav` subtitle URIs load too —
     * [android.content.ContentResolver.openInputStream] only knows `content:`/
     * `file:`. Returns null on failure.
     */
    fun readUriBytes(uri: Uri): ByteArray? {
        val ds = buildCompositeDataSourceFactory(context).createDataSource()
        return runCatching {
            ds.open(androidx.media3.datasource.DataSpec.Builder().setUri(uri).build())
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var read: Int
            while (ds.read(buf, 0, buf.size).also { read = it } != androidx.media3.common.C.RESULT_END_OF_INPUT) {
                out.write(buf, 0, read)
            }
            out.toByteArray()
        }.getOrNull().also {
            runCatching { ds.close() }
        }
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
 * - `archive://` → [ArchiveDataSource] (libarchive, in-place entry read)
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
            "archive" -> ArchiveDataSource()
            else -> defaultFactory.createDataSource()
        }
        delegate = resolved
        
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
