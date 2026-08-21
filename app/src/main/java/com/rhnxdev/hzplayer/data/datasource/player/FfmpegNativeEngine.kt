package com.rhnxdev.hzplayer.data.datasource.player

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
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
import com.rhnxdev.hzplayer.core.thumbnail.ArchiveRandomAccessBridge
import com.rhnxdev.hzplayer.core.thumbnail.ChannelRandomAccessBridge
import com.rhnxdev.hzplayer.core.thumbnail.LocalRandomAccessBridge
import com.rhnxdev.hzplayer.core.thumbnail.RandomAccessBridge
import com.rhnxdev.hzplayer.core.thumbnail.ThumbnailSource
import com.rhnxdev.hzplayer.core.util.ArchiveUri
import com.rhnxdev.hzplayer.data.datasource.player.ffmpeg.FfmpegNativePlayer
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.AssHandler
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.SubtitleConverters
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
import java.io.Closeable
import java.io.FileInputStream
import java.lang.ref.WeakReference
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
    }

    override val engineType: EngineType = EngineType.NATIVE_FFMPEG

    private val player = FfmpegNativePlayer()
    private val _playbackState = MutableStateFlow(PlayerStateInfo())
    override val playbackState: StateFlow<PlayerStateInfo> = _playbackState.asStateFlow()

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activeSurface: Surface? = null
    private var activeBridge: ThumbnailSource? = null
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
    @Volatile
    private var pendingSeekTargetMs: Long = -1L

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
                    val seekTarget = pendingSeekTargetMs
                    if (seekTarget < 0L || curPos >= seekTarget - 500L) {
                        pendingSeekTargetMs = -1L
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
                val seekTarget = pendingSeekTargetMs
                if (seekTarget >= 0L) {
                    if (positionMs < seekTarget - 500L) {
                        // Drop stale position updates from pre-seek frames or callbacks
                        return
                    } else {
                        pendingSeekTargetMs = -1L
                    }
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
        currentUri = uri
        currentTitle = title
        currentArtist = artist
        currentHeaders = headers
        if (!preservePlaylist) {
            currentPlaylist = null
        }
        pendingSeekTargetMs = -1L
        assHandler.player = null
        assHandler.playbackSpeed = currentSpeed
        assHandler.reset()
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
            openAndStart(uri, resumePositionMs, headers)
            if (currentUri == uri) {
                val subs = neighborSubtitleDiscoverer.discover(uri)
                if (subs.isNotEmpty() && currentUri == uri) {
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

    private fun openAndStart(uriString: String, startPositionMs: Long, headers: Map<String, String> = currentHeaders) {
        (activeBridge as? Closeable)?.let { runCatching { it.close() } }
        activeBridge = null

        val uri = Uri.parse(uriString)
        val scheme = uri.scheme?.lowercase() ?: ""

        var bridge: ThumbnailSource? = null
        var directUrl: String? = null

        try {
            when {
                scheme == "content" -> {
                    val pfd = appContext.contentResolver.openFileDescriptor(uri, "r")
                    if (pfd != null) {
                        val channel = FileInputStream(pfd.fileDescriptor).channel
                        val size = if (pfd.statSize > 0) pfd.statSize else runCatching { channel.size() }.getOrDefault(0L)
                        bridge = ChannelRandomAccessBridge(channel, size) { runCatching { pfd.close() } }
                    }
                }
                scheme == "smb" -> {
                    val androidUri = uri
                    val username = Uri.decode(androidUri.userInfo?.substringBefore(':') ?: "")
                    val password = Uri.decode(androidUri.userInfo?.substringAfter(':', "") ?: "")
                    val host = androidUri.host ?: ""
                    val port = if (androidUri.port > 0) androidUri.port else 445
                    val segments = SmbPathResolver.decodedSegmentsOf(androidUri.encodedPath)
                    val ctx = ConnectionPool.borrowSmbThumbnailContext(host, port, username, password)
                    val file = SmbPathResolver.resolve(ctx, host, port, segments)
                    if (file != null) {
                        bridge = RandomAccessBridge(file, file.length(), lightweight = false)
                    }
                }
                scheme == "archive" -> {
                    val parsed = ArchiveUri.parse(uriString)
                    if (parsed != null) {
                        val (container, entry, password) = parsed
                        bridge = ArchiveRandomAccessBridge(container, entry, password)
                    }
                }
                scheme == "file" -> {
                    val path = uri.path ?: uriString.removePrefix("file://")
                    bridge = LocalRandomAccessBridge(path)
                }
                scheme.isEmpty() || uriString.startsWith("/") -> {
                    bridge = LocalRandomAccessBridge(uriString)
                }
                scheme == "http" || scheme == "https" -> {
                    directUrl = uriString
                }
                else -> {
                    directUrl = uriString
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve URI source: ${e.message}", e)
        }

        activeBridge = bridge
        selectedAudioTrackIndex = 0

        val success = player.open(bridge, directUrl, activeSurface, startPositionMs, headers)
        if (success) {
            player.setSpeed(currentSpeed)
            val audioDelay = getAudioDelay()
            if (audioDelay != 0L) {
                player.setAudioDelay(audioDelay)
            }
            val eqInfo = equalizerController.state.value
            val gains = eqInfo.bands.map { it.levelMb }.toIntArray()
            player.setEqualizer(eqInfo.enabled, gains)
            player.play()
            _playbackState.update {
                it.copy(
                    state = PlayerState.BUFFERING,
                    isPlaying = true
                )
            }
        } else {
            assHandler.setIsPlaying(false)
            _playbackState.update {
                it.copy(
                    state = PlayerState.ERROR,
                    isPlaying = false,
                    errorMessage = "Failed to open media"
                )
            }
        }
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
        playJob?.cancel()
        playJob = null
        player.stop()
        assHandler.setIsPlaying(false)
        (activeBridge as? Closeable)?.let { runCatching { it.close() } }
        activeBridge = null
        currentUri = null
        currentTitle = null
        selectedAudioTrackIndex = 0
        videoWidth = 0
        videoHeight = 0
        videoRotation = 0
        sarNum = 1
        sarDen = 1
        pendingSeekTargetMs = -1L
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

    override fun seekTo(positionMs: Long) {
        val duration = getDuration().takeIf { it > 0 } ?: Long.MAX_VALUE
        val clamped = positionMs.coerceIn(0, duration)
        val targetUs = clamped * 1000L
        val nowUs = SystemClock.elapsedRealtime() * 1000L
        pendingSeekTargetMs = clamped
        _playbackState.update { it.copy(state = PlayerState.BUFFERING) }
        assHandler.setIsBuffering(true)
        assHandler.setIsPlaying(false)
        player.seekTo(clamped)
        assHandler.updatePosition(targetUs, nowUs)
        assHandler.onSeek(clamped)
    }

    override fun skipForward(ms: Long) {
        val duration = getDuration().takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (getCurrentPosition() + ms).coerceAtMost(duration)
        seekTo(target)
    }

    override fun skipBackward(ms: Long) {
        val target = (getCurrentPosition() - ms).coerceAtLeast(0L)
        seekTo(target)
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
            } else if (SubtitleConverters.isConvertibleSubtitleFormat(mimeType)) {
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
        engineScope.cancel()
        player.release()
        (activeBridge as? Closeable)?.let { runCatching { it.close() } }
        activeBridge = null
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
            val parentWidth = measuredWidth
            val parentHeight = measuredHeight
            if (parentWidth <= 0 || parentHeight <= 0) return

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
            } else if (resizeMode == AspectRatioMode.ZOOM) {
                if (targetRatio > containerRatio) {
                    val h = parentHeight
                    val w = (h * targetRatio).toInt()
                    w to h
                } else {
                    val w = parentWidth
                    val h = (w / targetRatio).toInt()
                    w to h
                }
            } else {
                // FIT / AUTO / specific fixed ratios
                if (targetRatio > containerRatio) {
                    val w = parentWidth
                    val h = (w / targetRatio).toInt()
                    w to h
                } else {
                    val h = parentHeight
                    val w = (h * targetRatio).toInt()
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
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    if (activeSurface == holder.surface) {
                        activeSurface = null
                        player.setSurface(null)
                    }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    activeSurface = holder.surface
                    player.setSurface(holder.surface)
                }
            })

            if (surfaceView.holder.surface?.isValid == true) {
                activeSurface = surfaceView.holder.surface
                player.setSurface(surfaceView.holder.surface)
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
                }

                override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                    surf?.let { player.setSurface(it) }
                }

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                    if (activeSurface == surf) {
                        activeSurface = null
                        player.setSurface(null)
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

        val sar = if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen.toFloat() else 1.0f
        val isRotated90or270 = (videoRotation == 90 || videoRotation == 270)

        // Pixel dimensions adjusted for Sample Aspect Ratio (SAR)
        val unrotatedWidth = if (vw > 0) vw * sar else 0f
        val unrotatedHeight = if (vh > 0) vh else 0f

        // Display dimensions adjusted for display matrix rotation
        val displayW = if (isRotated90or270) unrotatedHeight else unrotatedWidth
        val displayH = if (isRotated90or270) unrotatedWidth else unrotatedHeight
        val autoRatio = if (displayW > 0 && displayH > 0) displayW / displayH else 0f

        val updateDimensions = Runnable {
            val container = activeContainerRef?.get() as? AspectRatioLayout ?: return@Runnable
            if (autoRatio > 0f) {
                container.aspectRatio = autoRatio
            }
            container.resizeMode = currentAspectRatioMode
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
