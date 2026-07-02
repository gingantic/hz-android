package com.rhnxdev.hzplayer.data.datasource.player

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.rhnxdev.hzplayer.domain.model.PlayerState
import com.rhnxdev.hzplayer.domain.model.PlayerStateInfo
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [IPlayerEngine] implementation backed by Media3 [ExoPlayer].
 *
 * This is a thin adapter over [MediaPlayerHolder] that exposes the
 * same interface as [VlcEngine], allowing the app to switch between
 * engines transparently.
 *
 * The underlying [ExoPlayer] instance is available via [getExoPlayer]
 * so that `PlayerView` and [MediaPlaybackService] can bind to it
 * directly.
 */
@Singleton
class ExoPlayerEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val playerHolder: MediaPlayerHolder,
) : IPlayerEngine {

    /** The raw ExoPlayer instance for `PlayerView` / service binding. */
    val player: Player get() = playerHolder.player

    override val playbackState: StateFlow<PlayerStateInfo>
        get() = playerHolder.playbackStateInfo

    /** The active engine type identifier. */
    val engineType: EngineType get() = EngineType.EXO_PLAYER

    // ── Current media tracking for external subtitles ──────────

    private var currentMediaUri: String? = null
    private var currentMediaTitle: String? = null
    private val externalSubtitleUris = mutableListOf<Uri>()

    companion object {
        private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub")
        private val SUBTITLE_SCHEMES_WITH_DIR = setOf("file", "smb", "ftp", "sftp")
    }

    // ── IPlayerEngine ──────────────────────────────────────────

    override fun play(uri: String, title: String, isVideo: Boolean) {
        currentMediaUri = uri
        currentMediaTitle = title
        subtitleConfigs.clear()
        externalSubtitleUris.clear()

        // Auto-discover neighbor subtitle files (same dir, same base name)
        val neighborSubs = findNeighborSubtitleFiles(uri)
        for (subUri in neighborSubs) {
            val mimeType = inferSubtitleMimeType(subUri)
            subtitleConfigs.add(
                MediaItem.SubtitleConfiguration.Builder(subUri)
                    .setMimeType(mimeType)
                    .setLanguage("und")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            )
        }

        player.stop()
        player.clearMediaItems()
        player.setMediaItem(buildMediaItemWithSubtitles(uri, title))
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

    override fun setPlaybackSpeed(speed: Float) {
        playerHolder.updateSpeed(speed)
    }

    override fun setVideoSurface(surface: Surface?) {
        // ExoPlayer's PlayerView manages the surface internally.
        // This is a no-op for ExoPlayer.
    }

    // ── Subtitle delay ─────────────────────────────────────────────

    private var subtitleDelayMs: Long = 0

    override fun setSubtitleDelay(delayMs: Long) {
        subtitleDelayMs = delayMs
    }

    override fun getSubtitleDelay(): Long = subtitleDelayMs

    // ── External subtitles ─────────────────────────────────────────

    /** All subtitle configurations currently associated with the media item. */
    private val subtitleConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()

    /** Build a [MediaItem] with the current media URI and all subtitle configs. */
    private fun buildMediaItemWithSubtitles(uri: String, title: String): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title)
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
            externalSubtitleUris.add(uri)

            val currentUri = currentMediaUri
            val currentTitle = currentMediaTitle
            if (currentUri != null) {
                val position = player.currentPosition
                val wasPlaying = player.isPlaying
                player.stop()
                player.clearMediaItems()
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

    // ── Subtitle / CC track selection ───────────────────────────

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

    override fun release() {
        playerHolder.release()
    }

    // ── External subtitle helpers ────────────────────────────────

    /**
     * Find subtitle files matching the video's base name.
     *
     * - **Local** (`file://`): scans sibling files in the parent dir.
     * - **Network** (smb/ftp/sftp/http): swaps the extension — the remote server
     *   must serve the subtitle file (ExoPlayer/Media3 will attempt to fetch).
     */
    private fun findNeighborSubtitleFiles(videoUri: String): List<Uri> {
        val androidUri = Uri.parse(videoUri)
        val scheme = androidUri.scheme?.lowercase() ?: "file"

        return if (scheme == "file") {
            val videoPath = androidUri.path ?: return emptyList()
            val videoFile = File(videoPath)
            val parentDir = videoFile.parentFile ?: return emptyList()
            val baseName = videoFile.nameWithoutExtension

            parentDir.listFiles()
                ?.filter { file ->
                    val ext = file.extension.lowercase()
                    file.nameWithoutExtension.equals(baseName, ignoreCase = true) &&
                        SUBTITLE_EXTENSIONS.contains(ext)
                }
                ?.map { Uri.fromFile(it) }
                ?: emptyList()
        } else if (SUBTITLE_SCHEMES_WITH_DIR.contains(scheme) || scheme.startsWith("http")) {
            val path = androidUri.path ?: return emptyList()
            val baseName = path.substringBeforeLast('.')
            if (baseName == path) return emptyList()

            SUBTITLE_EXTENSIONS.mapNotNull { ext ->
                val subPath = "$baseName.$ext"
                androidUri.buildUpon().path(subPath).build()
            }
        } else {
            emptyList()
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
