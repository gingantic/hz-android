package com.rhnxdev.hzplayer.data.datasource.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.AssHandler
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.SubtitleConverters
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.isLibassSubtitleFormat
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.DebugStats
import com.rhnxdev.hzplayer.domain.model.DecoderMode
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@SuppressLint("UnsafeOptInUsageError")
@OptIn(UnstableApi::class)
class ExoPlayerEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val playerHolder: MediaPlayerHolder,
    private val assHandler: AssHandler,
    private val neighborSubtitleDiscoverer: NeighborSubtitleDiscoverer,
) : IPlayerEngine {

    override val engineType: EngineType = EngineType.EXO_PLAYER

    val player: Player get() = playerHolder.player

    private val debugStatsHelper = ExoDebugStatsHelper()

    init {
        // External subtitle tracks live outside ExoPlayer; surface their changes
        // so the UI track list (which is otherwise ExoPlayer-driven) stays in sync.
        assHandler.onExternalTrackListChanged = {
            subtitleTrackChangeListener?.invoke()
        }
    }

    /** Fired when subtitle tracks change (embedded via ExoPlayer, external via libass). */
    override var subtitleTrackChangeListener: (() -> Unit)? = null

    /** Apply a decoder preference. Forwards to the holder, which rebuilds the
     *  underlying ExoPlayer so the choice takes effect on the next play. */
    override fun setDecoderMode(mode: DecoderMode) {
        playerHolder.decoderMode = mode
    }

    /** Compute instant rendered FPS from DecoderCounters. Call each polling interval. */
    fun pollRenderedFps(): Float = debugStatsHelper.pollRenderedFps(playerHolder)

    /** Get absolute dropped frame count. */
    fun pollDroppedFrames(): Long = debugStatsHelper.pollDroppedFrames(playerHolder)

    override val playbackState: StateFlow<PlayerStateInfo>
        get() = playerHolder.playbackStateInfo

    private var currentMediaUri: String? = null
    private var currentMediaTitle: String? = null
    private var currentPlaylist: List<MediaItem>? = null
    private val subtitleDiscoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun play(uri: String, title: String, artist: String?, isVideo: Boolean, mimeType: String?, resumePositionMs: Long, headers: Map<String, String>) {
        playerHolder.setHttpRequestHeaders(headers)
        playerHolder.prepareForUri(uri)
        currentPlaylist = null
        currentMediaUri = uri
        currentMediaTitle = title
        subtitleDiscoveryScope.launch {
            val subs = neighborSubtitleDiscoverer.discover(uri)
            withContext(Dispatchers.Main) {
                subtitleConfigs.clear()
                subtitleConfigs.addAll(subs)
                playerHolder.clearError()
                playerHolder.flushPendingDecoderRebuild()
                player.setMediaItem(ExoMediaItemHelper.buildMediaItemWithSubtitles(uri, title, artist, mimeType, subs = subs))
                player.prepare()
                if (resumePositionMs > 0) player.seekTo(resumePositionMs)
                player.play()
            }
        }
    }

    override fun playPlaylist(items: List<Pair<String, String>>, startIndex: Int, startPositionMs: Long) {
        if (items.isEmpty()) return
        val startUri = items[startIndex].first
        playerHolder.prepareForUri(startUri)
        currentPlaylist = null
        subtitleConfigs.clear()
        val startTitle = items[startIndex].second
        currentMediaUri = startUri
        currentMediaTitle = startTitle
        subtitleDiscoveryScope.launch {
            val subs = neighborSubtitleDiscoverer.discover(startUri)
            withContext(Dispatchers.Main) {
                subtitleConfigs.clear()
                subtitleConfigs.addAll(subs)
                val mediaItems = items.map { (uri, title) ->
                    val itemSubs = if (uri == startUri) subs else emptyList()
                    ExoMediaItemHelper.buildMediaItemWithSubtitles(uri, title, subs = itemSubs)
                }
                currentPlaylist = mediaItems
                playerHolder.clearError()
                playerHolder.flushPendingDecoderRebuild()
                player.setMediaItems(mediaItems, startIndex.coerceIn(0, mediaItems.lastIndex), startPositionMs)
                player.prepare()
                player.play()
            }
        }
    }

    override fun playAudioPlaylist(items: List<AudioItem>, startIndex: Int) {
        if (items.isEmpty()) return
        val startItem = items[startIndex]
        playerHolder.prepareForUri(startItem.uri)
        currentPlaylist = null
        subtitleConfigs.clear()
        currentMediaUri = startItem.uri
        currentMediaTitle = startItem.title
        subtitleDiscoveryScope.launch {
            val subs = neighborSubtitleDiscoverer.discover(startItem.uri)
            withContext(Dispatchers.Main) {
                subtitleConfigs.clear()
                subtitleConfigs.addAll(subs)
                val mediaItems = items.map { audio ->
                    val itemSubs = if (audio.uri == startItem.uri) subs else emptyList()
                    ExoMediaItemHelper.buildMediaItemWithSubtitles(audio.uri, audio.title, audio.artist, subs = itemSubs)
                }
                currentPlaylist = mediaItems
                playerHolder.clearError()
                playerHolder.flushPendingDecoderRebuild()
                player.setMediaItems(mediaItems, startIndex.coerceIn(0, mediaItems.lastIndex), 0L)
                player.prepare()
                player.play()
            }
        }
    }

    override fun pause() {
        player.pause()
    }

    override fun resume() {
        player.play()
    }

    override fun stop() {
        player.stop()
        // Fully reset the singleton player so nothing from this session (last
        // frame, position, tracks, speed) leaks into the next one. Without
        // clearMediaItems() the old media stays loaded after the screen closes
        // and only gets replaced when the next video starts.
        player.clearMediaItems()
        player.setPlaybackSpeed(1f)
        currentPlaylist = null
        currentMediaUri = null
        currentMediaTitle = null
        subtitleConfigs.clear()
        assHandler.reset()
        playerHolder.clearError()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun skipForward(ms: Long) {
        val newPos = (player.currentPosition + ms)
            .coerceAtMost(player.duration.coerceAtLeast(0))
        player.seekTo(newPos)
    }

    override fun skipBackward(ms: Long) {
        val newPos = (player.currentPosition - ms).coerceAtLeast(0)
        player.seekTo(newPos)
    }

    override fun skipToNext() {
        if (player.mediaItemCount > 1) player.seekToNextMediaItem()
        else player.seekTo((player.currentPosition + 10_000).coerceAtMost(player.duration.coerceAtLeast(0)))
    }

    override fun skipToPrevious() {
        if (player.mediaItemCount > 1) player.seekToPreviousMediaItem()
        else player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
    }

    override fun getCurrentMediaItemIndex(): Int = player.currentMediaItemIndex

    override fun getMediaItemCount(): Int = player.mediaItemCount

    override fun seekToMediaItem(index: Int) {
        if (index in 0 until player.mediaItemCount) {
            player.seekTo(index, 0)
        }
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        player.shuffleModeEnabled = enabled
    }

    override fun setRepeatMode(mode: RepeatMode) {
        player.repeatMode = when (mode) {
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun isShuffleEnabled(): Boolean = player.shuffleModeEnabled

    override fun getRepeatMode(): RepeatMode =
        when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.NONE
        }

    override fun isPlaying(): Boolean = player.isPlaying

    override fun getDuration(): Long = player.duration.coerceAtLeast(0)

    override fun getCurrentPosition(): Long = player.currentPosition.coerceAtLeast(0)

    override fun getBufferedPosition(): Long = player.bufferedPosition.coerceAtLeast(0)

    override fun setPlaybackSpeed(speed: Float) {
        playerHolder.updateSpeed(speed)
    }

    // ── Subtitle delay ─────────────────────────────────────────────────────

    private var subtitleDelayMs: Long = 0

    override fun setSubtitleDelay(delayMs: Long) {
        subtitleDelayMs = delayMs
        assHandler.subtitleDelayMs = delayMs
    }

    override fun getSubtitleDelay(): Long = subtitleDelayMs

    override fun setAudioDelay(delayMs: Long) {
        playerHolder.audioDelayMs = delayMs
    }

    override fun getAudioDelay(): Long = playerHolder.audioDelayMs

    // ── External subtitles ─────────────────────────────────────────────────

    private val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()

    override fun addExternalSubtitle(uri: Uri): Boolean {
        val ext = (uri.path ?: "").substringAfterLast('.').lowercase()
        val mimeType = ExoMediaItemHelper.inferSubtitleMimeType(uri)
        val displayName = uri.lastPathSegment ?: uri.toString()

        if (ext == "ass" || ext == "ssa" || SubtitleConverters.isConvertibleSubtitleFormat(mimeType)) {
            subtitleDiscoveryScope.launch {
                val data = ExoMediaItemHelper.readSubtitleUriBytes(appContext, playerHolder, uri)
                withContext(Dispatchers.Main) {
                    if (data == null) {
                        Log.w(TAG, "External subtitle read failed: $uri")
                        return@withContext
                    }
                    val assBytes = if (ext == "ass" || ext == "ssa") {
                        data
                    } else {
                        SubtitleConverters.convertToAss(
                            data, mimeType,
                            assHandler.getVideoWidth(), assHandler.getVideoHeight()
                        )
                    }
                    if (assBytes == null) {
                        Log.w(TAG, "External subtitle convert failed: $uri")
                        return@withContext
                    }
                    assHandler.loadExternalTrack(assBytes, displayName)
                }
            }
            return true
        }

        return try {
            val config = MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(mimeType)
                .setLanguage("und")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            subtitleConfigs.add(config)

            val currentUri = currentMediaUri
            val currentTitle = currentMediaTitle
            val playlist = currentPlaylist
            if (currentUri != null) {
                val position = player.currentPosition
                val wasPlaying = player.isPlaying
                val currentIndex = player.currentMediaItemIndex
                val rebuilt = rebuildPlaylistForSubtitleSwap(
                    playlist, currentUri, currentTitle ?: "",
                    currentIndex, position, subtitleConfigs.toList()
                ) { uriStr, titleStr, s -> ExoMediaItemHelper.buildMediaItemWithSubtitles(uriStr, titleStr, subs = s) }
                player.setMediaItems(rebuilt.items, rebuilt.startIndex, rebuilt.startPositionMs)
                player.prepare()
                if (wasPlaying) player.play()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ── Subtitle track selection ───────────────────────────────

    private data class ExoTrackInfo(
        val group: Tracks.Group,
        val trackIndex: Int,
        val displayName: String
    )

    private fun getExoTextTracks(): List<ExoTrackInfo> {
        val list = mutableListOf<ExoTrackInfo>()
        val currentTracks = player.currentTracks
        var trackIdxCounter = 1
        for (group in currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    if (group.isTrackSupported(i)) {
                        val format = group.getTrackFormat(i)
                        val lang = format.language
                        val label = format.label
                        val name = when {
                            !label.isNullOrEmpty() -> label
                            !lang.isNullOrEmpty() -> "Subtitle - $lang"
                            else -> "Subtitle Track $trackIdxCounter"
                        }
                        list.add(ExoTrackInfo(group, i, name))
                        trackIdxCounter++
                    }
                }
            }
        }
        return list
    }

    override fun getSubtitleTracks(): List<String> {
        return getExoTextTracks().map { it.displayName } + assHandler.getExternalTrackNames()
    }

    override fun getSubtitleTrackMimeTypes(): List<String?> {
        val embedded = getExoTextTracks().map {
            val format = it.group.getTrackFormat(it.trackIndex)
            if (format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES) {
                format.codecs ?: format.sampleMimeType
            } else {
                format.sampleMimeType
            }
        }
        val external = assHandler.getExternalTrackIds().map { MimeTypes.TEXT_SSA }
        return embedded + external
    }

    override fun getSelectedSubtitleTrack(): Int {
        val tracks = getExoTextTracks()
        val extIdx = assHandler.getActiveExternalTrackIndex()
        if (extIdx >= 0) return tracks.size + extIdx
        if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
            return -1
        }
        for (index in tracks.indices) {
            val trackInfo = tracks[index]
            if (trackInfo.group.isTrackSelected(trackInfo.trackIndex)) {
                return index
            }
        }
        return -1
    }

    override fun selectSubtitleTrack(index: Int) {
        val tracks = getExoTextTracks()
        val embeddedCount = tracks.size
        if (index >= embeddedCount) {
            val extIdx = index - embeddedCount
            val extIds = assHandler.getExternalTrackIds()
            if (extIdx in extIds.indices) {
                assHandler.selectTrack(extIds[extIdx])
                assHandler.onAssTrackSelected?.invoke()
            }
            return
        }
        if (index in tracks.indices) {
            val selectedTrack = tracks[index]
            val format = selectedTrack.group.getTrackFormat(selectedTrack.trackIndex)
            val isAss = isLibassSubtitleFormat(format)
            if (isAss) {
                assHandler.selectTrackByFormat(format)
            } else {
                assHandler.clearOverlay()
                setExoSubtitleViewVisible(true)
            }
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .addOverride(
                    TrackSelectionOverride(
                        selectedTrack.group.mediaTrackGroup,
                        selectedTrack.trackIndex
                    )
                )
                .build()
        } else {
            assHandler.clearOverlay()
            setExoSubtitleViewVisible(true)
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
        }
    }

    override fun loadExternalAss(uri: Uri) {
        val data = ExoMediaItemHelper.readSubtitleUriBytes(appContext, playerHolder, uri) ?: return
        assHandler.loadExternalTrack(data)
    }

    // ── Audio track selection ──────────────────────────────────

    private fun getExoAudioTracks(): List<ExoTrackInfo> {
        val list = mutableListOf<ExoTrackInfo>()
        val currentTracks = player.currentTracks
        var trackIdxCounter = 1
        for (group in currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    if (group.isTrackSupported(i)) {
                        val format = group.getTrackFormat(i)
                        val lang = format.language
                        val label = format.label
                        val channelCount = format.channelCount
                        val name = when {
                            !label.isNullOrEmpty() -> label
                            !lang.isNullOrEmpty() && channelCount > 0 -> "$lang ($channelCount ch)"
                            !lang.isNullOrEmpty() -> "Audio - $lang"
                            else -> "Audio Track $trackIdxCounter"
                        }
                        list.add(ExoTrackInfo(group, i, name))
                        trackIdxCounter++
                    }
                }
            }
        }
        return list
    }

    override fun getAudioTracks(): List<String> {
        return getExoAudioTracks().map { it.displayName }
    }

    override fun getSelectedAudioTrack(): Int {
        val tracks = getExoAudioTracks()
        for (index in tracks.indices) {
            val trackInfo = tracks[index]
            if (trackInfo.group.isTrackSelected(trackInfo.trackIndex)) {
                return index
            }
        }
        return -1
    }

    override fun selectAudioTrack(index: Int) {
        val tracks = getExoAudioTracks()
        if (index in tracks.indices) {
            val selectedTrack = tracks[index]
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .addOverride(
                    TrackSelectionOverride(
                        selectedTrack.group.mediaTrackGroup,
                        selectedTrack.trackIndex
                    )
                )
                .build()
        } else {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build()
        }
    }

    override fun clearError() {
        playerHolder.clearError()
    }

    override fun retry() {
        playerHolder.clearError()
        val playlist = currentPlaylist
        val index = player.currentMediaItemIndex
        if (playlist != null && playlist.isNotEmpty()) {
            player.setMediaItems(playlist, index, player.currentPosition)
        } else {
            val item = player.currentMediaItem ?: return
            player.setMediaItem(item)
        }
        player.prepare()
        player.play()
    }

    override fun release() {
        subtitleDiscoveryScope.cancel()
        playerHolder.release()
    }

    // ── Rendering seam (engine-private; surfaced via PlayerSurface) ──

    private var activePlayerViewRef: WeakReference<PlayerView>? = null
    private var currentAspectRatioMode: com.rhnxdev.hzplayer.domain.model.AspectRatioMode = com.rhnxdev.hzplayer.domain.model.AspectRatioMode.AUTO
    private var listenerRegisteredPlayer: Player? = null
    private var assSubtitlesActive = false

    private val videoSizeListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            activePlayerViewRef?.get()?.let { playerView ->
                playerView.post {
                    applyAspectRatioMode(playerView, currentAspectRatioMode)
                }
            }
        }
    }

    private fun applyAspectRatioMode(playerView: PlayerView, mode: com.rhnxdev.hzplayer.domain.model.AspectRatioMode) {
        currentAspectRatioMode = mode
        val currentPlayer = player
        if (listenerRegisteredPlayer !== currentPlayer) {
            listenerRegisteredPlayer?.removeListener(videoSizeListener)
            currentPlayer.addListener(videoSizeListener)
            listenerRegisteredPlayer = currentPlayer
        }

        val contentFrame = playerView.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        when (mode) {
            com.rhnxdev.hzplayer.domain.model.AspectRatioMode.AUTO -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                val videoSize = currentPlayer.videoSize
                val videoAspectRatio = if (videoSize.height == 0 || videoSize.width == 0) {
                    0f
                } else {
                    (videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
                }
                contentFrame?.setAspectRatio(videoAspectRatio)
            }
            com.rhnxdev.hzplayer.domain.model.AspectRatioMode.RATIO_16_9 -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                contentFrame?.setAspectRatio(16f / 9f)
            }
            com.rhnxdev.hzplayer.domain.model.AspectRatioMode.RATIO_4_3 -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                contentFrame?.setAspectRatio(4f / 3f)
            }
            com.rhnxdev.hzplayer.domain.model.AspectRatioMode.RATIO_21_9 -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                contentFrame?.setAspectRatio(21f / 9f)
            }
            com.rhnxdev.hzplayer.domain.model.AspectRatioMode.RATIO_18_9 -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                contentFrame?.setAspectRatio(18f / 9f)
            }
            com.rhnxdev.hzplayer.domain.model.AspectRatioMode.ZOOM -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                val videoSize = currentPlayer.videoSize
                val videoAspectRatio = if (videoSize.height == 0 || videoSize.width == 0) {
                    0f
                } else {
                    (videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
                }
                contentFrame?.setAspectRatio(videoAspectRatio)
            }
            com.rhnxdev.hzplayer.domain.model.AspectRatioMode.STRETCH -> {
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                contentFrame?.setAspectRatio(0f)
            }
        }
    }

    override fun createRenderView(context: Context, useSurfaceView: Boolean): View {
        val layoutRes = if (useSurfaceView) {
            com.rhnxdev.hzplayer.R.layout.view_exo_player_surface
        } else {
            com.rhnxdev.hzplayer.R.layout.view_exo_player
        }
        val playerView = LayoutInflater.from(context)
            .inflate(layoutRes, null, false) as PlayerView
        playerView.player = player
        playerView.useController = false
        activePlayerViewRef = WeakReference(playerView)
        val subtitleView = playerView.subtitleView
        if (subtitleView != null) {
            subtitleView.setStyle(
                CaptionStyleCompat(
                    0xFFFFFFFF.toInt(),
                    0,
                    0x00000000,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    0xFF000000.toInt(),
                    null,
                )
            )
            if (assSubtitlesActive) {
                subtitleView.visibility = View.GONE
            }
        }
        return playerView
    }

    fun setExoSubtitleViewVisible(visible: Boolean) {
        assSubtitlesActive = !visible
        val playerView = activePlayerViewRef?.get() ?: return
        val sv = playerView.subtitleView ?: return
        sv.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun updateRenderView(view: View, config: RenderViewConfig) {
        val playerView = view as PlayerView
        activePlayerViewRef = WeakReference(playerView)
        applyAspectRatioMode(playerView, config.aspectRatioMode)
    }

    override fun onRenderViewPaused(view: View) = (view as PlayerView).onPause()
    override fun onRenderViewResumed(view: View) = (view as PlayerView).onResume()

    override fun getMedia3Player(): Player = playerHolder.player

    override fun setOnPlayerReplacedListener(listener: ((Player) -> Unit)?) {
        playerHolder.setOnPlayerReplacedListener(listener)
    }

    override fun getDebugStats(): DebugStats? {
        return debugStatsHelper.getDebugStats(player, playerHolder)
    }

    companion object {
        private const val TAG = "ExoPlayerEngine"
    }
}
