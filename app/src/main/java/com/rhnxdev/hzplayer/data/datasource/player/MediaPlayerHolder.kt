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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaPlayerHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val assHandler: AssHandler,
    private val eqProcessor: TenBandEqualizerProcessor,
    userPreferencesRepository: com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository,
) {
    init {
        if (java.net.CookieHandler.getDefault() == null) {
            java.net.CookieHandler.setDefault(java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL))
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private val customLoadErrorHandlingPolicy = object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(6) {
        override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val exception = loadErrorInfo.exception
            if (exception is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                val code = exception.responseCode
                if (code in 500..599 || code == 429 || code == 408) {
                    return minOf(500L * (1 shl (loadErrorInfo.errorCount - 1)), 6000L)
                }
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }

        override fun getMinimumLoadableRetryCount(dataType: Int): Int = 6
    }

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

    /** When true (the "FFmpeg" engine selection), the FFmpeg software renderers
     *  are indexed before the MediaCodec ones, forcing every FFmpeg-supported
     *  codec through software decode. Same deferred-rebuild semantics as
     *  [decoderMode]: applied on the next play.
     *
     *  Seeded synchronously from the persisted engine preference: the async
     *  collector in PlayerRepositoryImpl loses the race against the eager
     *  [player] build below on cold start (e.g. VIEW-intent playback), which
     *  would silently fall back to MediaCodec-first ordering. The initializer
     *  writes the backing field directly, so no rebuild is scheduled. */
    @Volatile var ffmpegPreferred: Boolean = try {
        runBlocking {
            userPreferencesRepository.activeEngine.first() ==
                com.rhnxdev.hzplayer.domain.player.EngineType.FFMPEG
        }
    } catch (e: Exception) {
        android.util.Log.w(TAG, "Failed to read engine preference, defaulting to MediaCodec-first", e)
        false
    }
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
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val (minMs, maxMs) = if (am.isLowRamDevice) 20_000 to 40_000 else 30_000 to 60_000
        val backBufferMs = if (am.isLowRamDevice) 15_000 else 30_000
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ minMs,
                /* maxBufferMs = */ maxMs,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterUserActionMs = */ 2_000
            )
            .setBackBuffer(
                /* backBufferDurationMs = */ backBufferMs,
                /* retainBackBufferFromKeyframe = */ true
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    /**
     * Shared HTTP data source factory, kept as a field (not rebuilt per play) so
     * request headers set via [setHttpRequestHeaders] persist across plays and
     * apply to every network request. Cross-protocol redirects are enabled because
     * HLS/DASH manifests and many CDNs redirect the request (https<->http or to a
     * token host); with them off those loads fail and the error is misclassified
     * as an auth failure ("Sign-in failed").
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private val httpDataSourceFactory: androidx.media3.datasource.DefaultHttpDataSource.Factory =
        androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(10_000)
            .setUserAgent(DEFAULT_USER_AGENT)

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    var player: ExoPlayer = buildPlayer()
        private set

    /** Delay wrapper of the current player's audio sink; refreshed on rebuild. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private var audioDelaySink: AudioDelaySink? = null

    /** Audio timing offset in ms (positive = audio heard later); survives player rebuilds. */
    @get:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @set:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    var audioDelayMs: Long = 0
        set(value) {
            field = value
            audioDelaySink?.delayUs = value * 1000
        }

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
        android.util.Log.d(TAG, "buildPlayer: ffmpegPreferred=$ffmpegPreferred decoderMode=$decoderMode")
        val renderersFactory = HzRenderersFactory(context, assHandler, eqProcessor, ffmpegPreferred)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(buildCodecSelector()) as HzRenderersFactory
        return ExoPlayer.Builder(context)
            .setTrackSelector(buildTrackSelector())
            .setLoadControl(loadControl)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context, AssExtractorsFactory(assHandler))
                    .setDataSourceFactory(buildCompositeDataSourceFactory())
                    .setSubtitleParserFactory(AssSubtitleParserFactory())
                    .setLoadErrorHandlingPolicy(customLoadErrorHandlingPolicy)
            )
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build().also { exo ->
                // Renderers (and thus the audio sink) are created during build;
                // re-apply the stored delay so it survives player rebuilds.
                audioDelaySink = renderersFactory.audioDelaySink
                audioDelaySink?.delayUs = audioDelayMs * 1000
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
        val ds = buildCompositeDataSourceFactory().createDataSource()
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

    /**
     * Build the scheme-routing data source factory used by the player and by
     * [readUriBytes]. Network (http/https) requests route through the shared
     * [httpDataSourceFactory] so header changes take effect on the next request.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildCompositeDataSourceFactory(): DataSource.Factory {
        val defaultFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        return DataSource.Factory {
            ProtocolRoutingDataSource(defaultFactory, lastTransitionUri)
        }
    }

    /**
     * Apply HTTP request headers (e.g. `Authorization` / a stream token forwarded
     * from a VIEW intent) to every subsequent network request. An empty map clears
     * previously set headers so a token from one stream never leaks into the next.
     * Mutates the shared [httpDataSourceFactory]; takes effect on the next prepare().
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun setHttpRequestHeaders(headers: Map<String, String>) {
        val filtered = mutableMapOf<String, String>()
        val forbidden = setOf(
            "host", "content-length", "connection", "accept-encoding",
            "content-type", "transfer-encoding", "if-modified-since", "if-none-match", "range", "sec-fetch-mode", "sec-fetch-site", "sec-fetch-dest"
        )

        headers.forEach { (rawKey, value) ->
            val keyLower = rawKey.trim().lowercase(java.util.Locale.ROOT)
            if (keyLower.isNotBlank() && value.isNotBlank() && !forbidden.contains(keyLower)) {
                val normalizedKey = when (keyLower) {
                    "referer" -> "Referer"
                    "user-agent" -> "User-Agent"
                    "cookie" -> "Cookie"
                    "authorization" -> "Authorization"
                    "origin" -> "Origin"
                    "accept" -> "Accept"
                    "accept-language" -> "Accept-Language"
                    else -> rawKey.trim()
                }
                filtered[normalizedKey] = value
            }
        }

        val refererVal = filtered["Referer"]
        if (!refererVal.isNullOrBlank() && filtered["Origin"].isNullOrBlank()) {
            try {
                val refUri = Uri.parse(refererVal)
                if (!refUri.scheme.isNullOrBlank() && !refUri.host.isNullOrBlank()) {
                    val portStr = if (refUri.port != -1) ":${refUri.port}" else ""
                    filtered["Origin"] = "${refUri.scheme}://${refUri.host}$portStr"
                }
            } catch (_: Exception) {}
        }

        if (filtered.none { it.key.equals("User-Agent", ignoreCase = true) }) {
            filtered["User-Agent"] = DEFAULT_USER_AGENT
        }
        httpDataSourceFactory.setDefaultRequestProperties(filtered)
    }

    companion object {
        private const val TAG = "MediaPlayerHolder"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
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
    private val primaryUriString: String? = null
) : androidx.media3.datasource.DataSource {

    private var delegate: androidx.media3.datasource.DataSource? = null

    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
        var spec = dataSpec
        try {
            val reqUri = dataSpec.uri
            val primaryUri = primaryUriString?.let { Uri.parse(it) }

            if (reqUri.scheme?.lowercase() in setOf("http", "https") && primaryUri != null) {
                if (reqUri.query.isNullOrBlank() && !primaryUri.query.isNullOrBlank() && reqUri.host.equals(primaryUri.host, ignoreCase = true)) {
                    val connector = if (reqUri.toString().contains("?")) "&" else "?"
                    val enrichedUri = Uri.parse("${reqUri}$connector${primaryUri.query}")
                    spec = dataSpec.buildUpon().setUri(enrichedUri).build()
                }
            }
        } catch (_: Exception) {}

        val resolved = when (spec.uri.scheme?.lowercase()) {
            "smb" -> SmbDataSource()
            "ftp" -> FtpDataSource()
            "sftp" -> SftpDataSource()
            "webdav", "webdavs" -> WebDavDataSource()
            "archive" -> ArchiveDataSource()
            else -> defaultFactory.createDataSource()
        }
        delegate = resolved
        
        return resolved.open(spec)
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
