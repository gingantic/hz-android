package com.rhnxdev.hzplayer.data.datasource.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.PlayerStateInfo
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExoPlayerEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val playerHolder: MediaPlayerHolder,
) : IPlayerEngine {

    val player: Player get() = playerHolder.player

    val subtitleCues: StateFlow<List<androidx.media3.common.text.Cue>>
        get() = playerHolder.subtitleCues
    val videoDecoderName: StateFlow<String>
        get() = playerHolder.videoDecoderName
    val audioDecoderName: StateFlow<String>
        get() = playerHolder.audioDecoderName

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

    companion object {
        private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub")
        private val SUBTITLE_SCHEMES_WITH_DIR = setOf("file", "smb", "ftp", "sftp", "webdav", "webdavs")
    }

    override fun play(uri: String, title: String, artist: String?, isVideo: Boolean) {
        currentMediaUri = uri
        currentMediaTitle = title
        currentPlaylist = null
        subtitleConfigs.clear()
        discoverNeighborSubtitles(uri)
        playerHolder.clearError()
        player.setMediaItem(buildMediaItemWithSubtitles(uri, title, artist))
        player.prepare()
        player.play()
    }

    fun playPlaylist(items: List<Pair<String, String>>, startIndex: Int, startPositionMs: Long) {
        if (items.isEmpty()) return
        currentPlaylist = null
        subtitleConfigs.clear()
        val mediaItems = items.map { (uri, title) ->
            // Only discover subtitles for the first item (startIndex); others get plain items
            if (uri == items[startIndex].first) {
                currentMediaUri = uri
                currentMediaTitle = title
                discoverNeighborSubtitles(uri)
            }
            buildMediaItemWithSubtitles(uri, title)
        }
        playerHolder.clearError()
        player.setMediaItems(mediaItems, startIndex.coerceIn(0, mediaItems.lastIndex), startPositionMs)
        player.prepare()
        player.play()
    }

    fun playAudioPlaylist(items: List<AudioItem>, startIndex: Int) {
        if (items.isEmpty()) return
        currentPlaylist = null
        subtitleConfigs.clear()
        val mediaItems = items.map { audio ->
            if (audio.uri == items[startIndex].uri) {
                currentMediaUri = audio.uri
                currentMediaTitle = audio.title
                discoverNeighborSubtitles(audio.uri)
            }
            buildMediaItemWithSubtitles(audio.uri, audio.title, audio.artist)
        }
        playerHolder.clearError()
        player.setMediaItems(mediaItems, startIndex.coerceIn(0, mediaItems.lastIndex), 0L)
        player.prepare()
        player.play()
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

    override fun isPlaying(): Boolean = player.isPlaying

    override fun getDuration(): Long = player.duration.coerceAtLeast(0)

    override fun getCurrentPosition(): Long = player.currentPosition.coerceAtLeast(0)

    override fun getBufferedPosition(): Long = player.bufferedPosition.coerceAtLeast(0)

    override fun setPlaybackSpeed(speed: Float) {
        playerHolder.updateSpeed(speed)
    }

    // ── Subtitle delay ─────────────────────────────────────────────

    private var subtitleDelayMs: Long = 0

    override fun setSubtitleDelay(delayMs: Long) {
        subtitleDelayMs = delayMs
    }

    override fun getSubtitleDelay(): Long = subtitleDelayMs

    // ── External subtitles ─────────────────────────────────────────

    private val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()

    private fun buildMediaItemWithSubtitles(uri: String, title: String, artist: String? = null): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .build(),
            )
        if (subtitleConfigs.isNotEmpty()) {
            builder.setSubtitleConfigurations(subtitleConfigs.toList())
        }
        return builder.build()
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
            if (currentUri != null) {
                val position = player.currentPosition
                val wasPlaying = player.isPlaying
                player.setMediaItem(buildMediaItemWithSubtitles(currentUri, currentTitle ?: ""))
                player.prepare()
                player.seekTo(position)
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
        return getExoTextTracks().map { it.displayName }
    }

    override fun getSelectedSubtitleTrack(): Int {
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
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
        }
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
        }
    }

    override fun clearError() {
        playerHolder.clearError()
    }

    override fun retry() {
        val item = player.currentMediaItem ?: return
        playerHolder.clearError()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    override fun release() {
        playerHolder.release()
    }

    // ── Subtitle discovery ───────────────────────────────────────

    private fun discoverNeighborSubtitles(uri: String) {
        playerHolder.setDiscovering()
        val subs = try {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                findNeighborSubtitleFiles(uri)
            }
        } catch (_: Exception) {
            emptyList()
        }
        if (subs.isNotEmpty()) {
            subtitleConfigs.addAll(subs.map { subUri ->
                MediaItem.SubtitleConfiguration.Builder(subUri)
                    .setMimeType(inferSubtitleMimeType(subUri))
                    .setLanguage("und")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            })
        }
    }

    // ── Debug stats ─────────────────────────────────────────────

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun collectDebugStats(): com.rhnxdev.hzplayer.domain.model.DebugStats {
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
            soCInfo = "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}"
                .ifBlank { "${android.os.Build.HARDWARE}" },
        )
    }

    /** Tag decoder as HW or SW by its registration name.
     *  OMX.google.* / c2.android.* → SW (Android software).
     *  OMX.* (not google) / c2.{qti,mediatek,exynos,ti}.* → HW.
     *  Anything else → SW (conservative). */
    private fun labelHwSw(decoderName: String): String = when {
        decoderName.startsWith("c2.android.") -> "SW"
        decoderName.startsWith("c2.") -> "HW"
        decoderName.startsWith("OMX.") && !decoderName.contains(".google.", ignoreCase = true) -> "HW"
        else -> "SW"
    }

    // ── External subtitle helpers ────────────────────────────────

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
                    // (avoids double-encoding %20 → %2520) and the userInfo/host/port,
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
