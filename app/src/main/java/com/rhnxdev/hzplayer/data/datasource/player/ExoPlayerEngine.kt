package com.rhnxdev.hzplayer.data.datasource.player

import android.content.Context
import android.net.Uri
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.isAssFormat
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.PlayerStateInfo
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import com.rhnxdev.hzplayer.domain.player.RenderViewConfig
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.AssHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
// Media3's playback/UI APIs are marked @UnstableApi. The Kotlin compiler requires
// @androidx.annotation.OptIn to use them; lint's UnsafeOptInUsageError only honors a
// different (compiler-rejected) annotation, so the lint warning is suppressed here.
// This is a known AndroidX/Kotlin opt-in friction, not a real issue.
@SuppressLint("UnsafeOptInUsageError")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ExoPlayerEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val playerHolder: MediaPlayerHolder,
    private val assHandler: AssHandler,
) : IPlayerEngine {

    override val engineType: EngineType = EngineType.EXO_PLAYER

    val player: Player get() = playerHolder.player

    /** Apply a decoder preference. Forwards to the holder, which rebuilds the
     *  underlying ExoPlayer so the choice takes effect on the next play. */
    @OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun setDecoderMode(mode: com.rhnxdev.hzplayer.domain.model.DecoderMode) {
        playerHolder.decoderMode = mode
    }

    private var lastRenderedFrames: Long = 0
    private var lastFrameTimestamp: Long = 0L

    /** Compute instant rendered FPS from DecoderCounters. Call each polling interval. */
    fun pollRenderedFps(): Float {
        val (rendered, _) = playerHolder.readFrameCounters()
        val now = System.nanoTime()
        val fps = if (lastFrameTimestamp > 0 && lastRenderedFrames > 0) {
            val dt = (now - lastFrameTimestamp) / 1_000_000_000f
            val df = rendered - lastRenderedFrames
            if (dt > 0f && df >= 0) df / dt else 0f
        } else 0f
        lastRenderedFrames = rendered
        lastFrameTimestamp = now
        return fps
    }

    /** Get absolute dropped frame count. */
    fun pollDroppedFrames(): Long {
        val (_, dropped) = playerHolder.readFrameCounters()
        return dropped
    }

    override val playbackState: StateFlow<PlayerStateInfo>
        get() = playerHolder.playbackStateInfo

    private var currentMediaUri: String? = null
    private var currentMediaTitle: String? = null
    private var currentPlaylist: List<androidx.media3.common.MediaItem>? = null
    private val subtitleDiscoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun play(uri: String, title: String, artist: String?, isVideo: Boolean, mimeType: String?, resumePositionMs: Long) {
        currentPlaylist = null
        // Set the current-uri fields up front: addExternalSubtitle reads them on
        // the main thread and would otherwise see null during the (async) discovery
        // window, silently no-op'ing the add.
        currentMediaUri = uri
        currentMediaTitle = title
        // Subtitle discovery (disk/network scan) runs off the main thread; the
        // player calls below must run on the main thread (Media3 requirement), so
        // we hop back to Dispatchers.Main after discovery completes.
        subtitleDiscoveryScope.launch {
            val subs = discoverNeighborSubtitles(uri)
            withContext(Dispatchers.Main) {
                subtitleConfigs.clear()
                subtitleConfigs.addAll(subs)
                playerHolder.clearError()
                playerHolder.flushPendingDecoderRebuild()
                player.setMediaItem(buildMediaItemWithSubtitles(uri, title, artist, mimeType, subs = subs))
                player.prepare()
                if (resumePositionMs > 0) player.seekTo(resumePositionMs)
                player.play()
            }
        }
    }

    override fun playPlaylist(items: List<Pair<String, String>>, startIndex: Int, startPositionMs: Long) {
        if (items.isEmpty()) return
        currentPlaylist = null
        subtitleConfigs.clear()
        val startUri = items[startIndex].first
        val startTitle = items[startIndex].second
        // Set before the async discovery window so addExternalSubtitle (main-thread)
        // never sees a null current uri.
        currentMediaUri = startUri
        currentMediaTitle = startTitle
        // Neighbor-subtitle discovery only for the start item; others get plain items.
        subtitleDiscoveryScope.launch {
            val subs = discoverNeighborSubtitles(startUri)
            withContext(Dispatchers.Main) {
                subtitleConfigs.clear()
                subtitleConfigs.addAll(subs)
                val mediaItems = items.map { (uri, title) ->
                    val itemSubs = if (uri == startUri) subs else emptyList()
                    buildMediaItemWithSubtitles(uri, title, subs = itemSubs)
                }
                // ponytail: populate currentPlaylist so retry()/addExternalSubtitle()
                // see the real list instead of null (which collapsed to 1 item).
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
        currentPlaylist = null
        subtitleConfigs.clear()
        val startItem = items[startIndex]
        // Set before the async discovery window so addExternalSubtitle (main-thread)
        // never sees a null current uri.
        currentMediaUri = startItem.uri
        currentMediaTitle = startItem.title
        subtitleDiscoveryScope.launch {
            val subs = discoverNeighborSubtitles(startItem.uri)
            withContext(Dispatchers.Main) {
                subtitleConfigs.clear()
                subtitleConfigs.addAll(subs)
                val mediaItems = items.map { audio ->
                    val itemSubs = if (audio.uri == startItem.uri) subs else emptyList()
                    buildMediaItemWithSubtitles(audio.uri, audio.title, audio.artist, subs = itemSubs)
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
        currentPlaylist = null
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

    override fun setShuffleEnabled(enabled: Boolean) {
        player.shuffleModeEnabled = enabled
    }

    override fun setRepeatMode(mode: com.rhnxdev.hzplayer.domain.model.RepeatMode) {
        player.repeatMode = when (mode) {
            com.rhnxdev.hzplayer.domain.model.RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            com.rhnxdev.hzplayer.domain.model.RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun isShuffleEnabled(): Boolean = player.shuffleModeEnabled

    override fun getRepeatMode(): com.rhnxdev.hzplayer.domain.model.RepeatMode =
        when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> com.rhnxdev.hzplayer.domain.model.RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> com.rhnxdev.hzplayer.domain.model.RepeatMode.ALL
            else -> com.rhnxdev.hzplayer.domain.model.RepeatMode.NONE
        }

    override fun isPlaying(): Boolean = player.isPlaying

    override fun getDuration(): Long = player.duration.coerceAtLeast(0)

    override fun getCurrentPosition(): Long = player.currentPosition.coerceAtLeast(0)

    override fun getBufferedPosition(): Long = player.bufferedPosition.coerceAtLeast(0)

    override fun setPlaybackSpeed(speed: Float) {
        playerHolder.updateSpeed(speed)
    }

    // â”€â”€ Subtitle delay â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private var subtitleDelayMs: Long = 0

    override fun setSubtitleDelay(delayMs: Long) {
        subtitleDelayMs = delayMs
    }

    override fun getSubtitleDelay(): Long = subtitleDelayMs

    // â”€â”€ External subtitles â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()

    private fun buildMediaItemWithSubtitles(
        uri: String,
        title: String,
        artist: String? = null,
        mimeType: String? = null,
        subs: List<MediaItem.SubtitleConfiguration> = emptyList(),
    ): MediaItem {
        val builder = MediaItem.Builder()
        builder.setUri(uri)
        // When we know the type (e.g. a server Content-Type probe), set it explicitly
        // so ExoPlayer doesn't have to rely solely on container sniffing.
        if (!mimeType.isNullOrBlank()) {
            builder.setMimeType(mimeType)
        }

        builder.setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .build(),
            )
        // Subtitles are scoped per-media-item: the global `subtitleConfigs` must not
        // be applied to every playlist entry (it was), or all items would inherit the
        // first item's discovered subs.
        if (subs.isNotEmpty()) {
            builder.setSubtitleConfigurations(subs)
        }
        return builder.build()
    }

    companion object {
        private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub")
        private val SUBTITLE_SCHEMES_WITH_DIR = setOf("file", "smb", "ftp", "sftp", "webdav", "webdavs")
    }

    override fun addExternalSubtitle(uri: Uri): Boolean {
        return try {
            val mimeType = inferSubtitleMimeType(uri)
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
                // ponytail: never call setMediaItem here — it wipes the playlist and
                // loses resume position. Rebuild the whole list with the updated
                // subtitle config on the current item, then restore index+position.
                val rebuilt = rebuildPlaylistForSubtitleSwap(
                    playlist, currentUri, currentTitle ?: "",
                    currentIndex, position, subtitleConfigs.toList()
                ) { uri, title, s -> buildMediaItemWithSubtitles(uri, title, subs = s) }
                player.setMediaItems(rebuilt.items, rebuilt.startIndex, rebuilt.startPositionMs)
                player.prepare()
                if (wasPlaying) player.play()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // â”€â”€ Subtitle track selection â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
        return getExoTextTracks().map { it.displayName }
    }

    override fun getSubtitleTrackMimeTypes(): List<String?> {
        // Since Media3 1.4, subtitles are parsed during extraction: the track's
        // sampleMimeType becomes "application/x-media3-cues" and the original
        // codec (e.g. text/x-ssa) is moved to Format.codecs. Prefer the original.
        return getExoTextTracks().map {
            val format = it.group.getTrackFormat(it.trackIndex)
            if (format.sampleMimeType == androidx.media3.common.MimeTypes.APPLICATION_MEDIA3_CUES) {
                format.codecs ?: format.sampleMimeType
            } else {
                format.sampleMimeType
            }
        }
    }

    override fun getSelectedSubtitleTrack(): Int {
        if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
            return -1
        }
        val tracks = getExoTextTracks()
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
        if (index in tracks.indices) {
            val selectedTrack = tracks[index]
            val format = selectedTrack.group.getTrackFormat(selectedTrack.trackIndex)
            val isAss = isAssFormat(format)
            if (isAss) {
                // Route embedded ASS/SSA through libass for full styling; the built-in
                // text renderer is fed a no-op parser so it won't draw duplicates.
                assHandler.selectTrackByFormat(format)
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
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
        }
    }

    /** Load an external `.ass`/`.ssa` file into libass (bypasses ExoPlayer parsing).
     *  Remote `smb`/`ftp`/`sftp`/`webdav` URIs are read via the player's routing
     *  data source; only `content:`/`file:` go through [contentResolver]. */
    override fun loadExternalAss(uri: Uri) {
        val data = runCatching {
            when (uri.scheme?.lowercase()) {
                "content", "file" -> appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                else -> playerHolder.readUriBytes(uri)
            }
        }.getOrNull() ?: return
        assHandler.loadExternalTrack(data)
    }

    // â”€â”€ Audio track selection â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
            // Mirror selectSubtitleTrack: index == -1 disables the audio track.
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
        // ponytail: keep playlist intact — setMediaItem wipes it and loses position.
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
        playerHolder.release()
    }

    // â”€â”€ Rendering seam (engine-private; surfaced via PlayerSurface) â”€â”€

    /**
     * Build the Media3 [PlayerView] that renders this engine's video. The surface
     * type (SurfaceView vs TextureView) is chosen from [useSurfaceView] and fixed at
     * across brief app switches) is chosen from [useSurfaceView] and fixed at
     * construction via the XML layout â€” there is no programmatic setter.
     */
    private var activePlayerViewRef: java.lang.ref.WeakReference<PlayerView>? = null
    private var currentAspectRatioMode: com.rhnxdev.hzplayer.domain.model.AspectRatioMode = com.rhnxdev.hzplayer.domain.model.AspectRatioMode.AUTO
    private var listenerRegisteredPlayer: Player? = null
    /** True once ASS renderer is active — keeps SubtitleView hidden across PlayerView recreations. */
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
        
        // Ensure our video size listener is attached to the current active player instance
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
        activePlayerViewRef = java.lang.ref.WeakReference(playerView)
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
            // Hide ExoPlayer's built-in subtitle view if ASS rendering is already active
            // (e.g. engine re-created while handler is initialized).
            if (assSubtitlesActive) {
                subtitleView.visibility = android.view.View.GONE
            }
        }
        return playerView
    }

    /** Hide / show ExoPlayer's built-in SubtitleView. Call when ASS renderer takes over. */
    fun setExoSubtitleViewVisible(visible: Boolean) {
        assSubtitlesActive = !visible
        val playerView = activePlayerViewRef?.get() ?: return
        val sv = playerView.subtitleView ?: return
        sv.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** Apply aspect-ratio + subtitle style to an existing [PlayerView]. */
    override fun updateRenderView(view: View, config: RenderViewConfig) {
        val playerView = view as PlayerView
        activePlayerViewRef = java.lang.ref.WeakReference(playerView)
        applyAspectRatioMode(playerView, config.aspectRatioMode)
    }

    /** Surface lifecycle â€” mirrors PlayerView.onPause/onResume. */
    override fun onRenderViewPaused(view: View) = (view as PlayerView).onPause()
    override fun onRenderViewResumed(view: View) = (view as PlayerView).onResume()

    /** Backs the system MediaSession for lock-screen / media controls. */
    override fun getMedia3Player(): Player = playerHolder.player

    override fun setOnPlayerReplacedListener(listener: ((Player) -> Unit)?) {
        playerHolder.setOnPlayerReplacedListener(listener)
    }

    // â”€â”€ Subtitle discovery â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private suspend fun discoverNeighborSubtitles(uri: String): List<MediaItem.SubtitleConfiguration> {
        playerHolder.setDiscovering()
        // Runs inside subtitleDiscoveryScope (Dispatchers.IO) — no runBlocking, so
        // the playback-start call never blocks the caller (usually main) thread.
        val subUris = try {
            findNeighborSubtitleFiles(uri)
        } catch (_: Exception) {
            emptyList<Uri>()
        }
        return subUris.map { subUri ->
            MediaItem.SubtitleConfiguration.Builder(subUri)
                .setMimeType(inferSubtitleMimeType(subUri))
                .setLanguage("und")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        }
    }

    // â”€â”€ Debug stats â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override fun getDebugStats(): com.rhnxdev.hzplayer.domain.model.DebugStats? {
        val currentTracks = player.currentTracks
        var videoCodec = ""
        var videoCodecMime = ""
        var resolution = ""
        var videoBitrate = ""
        var frameRate = ""
        var colorInfo = ""
        var hdrInfo = ""

        var audioCodec = ""
        var audioCodecMime = ""
        var audioBitrate = ""
        var sampleRate = ""
        var channelCount = ""
        var audioLanguage = ""

        fmt@ for (group in currentTracks.groups) {
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val fmt = group.getTrackFormat(i)
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> {
                        videoCodec = fmt.codecs ?: ""
                        videoCodecMime = fmt.sampleMimeType ?: ""
                        resolution = if (fmt.width > 0 && fmt.height > 0)
                            "${fmt.width}x${fmt.height}" else ""
                        videoBitrate = if (fmt.bitrate > 0) fmt.bitrate.toString() else ""
                        frameRate = if (fmt.frameRate > 0f) "${"%.2f".format(fmt.frameRate)} fps" else ""
                        val ci = fmt.colorInfo
                        if (ci != null) {
                            colorInfo = buildString {
                                append(when (ci.colorSpace) {
                                    3 -> "BT.2020"
                                    7 -> "BT.709"
                                    else -> "BT.601"
                                })
                                append(" ")
                                append(when (ci.colorTransfer) {
                                    6 -> "PQ"
                                    7 -> "HLG"
                                    else -> "SDR"
                                })
                                append(" ")
                                append(when (ci.colorRange) {
                                    3 -> "FULL"
                                    else -> "LIMITED"
                                })
                            }
                            if (ColorInfo.isTransferHdr(ci)) {
                                hdrInfo = "HDR (${when (ci.colorTransfer) { 6 -> "PQ/ST.2084"; 7 -> "HLG"; else -> "yes" }})"
                            }
                        }
                    }
                    C.TRACK_TYPE_AUDIO -> {
                        audioCodec = fmt.codecs ?: ""
                        audioCodecMime = fmt.sampleMimeType ?: ""
                        audioBitrate = if (fmt.bitrate > 0) fmt.bitrate.toString() else ""
                        sampleRate = if (fmt.sampleRate > 0) "${fmt.sampleRate} Hz" else ""
                        channelCount = if (fmt.channelCount > 0) {
                            when (fmt.channelCount) {
                                1 -> "Mono"
                                2 -> "Stereo"
                                6 -> "5.1"
                                7 -> "6.1"
                                8 -> "7.1"
                                else -> "${fmt.channelCount} ch"
                            }
                        } else ""
                        audioLanguage = fmt.language ?: ""
                    }
                }
                break@fmt  // first selected track each type
            }
        }

        val videoDec = playerHolder.videoDecoderName.value
        val audioDec = playerHolder.audioDecoderName.value
        val videoDecLabel = if (videoDec.isNotEmpty()) "{${labelHwSw(videoDec)}} $videoDec" else ""
        val audioDecLabel = if (audioDec.isNotEmpty()) "{${labelHwSw(audioDec)}} $audioDec" else ""
        val decoderLabel = buildString {
            append(videoDecLabel)
            if (videoDecLabel.isNotEmpty() && audioDecLabel.isNotEmpty()) append(" | ")
            append(audioDecLabel)
        }.ifEmpty { "" }

        val fps = pollRenderedFps()
        return com.rhnxdev.hzplayer.domain.model.DebugStats(
            videoCodec = videoCodec,
            videoCodecMime = videoCodecMime,
            resolution = resolution,
            videoBitrate = videoBitrate,
            frameRate = frameRate,
            renderedFps = if (fps > 0f) "${"%.0f".format(fps)} fps" else "",
            droppedFrames = pollDroppedFrames().let { if (it > 0) it.toString() else "" },
            colorInfo = colorInfo,
            hdrInfo = hdrInfo,
            decoderName = decoderLabel,
            videoDecoderLabel = videoDecLabel,
            audioDecoderLabel = audioDecLabel,
            audioCodec = audioCodec,
            audioCodecMime = audioCodecMime,
            audioBitrate = audioBitrate,
            sampleRate = sampleRate,
            channelCount = channelCount,
            audioLanguage = audioLanguage,
            deviceModel = android.os.Build.MODEL,
            androidVersion = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            soCInfo = if (android.os.Build.VERSION.SDK_INT >= 31) {
                "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}"
            } else {
                "${android.os.Build.HARDWARE}"
            }.ifBlank { "${android.os.Build.HARDWARE}" },
        )
    }

    /** Tag decoder as HW or SW by its registration name.
     *  OMX.google.* / c2.android.* â†’ SW (Android software).
     *  OMX.* (not google) / c2.{qti,mediatek,exynos,ti}.* â†’ HW.
     *  Anything else â†’ SW (conservative). */
    private fun labelHwSw(decoderName: String): String = when {
        decoderName.startsWith("c2.android.") -> "SW"
        decoderName.startsWith("c2.") -> "HW"
        decoderName.startsWith("OMX.") && !decoderName.contains(".google.", ignoreCase = true) -> "HW"
        else -> "SW"
    }

    // â”€â”€ External subtitle helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun findNeighborSubtitleFiles(videoUri: String): List<Uri> {
        val androidUri = Uri.parse(videoUri)
        val scheme = androidUri.scheme?.lowercase() ?: "file"
        return when (scheme) {
            "file" -> findLocalNeighborSubtitles(androidUri)
            "smb" -> findSmbNeighborSubtitles(androidUri)
            "ftp", "sftp", "webdav", "webdavs" -> findRemoteExtensionSwapSubtitles(androidUri)
            else -> emptyList()
        }
    }

    private fun findLocalNeighborSubtitles(androidUri: Uri): List<Uri> {
        val videoPath = androidUri.path ?: return emptyList()
        val videoFile = File(videoPath)
        val parentDir = videoFile.parentFile ?: return emptyList()
        val baseName = videoFile.nameWithoutExtension
        return parentDir.listFiles()
            ?.filter { file ->
                val ext = file.extension.lowercase()
                file.nameWithoutExtension.equals(baseName, ignoreCase = true) &&
                    SUBTITLE_EXTENSIONS.contains(ext)
            }
            ?.map { Uri.fromFile(it) }
            ?: emptyList()
    }

    private fun findSmbNeighborSubtitles(androidUri: Uri): List<Uri> {
        return try {
            val userInfo = androidUri.userInfo ?: ""
            val parts = userInfo.split(":", limit = 2)
            val user = Uri.decode(parts.getOrNull(0) ?: "")
            val pass = Uri.decode(parts.getOrNull(1) ?: "")
            val host = androidUri.host ?: return emptyList()
            val port = androidUri.port.takeIf { it > 0 } ?: 445
            
            // Decoded name for text comparison with file.name (jcifs-ng returns decoded names)
            val path = androidUri.path ?: return emptyList()
            val videoName = path.substringAfterLast('/')
            val baseName = videoName.substringBeforeLast('.')

            // Resolve the parent directory by walking via listFiles() rather than
            // constructing an SmbFile from a URL, so a folder named with spaces,
            // emoji, or fullwidth CJK punctuation still resolves. See [SmbPathResolver].
            val encodedPath = androidUri.encodedPath ?: return emptyList()
            val encodedParentPath = encodedPath.substringBeforeLast('/').ifEmpty { "/" }
            val segments = SmbPathResolver.decodedSegmentsOf(encodedPath)

            val ctx = ConnectionPool.borrowSmbContext(host, port, user, pass)
            val dir = SmbPathResolver.resolveParent(ctx, host, port, segments)
                ?: return emptyList()

            val siblings = dir.listFiles()?.toList() ?: return emptyList()

            siblings
                .filter { file ->
                    val name = file.name.trimEnd('/')
                    val ext = name.substringAfterLast('.', "").lowercase()
                    name.substringBeforeLast('.').equals(baseName, ignoreCase = true) &&
                        SUBTITLE_EXTENSIONS.contains(ext)
                }
                .map { file ->
                    // Rebuild the sibling URI from the *original* androidUri, swapping
                    // only the last path segment. This preserves the existing encoding
                    // (avoids double-encoding %20 â†’ %2520) and the userInfo/host/port,
                    // instead of string-splicing "smb://$credPrefix$host:$port$path".
                    val encodedName = Uri.encode(file.name.trimEnd('/'))
                    androidUri.buildUpon()
                        .encodedPath("$encodedParentPath/$encodedName")
                        .build()
                }
        } catch (e: Exception) {
            android.util.Log.w("ExoPlayerEngine", "SMB subtitle discovery failed for $androidUri", e)
            emptyList()
        }
    }

    private fun findRemoteExtensionSwapSubtitles(androidUri: Uri): List<Uri> {
        val path = androidUri.path ?: return emptyList()
        val basePath = path.substringBeforeLast('.')
        return SUBTITLE_EXTENSIONS.map { ext ->
            androidUri.buildUpon().path("$basePath.$ext").build()
        }
    }

    private fun inferSubtitleMimeType(uri: Uri): String {
        val ext = uri.path?.substringAfterLast('.')?.lowercase() ?: return MimeTypes.APPLICATION_SUBRIP
        return when (ext) {
            "vtt" -> MimeTypes.TEXT_VTT
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "sub" -> MimeTypes.APPLICATION_SUBRIP
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
}

/** Result of [rebuildPlaylistForSubtitleSwap]: rebuilt items + replay index/position. */
internal data class PlaylistRebuild(
    val items: List<MediaItem>,
    val startIndex: Int,
    val startPositionMs: Long,
)

/**
 * Rebuild a media-item list so the current item carries new [subtitleConfigs],
 * keeping every other item and the active [currentIndex]/[startPositionMs].
 *
 * ponytail: pure fn extracted so the P0 regression (setMediaItem wiping the
 * playlist on subtitle-add) is unit-testable without a real ExoPlayer.
 */
internal fun rebuildPlaylistForSubtitleSwap(
    playlist: List<MediaItem>?,
    currentUri: String,
    currentTitle: String,
    currentIndex: Int,
    startPositionMs: Long,
    subtitleConfigs: List<MediaItem.SubtitleConfiguration>,
    buildItem: (String, String, List<MediaItem.SubtitleConfiguration>) -> MediaItem,
): PlaylistRebuild {
    val items = playlist?.mapIndexed { idx, item ->
        if (idx == currentIndex) {
            buildItem(currentUri, currentTitle, subtitleConfigs)
        } else {
            item
        }
    } ?: listOf(buildItem(currentUri, currentTitle, subtitleConfigs))
    return PlaylistRebuild(items, currentIndex, startPositionMs)
}
