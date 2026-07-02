package com.rhnxdev.hzplayer.data.datasource.player

import android.content.Context
import android.net.Uri
import android.view.Surface
import android.view.SurfaceView
import com.rhnxdev.hzplayer.domain.model.PlayerState
import com.rhnxdev.hzplayer.domain.model.PlayerStateInfo
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [IPlayerEngine] implementation backed by libVLC native library.
 *
 * ## Surface lifecycle
 *
 * Call [setSurfaceView] from your `SurfaceView`'s
 * [android.view.SurfaceHolder.Callback.surfaceCreated] to connect the
 * rendering surface.  Call [removeSurfaceView] on surface destruction.
 * Playback is automatically deferred until a surface is available.
 *
 * ## Reference counting
 *
 * libVLC Java objects use manual reference counting on top of JNI
 * native peers.  Every `Media` created here is paired with a
 * corresponding `release()`.  Call [release] when the engine is no
 * longer needed.
 */
@Singleton
class VlcEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : IPlayerEngine {

    // ── Native instances ───────────────────────────────────────

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentMedia: Media? = null

    // ── State flow ─────────────────────────────────────────────

    private val _playbackState = MutableStateFlow(PlayerStateInfo())
    override val playbackState: StateFlow<PlayerStateInfo> = _playbackState.asStateFlow()

    // ── Internal state ─────────────────────────────────────────

    private var currentUri: String? = null
    private var currentTitle: String? = null
    private var currentIsVideo = false
    private var pendingPlay = false
    private var userPaused = false
    private var surfacesAttached = false

    init {
        initialize()
    }

    // ── Initialization ─────────────────────────────────────────

    private fun initialize() {
        val args = arrayListOf(
            "--verbose=2",
            "--network-caching=300",
            "--file-caching=300",
        )
        libVLC = LibVLC(appContext, args)
        mediaPlayer = MediaPlayer(libVLC)
        setupEventListeners()
    }

    // ── Event listener ─────────────────────────────────────────

