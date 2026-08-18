package com.rhnxdev.hzplayer.data.datasource.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.Player
import com.rhnxdev.hzplayer.core.thumbnail.ChannelRandomAccessBridge
import com.rhnxdev.hzplayer.core.thumbnail.LocalRandomAccessBridge
import com.rhnxdev.hzplayer.core.thumbnail.RandomAccessBridge
import com.rhnxdev.hzplayer.core.thumbnail.ThumbnailSource
import com.rhnxdev.hzplayer.data.datasource.player.ffmpeg.FfmpegNativePlayer
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.AssHandler
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.SubtitleConverters
import com.rhnxdev.hzplayer.domain.model.AspectRatioMode
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.DebugStats
import com.rhnxdev.hzplayer.domain.model.DecoderMode
import com.rhnxdev.hzplayer.domain.model.PlayerState
import com.rhnxdev.hzplayer.domain.model.PlayerStateInfo
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import com.rhnxdev.hzplayer.domain.player.RenderViewConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private var currentPlaylist: List<Pair<String, String>>? = null
    private var currentPlaylistIndex: Int = 0
    private var repeatMode: RepeatMode = RepeatMode.NONE
    private var isShuffle: Boolean = false
    private var currentSpeed: Float = 1.0f
    private var subtitleDelay: Long = 0L

    private var activeSurfaceViewRef: WeakReference<SurfaceView>? = null
    private var activeContainerRef: WeakReference<FrameLayout>? = null
    private var currentAspectRatioMode: AspectRatioMode = AspectRatioMode.AUTO
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    override var subtitleTrackChangeListener: (() -> Unit)? = null

    init {
        assHandler.onExternalTrackListChanged = {
            subtitleTrackChangeListener?.invoke()
        }

        player.listener = object : FfmpegNativePlayer.Listener {
            override fun onVideoSizeChanged(width: Int, height: Int) {
                videoWidth = width
                videoHeight = height
                assHandler.setVideoSize(width, height)
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

                _playbackState.update { current ->
                    current.copy(
                        state = mappedState,
                        isPlaying = (state == FfmpegNativePlayer.STATE_READY && player.isPlaying()),
                    )
                }
            }

            override fun onError(message: String) {
                Log.e(TAG, "Native player error: $message")
                _playbackState.update { current ->
                    current.copy(
                        state = PlayerState.ERROR,
                        isPlaying = false,
                        errorMessage = message
                    )
                }
            }

            override fun onPositionUpdate(positionMs: Long, durationMs: Long) {
                assHandler.updatePosition(positionMs * 1000, SystemClock.elapsedRealtimeNanos() / 1000)
                _playbackState.update { current ->
                    current.copy(
                        bufferedPosition = positionMs,
                        isPlaying = player.isPlaying()
                    )
                }
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
        currentUri = uri
        currentTitle = title
        currentArtist = artist
        currentPlaylist = null
        currentPlaylistIndex = 0

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

        engineScope.launch {
            val subs = neighborSubtitleDiscoverer.discover(uri)
            withContext(Dispatchers.Main) {
                for (sub in subs) {
                    addExternalSubtitle(sub.uri)
                }
                openAndStart(uri, resumePositionMs)
            }
        }
    }

    override fun playPlaylist(items: List<Pair<String, String>>, startIndex: Int, startPositionMs: Long) {
        if (items.isEmpty()) return
        currentPlaylist = items
        currentPlaylistIndex = startIndex.coerceIn(0, items.lastIndex)
        val (uri, title) = items[currentPlaylistIndex]
        play(uri, title, resumePositionMs = startPositionMs)
    }

    override fun playAudioPlaylist(items: List<AudioItem>, startIndex: Int) {
        if (items.isEmpty()) return
        val list = items.map { it.uri to it.title }
        playPlaylist(list, startIndex, 0L)
    }

    private fun openAndStart(uriString: String, startPositionMs: Long) {
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

        val success = player.open(bridge, directUrl, activeSurface, startPositionMs)
        if (success) {
            player.setSpeed(currentSpeed)
            player.play()
            _playbackState.update {
                it.copy(
                    state = PlayerState.READY,
                    isPlaying = true
                )
            }
        } else {
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
        _playbackState.update { it.copy(isPlaying = false) }
    }

    override fun resume() {
        player.play()
        _playbackState.update { it.copy(isPlaying = true) }
    }

    override fun stop() {
        player.stop()
        (activeBridge as? Closeable)?.let { runCatching { it.close() } }
        activeBridge = null
        currentUri = null
        currentTitle = null
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
        assHandler.onSeek()
        player.seekTo(positionMs)
    }

    override fun skipForward(ms: Long) {
        val target = (getCurrentPosition() + ms).coerceAtMost(getDuration())
        seekTo(target)
    }

    override fun skipBackward(ms: Long) {
        val target = (getCurrentPosition() - ms).coerceAtLeast(0L)
        seekTo(target)
    }

    override fun skipToNext() {
        val playlist = currentPlaylist ?: return
        if (currentPlaylistIndex + 1 < playlist.size) {
            currentPlaylistIndex++
            val (uri, title) = playlist[currentPlaylistIndex]
            play(uri, title)
        }
    }

    override fun skipToPrevious() {
        val playlist = currentPlaylist ?: return
        if (currentPlaylistIndex > 0) {
            currentPlaylistIndex--
            val (uri, title) = playlist[currentPlaylistIndex]
            play(uri, title)
        }
    }

    override fun getCurrentMediaItemIndex(): Int = currentPlaylistIndex
    override fun getMediaItemCount(): Int = currentPlaylist?.size ?: 1

    override fun seekToMediaItem(index: Int) {
        val playlist = currentPlaylist ?: return
        if (index in playlist.indices) {
            currentPlaylistIndex = index
            val (uri, title) = playlist[index]
            play(uri, title)
        }
    }

    override fun isPlaying(): Boolean = player.isPlaying()
    override fun getDuration(): Long = player.getDuration()
    override fun getCurrentPosition(): Long = player.getPosition()
    override fun getBufferedPosition(): Long = player.getPosition()

    override fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        player.setSpeed(speed)
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

    override fun setDecoderMode(mode: DecoderMode) {}
    override fun setFfmpegPreferred(preferred: Boolean) {}

    // ─── Subtitles ──────────────────────────────────────────────────────────

    override fun getSubtitleTracks(): List<String> = assHandler.getExternalTrackNames()
    override fun getSelectedSubtitleTrack(): Int = assHandler.getActiveExternalTrackIndex()

    override fun selectSubtitleTrack(index: Int) {
        val extIds = assHandler.getExternalTrackIds()
        if (index in extIds.indices) {
            assHandler.selectTrack(extIds[index])
            assHandler.onAssTrackSelected?.invoke()
        } else {
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
            val data = ExoMediaItemHelper.readSubtitleUriBytes(appContext, null, uri) ?: return@launch
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
    override fun getSelectedAudioTrack(): Int = 0
    override fun selectAudioTrack(index: Int) {}

    override fun clearError() {
        _playbackState.update { it.copy(errorMessage = null) }
    }

    override fun retry() {
        val uri = currentUri ?: return
        val title = currentTitle ?: ""
        play(uri, title, resumePositionMs = getCurrentPosition())
    }

    override fun release() {
        engineScope.cancel()
        player.release()
        (activeBridge as? Closeable)?.let { runCatching { it.close() } }
        activeBridge = null
    }

    // ─── Render View Seam ───────────────────────────────────────────────────

    override fun createRenderView(context: Context, useSurfaceView: Boolean): View {
        val frameLayout = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

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
                activeSurface = null
                player.setSurface(null)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
        })

        frameLayout.addView(surfaceView)
        activeSurfaceViewRef = WeakReference(surfaceView)
        activeContainerRef = WeakReference(frameLayout)
        applyAspectRatio()

        return frameLayout
    }

    override fun updateRenderView(view: View, config: RenderViewConfig) {
        currentAspectRatioMode = config.aspectRatioMode
        applyAspectRatio()
    }

    private fun applyAspectRatio() {
        val container = activeContainerRef?.get() ?: return
        val surfaceView = activeSurfaceViewRef?.get() ?: return
        val vw = videoWidth.toFloat()
        val vh = videoHeight.toFloat()
        if (vw <= 0 || vh <= 0) return

        container.post {
            val containerWidth = container.width
            val containerHeight = container.height
            if (containerWidth <= 0 || containerHeight <= 0) return@post

            val targetRatio = when (currentAspectRatioMode) {
                AspectRatioMode.AUTO -> vw / vh
                AspectRatioMode.RATIO_16_9 -> 16f / 9f
                AspectRatioMode.RATIO_4_3 -> 4f / 3f
                AspectRatioMode.RATIO_21_9 -> 21f / 9f
                AspectRatioMode.RATIO_18_9 -> 18f / 9f
                AspectRatioMode.STRETCH -> containerWidth.toFloat() / containerHeight.toFloat()
                AspectRatioMode.ZOOM -> vw / vh
            }

            val (finalW, finalH) = if (currentAspectRatioMode == AspectRatioMode.STRETCH) {
                containerWidth to containerHeight
            } else if (currentAspectRatioMode == AspectRatioMode.ZOOM) {
                val containerRatio = containerWidth.toFloat() / containerHeight.toFloat()
                if (targetRatio > containerRatio) {
                    val h = containerHeight
                    val w = (h * targetRatio).toInt()
                    w to h
                } else {
                    val w = containerWidth
                    val h = (w / targetRatio).toInt()
                    w to h
                }
            } else {
                val containerRatio = containerWidth.toFloat() / containerHeight.toFloat()
                if (targetRatio > containerRatio) {
                    val w = containerWidth
                    val h = (w / targetRatio).toInt()
                    w to h
                } else {
                    val h = containerHeight
                    val w = (h * targetRatio).toInt()
                    w to h
                }
            }

            val params = (surfaceView.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(finalW, finalH)
            params.width = finalW
            params.height = finalH
            params.gravity = android.view.Gravity.CENTER
            surfaceView.layoutParams = params
        }
    }

    override fun onRenderViewPaused(view: View) {}
    override fun onRenderViewResumed(view: View) {}

    override fun getMedia3Player(): Player? = null
    override fun setOnPlayerReplacedListener(listener: ((Player) -> Unit)?) {}
    override fun getDebugStats(): DebugStats? = null
}
