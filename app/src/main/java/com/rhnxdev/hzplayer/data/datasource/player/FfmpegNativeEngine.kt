package com.rhnxdev.hzplayer.data.datasource.player

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.graphics.SurfaceTexture
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.Player
import com.rhnxdev.hzplayer.core.io.ArchiveRandomAccessSource
import com.rhnxdev.hzplayer.core.io.ChannelRandomAccessSource
import com.rhnxdev.hzplayer.core.io.LocalRandomAccessSource
import com.rhnxdev.hzplayer.core.io.SmbRandomAccessSource
import com.rhnxdev.hzplayer.core.io.RandomAccessMediaSource
import com.rhnxdev.hzplayer.core.util.ArchiveUri
import com.rhnxdev.hzplayer.core.util.userInfoPair
import com.rhnxdev.hzplayer.data.datasource.player.ffmpeg.FfmpegNativePlayer
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.AssHandler
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.SubtitleConverters
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.isNonAssSubtitleMimeType
import com.rhnxdev.hzplayer.domain.model.AspectRatioMode
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.DebugStats
import com.rhnxdev.hzplayer.domain.model.DecoderMode
import com.rhnxdev.hzplayer.domain.model.EqualizerInfo
import com.rhnxdev.hzplayer.domain.model.PlayerState
import com.rhnxdev.hzplayer.domain.model.PlayerStateInfo
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import com.rhnxdev.hzplayer.domain.player.RenderViewConfig
import com.rhnxdev.hzplayer.domain.player.clampSeekPosition
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.Closeable
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FfmpegNativeEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val playerHolder: MediaPlayerHolder,
    private val equalizerController: EqualizerController,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val assHandler: AssHandler,
    private val neighborSubtitleDiscoverer: NeighborSubtitleDiscoverer,
) : IPlayerEngine {

    companion object {
        private const val TAG = "FfmpegNativeEngine"

        /**
         * Maximum time [openAndStart] waits for a render surface before opening
         * native playback anyway. Bounds the wait for audio-only playback (no
         * [createRenderView] call ever happens) and any other case where a
         * surface legitimately never arrives, so playback isn't blocked forever.
         */
        private const val SURFACE_READY_TIMEOUT_MS = 1500L

        /**
         * Computes the true display aspect ratio (DAR) as `(width × SAR) : height`, with a
         * 90°/270° rotation swapping the resulting display width and height.
         *
         * Ordering (matches ffmpeg): apply SAR to the source width first, THEN apply the
         * display-matrix rotation swap. SAR defaults to 1:1 when it is not positive.
         *
         * @return the display aspect ratio, or `0f` when width/height are non-positive (or the
         *   resulting display dimensions are non-positive).
         */
        internal fun computeDisplayAspectRatio(
            videoWidth: Int,
            videoHeight: Int,
            sarNum: Int,
            sarDen: Int,
            rotationDegrees: Int
        ): Float {
            if (videoWidth <= 0 || videoHeight <= 0) return 0f

            val sar = if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen.toFloat() else 1.0f

            // Apply SAR to width first.
            val displayW0 = videoWidth.toFloat() * sar
            val displayH0 = videoHeight.toFloat()

            // Swap display width/height for a 90°/270° rotation.
            val isRotated90or270 = (rotationDegrees == 90 || rotationDegrees == 270)
            val displayW = if (isRotated90or270) displayH0 else displayW0
            val displayH = if (isRotated90or270) displayW0 else displayH0

            return if (displayW > 0f && displayH > 0f) displayW / displayH else 0f
        }

        /**
         * True when [positionMs] has reached [targetMs] from the direction of travel:
         * at or below it for a backward seek, at or above it for a forward one.
         * [toleranceMs] absorbs container/PTS rounding; a stale pre-seek position on
         * the far side of the target therefore stays rejected.
         */
        internal fun hasSeekLanded(
            positionMs: Long,
            targetMs: Long,
            seekBackward: Boolean,
            toleranceMs: Long = 500L,
        ): Boolean = if (seekBackward) {
            positionMs <= targetMs + toleranceMs
        } else {
            positionMs >= targetMs - toleranceMs
        }
    }

    override val engineType: EngineType = EngineType.NATIVE_FFMPEG

    private val player = FfmpegNativePlayer()
    private val _playbackState = MutableStateFlow(PlayerStateInfo())
    override val playbackState: StateFlow<PlayerStateInfo> = _playbackState.asStateFlow()

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activeSurface: Surface? = null
    private var activeBridge: RandomAccessMediaSource? = null

    /**
     * Released the first time [createRenderView]'s surface callback attaches a
     * real [Surface]. `SurfaceView`/`TextureView` surface creation is asynchronous
     * relative to Compose's `AndroidView` factory, so without this gate
     * [openAndStart] can call native `open()+play()` before `nativeWindow` exists —
     * the video thread then silently drops every decoded frame (audio still plays)
     * until something later flips `surfaceChanged` (e.g. a seek). See docs/PLAYER_ARCHITECTURE.md.
     * A plain [CountDownLatch] is used (not a coroutine primitive) because
     * [openAndStart] blocks synchronously under [lifecycleLock] on the IO dispatcher.
     */
    @Volatile
    private var surfaceReadyLatch = CountDownLatch(1)

    /**
     * Protects active bridge publication/detachment independently of the
     * lifecycle lock. Release must be able to abort a source while an open
     * still owns lifecycleLock.
     */
    private val bridgeLock = Any()

    /**
     * SMB pool checkout backing [activeBridge] — returned to [ConnectionPool]
     * when the bridge closes, so the sweeper can evict it when idle.
     */
    private data class SmbCheckout(val lease: ConnectionPool.SmbContextLease)

    private var activeSmbCheckout: SmbCheckout? = null
    private val lifecycleLock = Any()
    private val playbackGeneration = AtomicLong(0L)
    @Volatile
    private var currentUri: String? = null
    private var currentTitle: String? = null
    private var currentArtist: String? = null
    private var currentHeaders: Map<String, String> = emptyMap()
    private var currentPlaylist: List<Pair<String, String>>? = null
    private var currentPlaylistIndex: Int = 0
    private var repeatMode: RepeatMode = RepeatMode.NONE
    private var isShuffle: Boolean = false
    private var currentSpeed: Float = 1.0f
    private var subtitleDelay: Long = 0L
    private var selectedAudioTrackIndex: Int = 0

    private var activeRenderViewRef: WeakReference<View>? = null
    private var activeContainerRef: WeakReference<FrameLayout>? = null
    private var currentAspectRatioMode: AspectRatioMode = AspectRatioMode.AUTO
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private var videoRotation: Int = 0
    private var sarNum: Int = 1
    private var sarDen: Int = 1
    private val mainHandler = Handler(Looper.getMainLooper())
    private var playJob: Job? = null
    /**
     * Pending seek target (ms), or -1 when none. Cleared only by a
     * [AtomicLong.compareAndSet] that still matches the target it was read with, so a
     * callback racing a newer [seekTo] cannot drop that seek's filter.
     */
    private val pendingSeekTargetMs = AtomicLong(-1L)
    @Volatile
    private var pendingSeekBackward = false
    /** Set once in [release]; afterwards clock pulls return -1 and playInternal is a no-op. */
    @Volatile
    private var released = false
    private val releaseLock = Any()

    override var subtitleTrackChangeListener: (() -> Unit)? = null

    init {
        engineScope.launch {
            userPreferencesRepository.decoderMode.collect { mode ->
                setDecoderMode(mode)
            }
        }

        engineScope.launch {
            userPreferencesRepository.disableHdr.collect { disabled ->
                setDisableHdr(disabled)
            }
        }

        engineScope.launch {
            equalizerController.state.collect { eqInfo ->
                val gains = eqInfo.bands.map { it.levelMb }.toIntArray()
                player.setEqualizer(eqInfo.enabled, gains)
            }
        }

        assHandler.onExternalTrackListChanged = {
            subtitleTrackChangeListener?.invoke()
        }

        player.onAudioSessionId = { sessionId ->
            playerHolder.setAudioSessionId(sessionId)
        }

        player.listener = object : FfmpegNativePlayer.Listener {
            override fun onVideoSizeChanged(width: Int, height: Int, rotationDegrees: Int, inSarNum: Int, inSarDen: Int) {
                videoWidth = width
                videoHeight = height
                videoRotation = rotationDegrees
                sarNum = inSarNum
                sarDen = inSarDen

                val isRotated90or270 = (rotationDegrees == 90 || rotationDegrees == 270)
                val targetAssW = if (isRotated90or270) height else width
                val targetAssH = if (isRotated90or270) width else height
                assHandler.setVideoSize(targetAssW, targetAssH)
                applyAspectRatio()
            }

            override fun onStateChanged(state: Int) {
                val mappedState = when (state) {
                    FfmpegNativePlayer.STATE_IDLE -> PlayerState.IDLE
                    FfmpegNativePlayer.STATE_BUFFERING -> PlayerState.BUFFERING
                    FfmpegNativePlayer.STATE_READY -> PlayerState.READY
                    FfmpegNativePlayer.STATE_ENDED -> {
                        handlePlaybackEnded()
                        PlayerState.ENDED
                    }
                    FfmpegNativePlayer.STATE_ERROR -> PlayerState.ERROR
                    else -> PlayerState.IDLE
                }

                val isCurrentlyPlaying = player.isPlaying()
                val isBuffering = mappedState == PlayerState.BUFFERING
                assHandler.setIsPlaying(!isBuffering && isCurrentlyPlaying)
                assHandler.setIsBuffering(isBuffering)

                if (mappedState == PlayerState.READY) {
                    val curPos = player.getPosition()
                    val seekTarget = pendingSeekTargetMs.get()
                    val seekBackward = pendingSeekBackward
                    if (seekTarget < 0L ||
                        (hasSeekLanded(curPos, seekTarget, seekBackward) &&
                            pendingSeekTargetMs.compareAndSet(seekTarget, -1L))
                    ) {
                        val nowUs = SystemClock.elapsedRealtime() * 1000L
                        assHandler.updatePosition(curPos * 1000L, nowUs)
                    }
                }

                _playbackState.update { current ->
                    current.copy(
                        state = mappedState,
                        isPlaying = if (isBuffering) current.isPlaying else isCurrentlyPlaying,
                    )
                }
            }

            override fun onError(message: String) {
                Log.e(TAG, "Native player error: $message")
                assHandler.setIsPlaying(false)
                assHandler.setIsBuffering(false)
                _playbackState.update { current ->
                    current.copy(
                        state = PlayerState.ERROR,
                        isPlaying = false,
                        errorMessage = message
                    )
                }
            }

            override fun onPositionUpdate(positionMs: Long, durationMs: Long) {
                val seekTarget = pendingSeekTargetMs.get()
                val seekBackward = pendingSeekBackward
                if (seekTarget >= 0L &&
                    (!hasSeekLanded(positionMs, seekTarget, seekBackward) ||
                        !pendingSeekTargetMs.compareAndSet(seekTarget, -1L))
                ) {
                    // Drop stale position updates from pre-seek frames or callbacks.
                    return
                }
                val nowUs = SystemClock.elapsedRealtime() * 1000L
                assHandler.updatePosition(positionMs * 1000L, nowUs)
                _playbackState.update { current ->
                    current.copy(
                        bufferedPosition = positionMs,
                        isPlaying = player.isPlaying()
                    )
                }
            }

            override fun onSubtitleHeader(trackId: Int, header: ByteArray, title: String) {
                assHandler.onTrackHeader(trackId, header, title)
            }

            override fun onSubtitleData(trackId: Int, timeUs: Long, durationUs: Long, data: ByteArray) {
                assHandler.onSubtitleSample(trackId, timeUs, durationUs, data)
            }

            override fun onBitmapSubtitle(trackId: Int, startPtsUs: Long, endPtsUs: Long, x: Int, y: Int, w: Int, h: Int, argb: IntArray?, canvasW: Int, canvasH: Int) {
                assHandler.onBitmapSubtitle(trackId, startPtsUs, endPtsUs, x, y, w, h, argb, canvasW, canvasH)
            }

            override fun onFontAttachment(name: String, data: ByteArray) {
                assHandler.onFontAttachment(name, data)
            }

            override fun onFrameRendered(ptsUs: Long) {
                assHandler.setIsBuffering(false)
            }
        }
    }

    private fun handlePlaybackEnded() {
        if (repeatMode == RepeatMode.ONE) {
            seekTo(0)
            resume()
            return
        }
        val playlist = currentPlaylist
        if (playlist != null && playlist.isNotEmpty()) {
            if (currentPlaylistIndex + 1 < playlist.size) {
                skipToNext()
            } else if (repeatMode == RepeatMode.ALL) {
                seekToMediaItem(0)
            }
        }
    }

    override fun play(
        uri: String,
        title: String,
        artist: String?,
        isVideo: Boolean,
        mimeType: String?,
        resumePositionMs: Long,
        headers: Map<String, String>,
        artworkUri: String?
    ) {
        playInternal(
            uri = uri,
            title = title,
            artist = artist,
            isVideo = isVideo,
            mimeType = mimeType,
            resumePositionMs = resumePositionMs,
            headers = headers,
            artworkUri = artworkUri,
            preservePlaylist = false,
        )
    }

    private fun playInternal(
        uri: String,
        title: String,
        artist: String? = null,
        isVideo: Boolean = true,
        mimeType: String? = null,
        resumePositionMs: Long = 0L,
        headers: Map<String, String> = emptyMap(),
        artworkUri: String? = null,
        preservePlaylist: Boolean = false,
    ) {
        if (released) return
        val requestId = playbackGeneration.incrementAndGet()
        synchronized(releaseLock) {
            if (released) return
            currentUri = uri
            currentTitle = title
            currentArtist = artist
            currentHeaders = headers
        }
        if (!isCurrentRequest(requestId, uri)) return
        if (!preservePlaylist) {
            currentPlaylist = null
        }
        pendingSeekTargetMs.set(-1L)
        pendingSeekBackward = false
        assHandler.player = null
        assHandler.playbackSpeed = currentSpeed
        assHandler.reset()
        assHandler.positionClock = ::clockPositionUs
        _playbackState.update {
            it.copy(
                state = PlayerState.BUFFERING,
                currentTitle = title,
                currentArtist = artist,
                currentUri = uri,
                errorMessage = null
            )
        }

        playJob?.cancel()
        playJob = engineScope.launch {
            val opened = openAndStart(uri, resumePositionMs, headers, requestId)
            if (opened && isCurrentRequest(requestId, uri)) {
                // Native playback owns its readiness state. Subtitle discovery
                // runs after opening and must not overwrite READY with BUFFERING.
                val subs = neighborSubtitleDiscoverer.discover(uri) { }
                if (subs.isNotEmpty() && isCurrentRequest(requestId, uri)) {
                    withContext(Dispatchers.Main) {
                        for (sub in subs) {
                            addExternalSubtitle(sub.uri)
                        }
                    }
                }
            }
        }
    }

    override fun playPlaylist(items: List<Pair<String, String>>, startIndex: Int, startPositionMs: Long) {
        if (items.isEmpty()) return
        currentPlaylist = items
        currentPlaylistIndex = startIndex.coerceIn(0, items.lastIndex)
        val (uri, title) = items[currentPlaylistIndex]
        playInternal(uri, title, resumePositionMs = startPositionMs, preservePlaylist = true)
    }

    override fun playAudioPlaylist(items: List<AudioItem>, startIndex: Int) {
        if (items.isEmpty()) return
        val list = items.map { it.uri to it.title }
        playPlaylist(list, startIndex, 0L)
    }

    /**
     * Ask the active source to stop any blocking read without waiting for the
     * native worker join. This is intentionally separate from lifecycleLock so
     * release can cancel an open that currently owns that lock.
     */
    private fun abortActiveBridge() {
        val bridge = synchronized(bridgeLock) { activeBridge }
        runCatching { bridge?.abortRead() }
            .onFailure { error -> Log.w(TAG, "Failed to abort media source read: ${error.message}", error) }
    }

    /**
     * Close [activeBridge] and return its SMB pool checkout (if any). Single
     * teardown path shared by [openAndStart], [stop] and [release].
     */
    private fun closeActiveBridge() {
        val detached = synchronized(bridgeLock) {
            val bridge = activeBridge
            val checkout = activeSmbCheckout
            activeBridge = null
            activeSmbCheckout = null
            bridge to checkout
        }
        val bridge = detached.first
        val checkout = detached.second
        (bridge as? Closeable)?.let { runCatching { it.close() } }
        // SmbRandomAccessSource owns deferred lease release through its
        // onQuiesced callback; other bridge types can release synchronously.
        if (bridge !is SmbRandomAccessSource) {
            checkout?.lease?.release()
        }
    }

    private fun stopNativeAndCloseBridge(): Boolean {
        return try {
            abortActiveBridge()
            player.stop()
            closeActiveBridge()
            true
        } catch (error: Throwable) {
            Log.e(TAG, "Native stop failed; source was kept open: ${error.message}", error)
            false
        }
    }

    private fun publishActiveBridge(
        bridge: RandomAccessMediaSource?,
        smbCheckout: SmbCheckout?,
        requestId: Long,
        uri: String,
    ): Boolean = synchronized(bridgeLock) {
        if (!isCurrentRequest(requestId, uri)) return@synchronized false
        activeBridge = bridge
        activeSmbCheckout = smbCheckout
        true
    }

    private fun isCurrentRequest(requestId: Long, uri: String): Boolean =
        !released && playbackGeneration.get() == requestId && currentUri == uri

    private fun reportOpenFailure(requestId: Long, uri: String) {
        if (!isCurrentRequest(requestId, uri)) return
        assHandler.setIsPlaying(false)
        _playbackState.update {
            it.copy(
                state = PlayerState.ERROR,
                isPlaying = false,
                errorMessage = "Failed to open media"
            )
        }
    }

    /** Releases [surfaceReadyLatch] exactly once per attach; safe to call repeatedly. */
    private fun markSurfaceReady() {
        surfaceReadyLatch.countDown()
    }

    /** Re-arms the surface gate after the render view's surface goes away. */
    private fun resetSurfaceReady() {
        if (surfaceReadyLatch.count == 0L) {
            surfaceReadyLatch = CountDownLatch(1)
        }
    }

    private fun openAndStart(
        uriString: String,
        startPositionMs: Long,
        headers: Map<String, String> = currentHeaders,
        requestId: Long,
    ): Boolean = synchronized(lifecycleLock) {
        if (!isCurrentRequest(requestId, uriString)) return@synchronized false
        if (!stopNativeAndCloseBridge()) {
            reportOpenFailure(requestId, uriString)
            return@synchronized false
        }

        val uri = Uri.parse(uriString)
        val scheme = uri.scheme?.lowercase() ?: ""

        var bridge: RandomAccessMediaSource? = null
        var smbCheckout: SmbCheckout? = null
        var directUrl: String? = null

        try {
            when {
                scheme == "content" -> {
                    val pfd = appContext.contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        val channel = FileInputStream(pfd.fileDescriptor).channel
                        val size = if (pfd.statSize > 0) pfd.statSize else runCatching { channel.size() }.getOrDefault(0L)
                        bridge = ChannelRandomAccessSource(channel, size) { runCatching { pfd.close() } }
                    }
                }
                scheme == "smb" -> {
                    val androidUri = uri
                    val (username, password) = androidUri.userInfoPair()
                    val host = androidUri.host ?: ""
                    val port = if (androidUri.port > 0) androidUri.port else 445
                    val segments = SmbPathResolver.decodedSegmentsOf(androidUri.encodedPath)
                    val lease = ConnectionPool.borrowSmbContextLease(host, port, username, password)
                    try {
                        val file = SmbPathResolver.resolve(lease.context, host, port, segments)
                        if (file != null) {
                            bridge = SmbRandomAccessSource(
                                file = file,
                                fileSize = file.length(),
                                lightweight = false,
                                onQuiesced = { lease.release() },
                            )
                            smbCheckout = SmbCheckout(lease)
                        }
                    } finally {
                        // Resolve/bridge-open failed — release the exact lease now.
                        if (bridge == null) lease.release()
                    }
                }
                scheme == "archive" -> {
                    val parsed = ArchiveUri.parse(uriString)
                    if (parsed != null) {
                        val (container, entry, password) = parsed
                        bridge = ArchiveRandomAccessSource(container, entry, password)
                    }
                }
                scheme == "file" -> {
                    val path = uri.path ?: uriString.removePrefix("file://")
                    bridge = LocalRandomAccessSource(path)
                }
                scheme.isEmpty() || uriString.startsWith("/") -> {
                    bridge = LocalRandomAccessSource(uriString)
                }
                scheme == "http" || scheme == "https" -> {
                    directUrl = uriString
                }
                else -> {
                    directUrl = uriString
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to resolve URI source: ${error.message}", error)
        }

        if (!isCurrentRequest(requestId, uriString)) {
            runCatching { (bridge as? Closeable)?.close() }
            if (bridge !is SmbRandomAccessSource) smbCheckout?.lease?.release()
            return@synchronized false
        }

        if (!publishActiveBridge(bridge, smbCheckout, requestId, uriString)) {
            runCatching { (bridge as? Closeable)?.close() }
            if (bridge !is SmbRandomAccessSource) smbCheckout?.lease?.release()
            return@synchronized false
        }
        selectedAudioTrackIndex = 0

        // A render view (SurfaceView/TextureView) was attached via createRenderView(),
        // meaning video output is expected, but its underlying Surface is created
        // asynchronously by the platform and may not exist yet. Opening native
        // playback before that Surface lands means nativeWindow stays null: audio
        // starts fine but every decoded video frame is silently dropped until
        // something later re-triggers surfaceChanged (e.g. a seek). Block briefly
        // here so play() only starts once the surface (or the timeout) is reached —
        // pure-audio playback (no render view ever attached) skips this entirely.
        if (activeRenderViewRef != null && activeSurface == null) {
            val arrived = surfaceReadyLatch.await(SURFACE_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!arrived) {
                Log.w(TAG, "Timed out waiting for render surface; opening without it")
            }
        }

        val success = try {
            if (!player.open(bridge, directUrl, activeSurface, startPositionMs, headers)) {
                false
            } else {
                player.setSpeed(currentSpeed)
                val audioDelay = getAudioDelay()
                if (audioDelay != 0L) {
                    player.setAudioDelay(audioDelay)
                }
                val eqInfo = equalizerController.state.value
                val gains = eqInfo.bands.map { it.levelMb }.toIntArray()
                player.setEqualizer(eqInfo.enabled, gains)
                player.play()
                true
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to start media: ${error.message}", error)
            false
        }

        if (success && isCurrentRequest(requestId, uriString)) return@synchronized true

        // The native open/start path may have created worker threads before an
        // exception surfaced. Stop them before closing their Kotlin source.
        stopNativeAndCloseBridge()
        reportOpenFailure(requestId, uriString)
        false
    }

    override fun pause() {
        player.pause()
        assHandler.setIsPlaying(false)
        _playbackState.update { it.copy(isPlaying = false) }
    }

    override fun resume() {
        player.play()
        assHandler.setIsPlaying(true)
        _playbackState.update { it.copy(isPlaying = true) }
    }

    override fun stop() {
        val stopRequestId = playbackGeneration.incrementAndGet()
        playJob?.cancel()
        playJob = null

        // Stop render-time callbacks immediately, but never wait for native
        // joins or lifecycleLock on the Android/UI caller thread.
        assHandler.setIsPlaying(false)
        assHandler.setIsBuffering(false)
        abortActiveBridge()

        engineScope.launch(Dispatchers.IO) {
            val stopped = synchronized(lifecycleLock) {
                // A newer play/reopen owns the lifecycle now. Its own
                // openAndStart() will serialize the required old-session stop.
                if (released || playbackGeneration.get() != stopRequestId) {
                    true
                } else {
                    stopNativeAndCloseBridge()
                }
            }

            withContext(Dispatchers.Main.immediate) {
                // Do not let an old asynchronous stop overwrite a newer open,
                // or update state after release has begun.
                if (released || playbackGeneration.get() != stopRequestId) return@withContext

                if (!stopped) {
                    _playbackState.update {
                        it.copy(
                            state = PlayerState.ERROR,
                            isPlaying = false,
                            errorMessage = "Failed to stop media"
                        )
                    }
                    return@withContext
                }

                currentUri = null
                currentTitle = null
                selectedAudioTrackIndex = 0
                videoWidth = 0
                videoHeight = 0
                videoRotation = 0
                sarNum = 1
                sarDen = 1
                pendingSeekTargetMs.set(-1L)
                pendingSeekBackward = false
                assHandler.reset()
                _playbackState.update {
                    it.copy(
                        state = PlayerState.IDLE,
                        isPlaying = false,
                        currentTitle = null,
                        currentArtist = null,
                        currentUri = null
                    )
                }
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        val clamped = clampSeekPosition(positionMs, getDuration())
        val targetUs = clamped * 1000L
        val nowUs = SystemClock.elapsedRealtime() * 1000L
        val currentPosition = player.getPosition()
        pendingSeekBackward = clamped < currentPosition
        pendingSeekTargetMs.set(clamped)
        _playbackState.update { it.copy(state = PlayerState.BUFFERING) }
        assHandler.setIsBuffering(true)
        assHandler.setIsPlaying(false)
        player.seekTo(clamped)
        assHandler.updatePosition(targetUs, nowUs)
        assHandler.onSeek(clamped)
    }

    override fun setScrubbing(isScrubbing: Boolean) {
        player.setScrubbing(isScrubbing)
    }

    fun setFastSeek(enabled: Boolean) {
        player.setFastSeek(enabled)
    }

    override fun skipToNext() {
        val playlist = currentPlaylist ?: return
        if (currentPlaylistIndex + 1 < playlist.size) {
            currentPlaylistIndex++
            val (uri, title) = playlist[currentPlaylistIndex]
            playInternal(uri, title, preservePlaylist = true)
        }
    }

    override fun skipToPrevious() {
        val playlist = currentPlaylist ?: return
        if (currentPlaylistIndex > 0) {
            currentPlaylistIndex--
            val (uri, title) = playlist[currentPlaylistIndex]
            playInternal(uri, title, preservePlaylist = true)
        }
    }

    override fun getCurrentMediaItemIndex(): Int = currentPlaylistIndex
    override fun getMediaItemCount(): Int = currentPlaylist?.size ?: 1

    override fun seekToMediaItem(index: Int) {
        val playlist = currentPlaylist ?: return
        if (index in playlist.indices) {
            currentPlaylistIndex = index
            val (uri, title) = playlist[index]
            playInternal(uri, title, preservePlaylist = true)
        }
    }

    override fun isPlaying(): Boolean = player.isPlaying()
    override fun getDuration(): Long = player.getDuration()
    override fun getCurrentPosition(): Long = player.getPosition()
    override fun getBufferedPosition(): Long = player.getPosition()

    override fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        player.setSpeed(speed)
        assHandler.playbackSpeed = speed
        _playbackState.update { it.copy(playbackSpeed = speed) }
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        isShuffle = enabled
        _playbackState.update { it.copy(shuffleModeEnabled = enabled) }
    }

    override fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        _playbackState.update { it.copy(repeatMode = mode) }
    }

    override fun isShuffleEnabled(): Boolean = isShuffle
    override fun getRepeatMode(): RepeatMode = repeatMode

    @Volatile
    private var currentDecoderMode: DecoderMode = DecoderMode.AUTO

    override fun setDecoderMode(mode: DecoderMode) {
        val prevMode = currentDecoderMode
        currentDecoderMode = mode
        val useHw = (mode != DecoderMode.SOFTWARE)
        player.setHardwareAcceleration(useHw)
        Log.d(TAG, "setDecoderMode: $mode (hardwareAcceleration=$useHw)")

        if (prevMode != mode) {
            val uri = currentUri
            val title = currentTitle
            if (uri != null && title != null && _playbackState.value.state != PlayerState.IDLE) {
                val pos = getCurrentPosition()
                val wasPlaying = isPlaying()
                play(uri, title, resumePositionMs = pos, headers = currentHeaders)
                if (!wasPlaying) {
                    pause()
                }
            }
        }
    }

    override fun setFfmpegPreferred(preferred: Boolean) {}

    override fun setDisableHdr(disabled: Boolean) {
        player.setForceSdr(disabled)
    }

    // ─── Subtitles ──────────────────────────────────────────────────────────

    override fun getSubtitleTracks(): List<String> = assHandler.getAllTrackNames()
    override fun getSelectedSubtitleTrack(): Int = assHandler.getActiveTrackIndex()

    override fun selectSubtitleTrack(index: Int) {
        val allIds = assHandler.getAllTrackIds()
        if (index in allIds.indices) {
            player.selectSubtitleTrack(index)
            assHandler.selectTrack(allIds[index])
            assHandler.onAssTrackSelected?.invoke()
        } else {
            player.selectSubtitleTrack(-1)
            assHandler.clearOverlay()
        }
    }

    override fun loadExternalAss(uri: Uri) {
        addExternalSubtitle(uri)
    }

    override fun addExternalSubtitle(uri: Uri): Boolean {
        val ext = (uri.path ?: "").substringAfterLast('.').lowercase()
        val mimeType = ExoMediaItemHelper.inferSubtitleMimeType(uri)
        val displayName = uri.lastPathSegment ?: uri.toString()

        engineScope.launch {
            val data = ExoMediaItemHelper.readSubtitleUriBytes(appContext, playerHolder, uri) ?: return@launch
            val assBytes = if (ext == "ass" || ext == "ssa") {
                data
            } else if (isNonAssSubtitleMimeType(mimeType)) {
                SubtitleConverters.convertToAss(data, mimeType, videoWidth, videoHeight)
            } else {
                null
            }

            if (assBytes != null) {
                withContext(Dispatchers.Main) {
                    assHandler.loadExternalTrack(assBytes, displayName)
                    subtitleTrackChangeListener?.invoke()
                }
            }
        }
        return true
    }

    override fun setSubtitleDelay(delayMs: Long) {
        subtitleDelay = delayMs
        assHandler.subtitleDelayMs = delayMs
    }

    override fun getSubtitleDelay(): Long = subtitleDelay

    // ─── Audio Tracks ───────────────────────────────────────────────────────

    override fun getAudioTracks(): List<String> = player.getAudioTracks()
    override fun getSelectedAudioTrack(): Int = selectedAudioTrackIndex

    override fun selectAudioTrack(index: Int) {
        val tracks = getAudioTracks()
        if (index in tracks.indices) {
            val success = player.selectAudioTrack(index)
            if (success) {
                selectedAudioTrackIndex = index
            }
        }
    }

    override fun setAudioDelay(delayMs: Long) {
        player.setAudioDelay(delayMs)
    }

    override fun getAudioDelay(): Long = player.getAudioDelay()

    // ─── Equalizer ──────────────────────────────────────────────

    override fun getEqualizerState(): StateFlow<EqualizerInfo>? = equalizerController.state

    override fun setEqualizerEnabled(enabled: Boolean) = equalizerController.setEnabled(enabled)

    override fun setEqualizerBandLevel(band: Int, levelMb: Int) =
        equalizerController.setBandLevel(band, levelMb)

    override fun applyEqualizerPreset(preset: Int) = equalizerController.applyPreset(preset)

    override fun resetEqualizerBands() = equalizerController.resetBands()

    override fun setBassBoostStrength(strength: Int) =
        equalizerController.setBassBoostStrength(strength)

    override fun setLoudnessGain(gainMb: Int) = equalizerController.setLoudnessGain(gainMb)

    override fun clearError() {
        _playbackState.update { it.copy(errorMessage = null) }
    }

    override fun retry() {
        val uri = currentUri ?: return
        val title = currentTitle ?: ""
        play(uri, title, resumePositionMs = getCurrentPosition(), headers = currentHeaders)
    }

    override fun release() {
        // Invalidate pending opens before waiting for the lifecycle lock. A
        // blocking open will stop and close its own source before release gets
        // the native player.
        playbackGeneration.incrementAndGet()
        synchronized(releaseLock) {
            released = true
            currentUri = null
        }
        playJob?.cancel()
        playJob = null
        engineScope.cancel()

        // release() cannot use player.release() to interrupt a pending
        // nativeOpen because the JNI lifecycle guard drains that call first.
        // Abort the published bridge before waiting for lifecycleLock.
        abortActiveBridge()

        synchronized(lifecycleLock) {
            // reset() nulls positionClock, stops the render loop and clears
            // overlay state — it must run before native context teardown.
            assHandler.reset()
            val nativeReleased = runCatching { player.release() }
                .onFailure { error -> Log.e(TAG, "Failed to release native player: ${error.message}", error) }
            if (nativeReleased.isSuccess) {
                closeActiveBridge()
            }
        }
    }

    /**
     * Position source for [AssHandler.positionClock], invoked on the render thread
     * every frame while playing. Returns µs, or -1 when unavailable. releaseLock
     * guarantees it can never call into a released native player: [release] sets
     * the flag under the same lock, so a pull either finishes first or bails out.
     */
    private fun clockPositionUs(): Long {
        if (released) return -1L
        synchronized(releaseLock) {
            if (released) return -1L
            return if (currentUri != null) player.getPosition() * 1000L else -1L
        }
    }

    // ─── Render View Seam ───────────────────────────────────────────────────

    private class AspectRatioLayout(context: Context) : FrameLayout(context) {
        var aspectRatio: Float = 0f
            set(value) {
                if (field != value) {
                    field = value
                    requestLayout()
                }
            }

        var resizeMode: AspectRatioMode = AspectRatioMode.AUTO
            set(value) {
                if (field != value) {
                    field = value
                    requestLayout()
                }
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            // Size the children off the incoming MeasureSpec, not measuredWidth/Height. During a
            // rotation transition super.onMeasure() can report a stale/intermediate container size
            // (the window hasn't finished resizing), and a SurfaceView latches its buffer geometry
            // from whatever child size we hand it that pass — leaving a stretched frame that never
            // self-corrects because the DAR/mode don't change on rotation. The spec size is the
            // dimension the parent is assigning this pass, so it is the authoritative target.
            val specW = MeasureSpec.getSize(widthMeasureSpec)
            val specH = MeasureSpec.getSize(heightMeasureSpec)
            val parentWidth = if (specW > 0) specW else measuredWidth
            val parentHeight = if (specH > 0) specH else measuredHeight
            if (parentWidth <= 0 || parentHeight <= 0) return
            // Keep our own measured size in sync with the target so onLayout centers correctly.
            setMeasuredDimension(parentWidth, parentHeight)

            val ratio = if (aspectRatio > 0f) aspectRatio else (16f / 9f)
            val containerRatio = parentWidth.toFloat() / parentHeight.toFloat()
            val targetRatio = when (resizeMode) {
                AspectRatioMode.AUTO -> ratio
                AspectRatioMode.RATIO_16_9 -> 16f / 9f
                AspectRatioMode.RATIO_4_3 -> 4f / 3f
                AspectRatioMode.RATIO_21_9 -> 21f / 9f
                AspectRatioMode.RATIO_18_9 -> 18f / 9f
                AspectRatioMode.STRETCH -> containerRatio
                AspectRatioMode.ZOOM -> ratio
            }

            val (childW, childH) = if (resizeMode == AspectRatioMode.STRETCH) {
                parentWidth to parentHeight
            } else if (resizeMode == AspectRatioMode.AUTO && aspectRatio <= 0f) {
                // AUTO but the true DAR is not yet known: fill the container instead of boxing
                // to a hardcoded 16:9. applyAspectRatio() re-lays out once the DAR resolves.
                parentWidth to parentHeight
            } else if (resizeMode == AspectRatioMode.ZOOM) {
                if (targetRatio > containerRatio) {
                    val h = parentHeight
                    val w = Math.round(h * targetRatio)
                    w to h
                } else {
                    val w = parentWidth
                    val h = Math.round(w / targetRatio)
                    w to h
                }
            } else {
                // FIT / AUTO / specific fixed ratios
                if (targetRatio > containerRatio) {
                    val w = parentWidth
                    val h = Math.round(w / targetRatio)
                    w to h
                } else {
                    val h = parentHeight
                    val w = Math.round(h * targetRatio)
                    w to h
                }
            }

            val childWidthSpec = MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY)
            val childHeightSpec = MeasureSpec.makeMeasureSpec(childH, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) {
                getChildAt(i).measure(childWidthSpec, childHeightSpec)
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val parentWidth = right - left
            val parentHeight = bottom - top
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility != GONE) {
                    val childWidth = child.measuredWidth
                    val childHeight = child.measuredHeight
                    val childLeft = (parentWidth - childWidth) / 2
                    val childTop = (parentHeight - childHeight) / 2
                    child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
                }
            }
        }

        // The DAR and resize mode do not change on device rotation, so the setters that guard
        // requestLayout() never fire — only the container size changes. Re-request a layout on
        // every size change so a late/settled post-rotation size re-runs onMeasure with the real
        // container dimensions instead of leaving the child sized against the pre-rotation pass.
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w != oldw || h != oldh) requestLayout()
        }
    }

    override fun createRenderView(context: Context, useSurfaceView: Boolean): View {
        val frameLayout = AspectRatioLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val renderView: View = if (useSurfaceView) {
            val surfaceView = SurfaceView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    activeSurface = holder.surface
                    player.setSurface(holder.surface)
                    markSurfaceReady()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    if (activeSurface == holder.surface) {
                        activeSurface = null
                        player.setSurface(null)
                        resetSurfaceReady()
                    }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    activeSurface = holder.surface
                    player.setSurface(holder.surface)
                    markSurfaceReady()
                }
            })

            if (surfaceView.holder.surface?.isValid == true) {
                activeSurface = surfaceView.holder.surface
                player.setSurface(surfaceView.holder.surface)
                markSurfaceReady()
            }

            surfaceView
        } else {
            val textureView = TextureView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                private var surf: Surface? = null

                override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                    val s = Surface(surfaceTexture)
                    surf = s
                    activeSurface = s
                    player.setSurface(s)
                    markSurfaceReady()
                }

                override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                    surf?.let { player.setSurface(it) }
                }

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                    if (activeSurface == surf) {
                        activeSurface = null
                        player.setSurface(null)
                        resetSurfaceReady()
                    }
                    surf?.release()
                    surf = null
                    return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
            }

            if (textureView.isAvailable && textureView.surfaceTexture != null) {
                val s = Surface(textureView.surfaceTexture)
                activeSurface = s
                player.setSurface(s)
                markSurfaceReady()
            }

            textureView
        }

        frameLayout.addView(renderView)
        activeRenderViewRef = WeakReference(renderView)
        activeContainerRef = WeakReference(frameLayout)

        applyAspectRatio()

        return frameLayout
    }

    override fun updateRenderView(view: View, config: RenderViewConfig) {
        currentAspectRatioMode = config.aspectRatioMode
        applyAspectRatio()
    }

    private fun applyAspectRatio() {
        var vw = videoWidth.toFloat()
        var vh = videoHeight.toFloat()
        if (vw <= 0 || vh <= 0) {
            val pw = player.getVideoWidth().toFloat()
            val ph = player.getVideoHeight().toFloat()
            if (pw > 0 && ph > 0) {
                videoWidth = pw.toInt()
                videoHeight = ph.toInt()
                videoRotation = player.getVideoRotation()
                sarNum = player.getSarNum()
                sarDen = player.getSarDen()
                vw = pw
                vh = ph
            }
        }

        // Display aspect ratio: (width × SAR) : height, with a 90°/270° rotation swap applied
        // in the correct order (SAR first, then rotation). Extracted for unit testing.
        val autoRatio = computeDisplayAspectRatio(
            videoWidth = vw.toInt(),
            videoHeight = vh.toInt(),
            sarNum = sarNum,
            sarDen = sarDen,
            rotationDegrees = videoRotation
        )

        val updateDimensions = Runnable {
            val container = activeContainerRef?.get() as? AspectRatioLayout ?: return@Runnable
            // Always apply the current mode.
            container.resizeMode = currentAspectRatioMode
            // Only commit the AUTO ratio once it is actually known; leave it unset (0f) until
            // then so onMeasure uses the full-container fallback instead of a wrong ratio.
            if (autoRatio > 0f) {
                container.aspectRatio = autoRatio
            }
            // Always re-layout so the interim full-container fallback is corrected to the true
            // DAR exactly once the dimensions/SAR resolve (the aspectRatio setter short-circuits
            // equal values, so an unconditional requestLayout is required here).
            container.requestLayout()
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateDimensions.run()
        } else {
            mainHandler.post(updateDimensions)
        }
    }

    override fun onRenderViewPaused(view: View) {}
    override fun onRenderViewResumed(view: View) {}

    override fun getMedia3Player(): Player? = null
    override fun setOnPlayerReplacedListener(listener: ((Player) -> Unit)?) {}

    private var lastRenderedFrames: Long = 0L
    private var lastFrameTimestamp: Long = 0L

    override fun getDebugStats(): DebugStats? {
        val info = player.getDebugInfo() ?: return null
        val rendered = info.renderedFrames
        val now = System.nanoTime()
        val renderedFps = if (lastFrameTimestamp > 0 && lastRenderedFrames > 0) {
            val dt = (now - lastFrameTimestamp) / 1_000_000_000f
            val df = rendered - lastRenderedFrames
            if (dt > 0f && df >= 0) "%.2f fps".format(df / dt) else ""
        } else ""
        lastRenderedFrames = rendered
        lastFrameTimestamp = now

        val isHw = info.videoCodec.contains("MediaCodec", ignoreCase = true)
        return DebugStats(
            videoCodec = info.videoCodec,
            videoCodecMime = if (info.videoCodec.isNotEmpty()) "video/ffmpeg-${info.videoCodec}" else "",
            resolution = info.resolution,
            videoBitrate = if (info.videoBitrate > 0) "${info.videoBitrate / 1000} kbps" else "",
            frameRate = if (info.frameRate > 0f) "%.2f fps".format(info.frameRate) else "",
            decoderName = if (info.videoCodec.isNotEmpty()) "FFmpeg Native (${info.videoCodec})" else "",
            decoderInfo = if (isHw) "Hardware Accelerated (AMediaCodec Zero-Copy)" else "Software Decoded (CPU multithreaded)",
            videoDecoderLabel = if (isHw) "Hardware (${info.videoCodec})" else "Software (${info.videoCodec})",
            audioDecoderLabel = if (info.audioCodec.isNotEmpty()) "Software (${info.audioCodec})" else "",
            audioCodec = info.audioCodec,
            audioCodecMime = if (info.audioCodec.isNotEmpty()) "audio/ffmpeg-${info.audioCodec}" else "",
            audioBitrate = if (info.audioBitrate > 0) "${info.audioBitrate / 1000} kbps" else "",
            sampleRate = if (info.sampleRate > 0) "${info.sampleRate} Hz" else "",
            channelCount = if (info.channels > 0) "${info.channels} ch" else "",
            audioLanguage = info.audioLanguage,
            renderedFps = renderedFps,
            droppedFrames = info.droppedFrames.toString(),
            deviceModel = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            soCInfo = Build.HARDWARE,
            isVisible = true
        )
    }
}