    private fun setupEventListeners() {
        mediaPlayer?.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> {
                    // libVLC starts opening/connecting to the network resource.
                    // Immediately enter BUFFERING so the loading indicator appears.
                    _playbackState.value = _playbackState.value.copy(
                        state = PlayerState.BUFFERING,
                        isPlaying = false,
                    )
                }

                MediaPlayer.Event.Buffering -> {
                    // event.buffering is a Float from 0.0 to 100.0 representing
                    // how full the network cache is. Below 100 % we are buffering;
                    // at 100 % the engine is ready to play again.
                    val pct = event.buffering // 0f..100f
                    val dur = mediaPlayer?.length ?: 0L
                    val bufferedPos = if (dur > 0 && pct > 0f) {
                        ((pct / 100f) * dur).toLong().coerceIn(0L, dur)
                    } else {
                        _playbackState.value.bufferedPosition
                    }
                    val isFullyBuffered = pct >= 100f
                    _playbackState.value = _playbackState.value.copy(
                        state = if (isFullyBuffered) PlayerState.READY else PlayerState.BUFFERING,
                        bufferedPosition = bufferedPos,
                    )
                }

                MediaPlayer.Event.Playing -> {
                    _playbackState.value = _playbackState.value.copy(
                        state = PlayerState.READY,
                        isPlaying = true,
                    )
                    pendingPlay = false
                }

                MediaPlayer.Event.Paused -> {
                    _playbackState.value = _playbackState.value.copy(
                        state = PlayerState.READY,
                        isPlaying = false,
                    )
                }

                MediaPlayer.Event.Stopped -> {
                    _playbackState.value = _playbackState.value.copy(
                        state = PlayerState.IDLE,
                        isPlaying = false,
                        currentPosition = 0,
                    )
                }

                MediaPlayer.Event.EndReached -> {
                    _playbackState.value = _playbackState.value.copy(
                        state = PlayerState.ENDED,
                        isPlaying = false,
                    )
                }

                MediaPlayer.Event.EncounteredError -> {
                    _playbackState.value = _playbackState.value.copy(
                        state = PlayerState.IDLE,
                        isPlaying = false,
                    )
                    pendingPlay = false
                }

                MediaPlayer.Event.TimeChanged -> {
                    val time = mediaPlayer?.time ?: 0L
                    val dur = mediaPlayer?.length ?: 0L
                    _playbackState.value = _playbackState.value.copy(
                        currentPosition = time.coerceAtLeast(0),
                        duration = dur.coerceAtLeast(0),
                    )
                }

                MediaPlayer.Event.PositionChanged -> {
                    val dur = mediaPlayer?.length ?: 0L
                    val pos = (mediaPlayer?.position ?: 0f) * dur
                    _playbackState.value = _playbackState.value.copy(
                        currentPosition = pos.toLong().coerceAtLeast(0),
                        duration = dur.coerceAtLeast(0),
                    )
                }

                MediaPlayer.Event.Vout -> {
                    // Video output dimension changed — video surface is now active.
                    // Query dimensions for aspect ratio.
                }

                MediaPlayer.Event.PausableChanged -> {
                    // Forwarded when the media's pausable state changes.
                }
            }
        }
    }

    // ── IPlayerEngine implementation ───────────────────────────

    override fun play(uri: String, title: String, isVideo: Boolean) {
        currentUri = uri
        currentTitle = title
        currentIsVideo = isVideo

        // Stop any active playback and release previous media
        mediaPlayer?.stop()
        releaseCurrentMedia()

        val androidUri = Uri.parse(uri)
        val vlc = libVLC ?: return

        val media = if (androidUri.scheme == "smb" && androidUri.userInfo != null) {
            val userInfo = androidUri.userInfo ?: ""
            val parts = userInfo.split(":")
            val user = Uri.decode(parts.getOrNull(0) ?: "")
            val pass = Uri.decode(parts.getOrNull(1) ?: "")

            // Rebuild clean URL without user info (keep as Uri object)
            val cleanUri = Uri.Builder()
                .scheme(androidUri.scheme)
                .encodedAuthority(androidUri.authority?.substringAfter("@"))
                .path(androidUri.path)
                .query(androidUri.query)
                .fragment(androidUri.fragment)
                .build()

            Media(vlc, cleanUri).apply {
                setHWDecoderEnabled(true, false)
                addOption(":network-caching=300")
                addOption(":file-caching=300")
                if (user.isNotEmpty()) {
                    addOption(":smb-user=$user")
                }
                if (pass.isNotEmpty()) {
                    addOption(":smb-pwd=$pass")
                }
            }
        } else {
            createMedia(vlc, uri).apply {
                setHWDecoderEnabled(true, false)
                addOption(":network-caching=300")
                addOption(":file-caching=300")
            }
        }

        // Keep the Java Media wrapper alive as long as the player
        // references it.  Releasing it here could destroy the native
        // peer while MediaPlayer still holds a native reference.
        currentMedia = media
        mediaPlayer?.media = media

        // Auto-discover neighbor subtitle files (same dir/ext-swap for network)
        val neighborSubs = findNeighborSubtitleFiles(uri)
        for (subUri in neighborSubs) {
            mediaPlayer?.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, subUri, true)
        }

        userPaused = false

        if (!currentIsVideo || surfacesAttached) {
            mediaPlayer?.play()
        } else {
            pendingPlay = true
        }

        _playbackState.value = _playbackState.value.copy(
            state = PlayerState.BUFFERING,
            isPlaying = false,
            currentPosition = 0,
            duration = 0,
        )
    }

    override fun pause() {
        userPaused = true
        mediaPlayer?.pause()
    }

    override fun resume() {
        userPaused = false
        if (!currentIsVideo || surfacesAttached) {
            mediaPlayer?.play()
        } else {
            pendingPlay = true
        }
    }

    override fun stop() {
        pendingPlay = false
        userPaused = false
        mediaPlayer?.stop()
    }

    override fun seekTo(positionMs: Long) {
        // Signal buffering immediately so the UI shows the loading indicator
        // before libVLC starts firing its Buffering events.
        _playbackState.value = _playbackState.value.copy(
            state = PlayerState.BUFFERING,
        )
        mediaPlayer?.time = positionMs.coerceAtLeast(0)
    }

    override fun skipForward(ms: Long) {
        val current = mediaPlayer?.time ?: 0L
        val duration = mediaPlayer?.length ?: 0L
        seekTo((current + ms).coerceAtMost(duration.coerceAtLeast(0)))
    }

    override fun skipBackward(ms: Long) {
        val current = mediaPlayer?.time ?: 0L
        seekTo((current - ms).coerceAtLeast(0))
    }

    override fun isPlaying(): Boolean {
        return mediaPlayer?.isPlaying == true
    }

    override fun getDuration(): Long {
        return (mediaPlayer?.length ?: 0L).coerceAtLeast(0)
    }

    override fun getCurrentPosition(): Long {
        return (mediaPlayer?.time ?: 0L).coerceAtLeast(0)
    }

    override fun setPlaybackSpeed(speed: Float) {
        mediaPlayer?.rate = speed.coerceIn(0.25f, 4.0f)
        _playbackState.value = _playbackState.value.copy(playbackSpeed = speed)
    }

    override fun setVideoSurface(surface: Surface?) {
        // This is handled by the VlcVideoSurface composable via
        // setSurfaceView()/removeSurfaceView() for a richer lifecycle.
        // No-op here — see those methods instead.
    }

    override fun release() {
        pendingPlay = false
        surfacesAttached = false

        mediaPlayer?.let { mp ->
            mp.getVLCVout()?.detachViews()
            mp.stop()
            mp.release()
        }
        mediaPlayer = null

        releaseCurrentMedia()

        libVLC?.release()
        libVLC = null
    }

    // ── VLC-specific surface management (called from composable) ──

    /**
     * Attach a [SurfaceView] for video output.
     *
     * Call from [android.view.SurfaceHolder.Callback.surfaceCreated].
     * The engine will immediately attempt to start deferred playback.
     */
    fun setSurfaceView(surfaceView: SurfaceView) {
        val mp = mediaPlayer ?: return
        val vout = mp.getVLCVout() ?: return

        vout.setVideoView(surfaceView)
        vout.setWindowSize(
            surfaceView.width.coerceAtLeast(1),
            surfaceView.height.coerceAtLeast(1),
        )
        vout.attachViews(
            org.videolan.libvlc.interfaces.IVLCVout.OnNewVideoLayoutListener { _, w, h, _, _, _, _ ->
                _videoWidth = w
                _videoHeight = h
            },
        )

        surfacesAttached = true

        if (pendingPlay) {
            pendingPlay = false
            mp.play()
        }
    }

    /**
     * Update the render window size (used during orientation changes).
     */
    fun setWindowSize(width: Int, height: Int) {
        val mp = mediaPlayer ?: return
        val vout = mp.getVLCVout() ?: return
        vout.setWindowSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    /**
     * Detach the video surface.
     *
     * Call from [android.view.SurfaceHolder.Callback.surfaceDestroyed].
     */
    fun removeSurfaceView() {
        surfacesAttached = false
        mediaPlayer?.getVLCVout()?.detachViews()
    }

    /**
     * Provide video dimensions for aspect ratio calculation.
     *
     * Video dimensions are tracked internally via
     * [IVLCVout.OnNewVideoLayoutListener] when the surface is
     * attached.  Returns (0,0) if not yet known.
     */
    fun getVideoSize(): Pair<Int, Int> {
        return Pair(_videoWidth, _videoHeight)
    }

    /** Dimensions set by [IVLCVout.OnNewVideoLayoutListener]. */
    @Volatile private var _videoWidth = 0
    @Volatile private var _videoHeight = 0

    /** The active engine type identifier. */
    val engineType: EngineType get() = EngineType.VLC

    // ── Internals ──────────────────────────────────────────────

    private fun createMedia(vlc: LibVLC, uriString: String): Media {
        val androidUri = Uri.parse(uriString)
        return when (androidUri.scheme) {
            null, "", "file" -> {
                val path = androidUri.path ?: uriString
                Media(vlc, path)
            }
            "content" -> {
                val resolved = resolveContentUri(androidUri)
                if (resolved != null) {
                    Media(vlc, resolved)
                } else {
                    Media(vlc, androidUri)
                }
            }
            else -> {
                Media(vlc, androidUri)
            }
        }
    }

    private fun releaseCurrentMedia() {
        currentMedia?.release()
        currentMedia = null
    }

    /**
     * Convert any URI to a form libVLC can open.
     *
     * Android `content://` URIs are not natively handled by libVLC's
     * C core, so we resolve them to a file descriptor path when possible.
     */
    private fun convertUri(uri: String): String {
        val androidUri = Uri.parse(uri)
        return when (androidUri.scheme) {
            "content" -> resolveContentUri(androidUri) ?: uri
            "file" -> androidUri.path ?: uri
            else -> uri // http, rtmp, rtsp, etc. pass through
        }
    }

    private fun resolveContentUri(uri: Uri): String? {
        return try {
            val fd = appContext.contentResolver
                .openFileDescriptor(uri, "r") ?: return null
            val fname = fd.detachFd()
            // libVLC can open /proc/self/fd/<N> paths
            "/proc/self/fd/$fname"
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    // ── Subtitle auto-detection ────────────────────────────

    companion object {
        private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub")
        private val SUBTITLE_SCHEMES_WITH_DIR = setOf("file", "smb", "ftp", "sftp")
    }

    /**
     * Find subtitle files matching the video's base name.
     *
     * - **Local files** (`file://`): scans sibling files in the parent dir.
     * - **Network URIs** (smb/ftp/sftp/http): swaps the file extension in the URI path
     *   (libVLC handles the actual IO — no explicit directory listing available).
     */
    private fun findNeighborSubtitleFiles(videoUri: String): List<Uri> {
        val androidUri = Uri.parse(videoUri)
        val scheme = androidUri.scheme?.lowercase() ?: "file"

        return if (scheme == "file") {
            // Local — scan parent dir
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
            // Network — swap the extension, let libVLC try to fetch each.
            // The path typically ends with the filename; we construct candidate URIs.
            val path = androidUri.path ?: return emptyList()
            val baseName = path.substringBeforeLast('.')
            if (baseName == path) return emptyList() // no extension at all

            SUBTITLE_EXTENSIONS.mapNotNull { ext ->
                val subPath = "$baseName.$ext"
                androidUri.buildUpon().path(subPath).build()
            }
        } else {
            emptyList()
        }
    }

    // ── Subtitle / CC track selection ───────────────────────────

    override fun addExternalSubtitle(uri: Uri): Boolean {
        return try {
            mediaPlayer?.let { mp ->
                mp.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, uri, true)
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    override fun setSubtitleDelay(delayMs: Long) {
        // VLC uses microseconds
        mediaPlayer?.setSpuDelay(delayMs * 1000L)
    }

    override fun getSubtitleDelay(): Long {
        return (mediaPlayer?.spuDelay ?: 0L) / 1000L
    }

    private fun getSpuTrackDescriptions(): Array<MediaPlayer.TrackDescription> {
        return mediaPlayer?.spuTracks ?: emptyArray()
    }

    override fun getSubtitleTracks(): List<String> {
        return getSpuTrackDescriptions()
            .filter { it.id != -1 }
            .map { it.name ?: "Track ${it.id}" }
    }

    override fun getSelectedSubtitleTrack(): Int {
        val currentId = mediaPlayer?.spuTrack ?: -1
        if (currentId == -1) return -1
        val tracks = getSpuTrackDescriptions().filter { it.id != -1 }
        return tracks.indexOfFirst { it.id == currentId }
    }

    override fun selectSubtitleTrack(index: Int) {
        val tracks = getSpuTrackDescriptions().filter { it.id != -1 }
        if (index in tracks.indices) {
            mediaPlayer?.setSpuTrack(tracks[index].id)
        } else {
            mediaPlayer?.setSpuTrack(-1)
        }
    }
}
