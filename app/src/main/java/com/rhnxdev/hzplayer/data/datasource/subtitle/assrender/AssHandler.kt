package com.rhnxdev.hzplayer.data.datasource.subtitle.assrender

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates the ASS subtitle pipeline:
 * - Receives raw ASS data from [AssTrackOutput] (header + dialogue chunks)
 * - Feeds data to libass via [AssDirectBridge]
 * - Renders subtitle bitmaps synced to playback time onto a [SubtitleOverlayView]
 *
 * Singleton: one instance is shared between [MediaPlayerHolder] (which wires the
 * ExoPlayer factories that feed it) and the Compose overlay (which hosts the view).
 */
@Singleton
class AssHandler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Called when the first ASS track is detected — use to hide ExoPlayer subtitles. */
    var onAssTrackSelected: (() -> Unit)? = null

    private val overlayView = SubtitleOverlayView(context)

    val view: SubtitleOverlayView get() = overlayView

    private var nativeHandle: Long = 0L
    private val nativeLock = Any()
    private var renderBitmap: Bitmap? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val trackFormats = mutableMapOf<Int, Format>()
    private val trackHeaders = mutableMapOf<Int, ByteArray>()
    private val trackEvents =
        ConcurrentHashMap<Int, CopyOnWriteArrayList<Triple<Long, Long, String>>>()
    private var activeTrackId: Int = -1

    /**
     * External subtitle files (loaded outside ExoPlayer) are registered as
     * synthetic libass tracks so they appear in the subtitle-selection dialog
     * alongside embedded tracks. We use a high id range to avoid colliding with
     * embedded track ids (which are small ints from ExoPlayer).
     */
    private val externalTrackNames = mutableListOf<String>()
    private val externalTrackIds = mutableListOf<Int>()
    private val externalTrackData = mutableListOf<ByteArray>()
    private var nextExternalTrackId = 100_000
    /** Fired when the external-track list changes, so the engine can refresh the UI. */
    var onExternalTrackListChanged: (() -> Unit)? = null
    /** True after the first ASS track header is received and nativeInit succeeds. */
    var initialized = false
        private set
    @Volatile
    private var pendingFormatToSelect: Format? = null

    private val pendingFonts = mutableListOf<Pair<String, ByteArray>>()

    @Volatile
    var currentTimeUs: Long = 0L

    @Volatile
    private var lastPositionUs: Long = 0L

    @Volatile
    private var lastPositionRealtimeUs: Long = 0L

    @Volatile
    private var needsFontReload = false

    @Volatile
    private var hasLoadedFirstTime = false

    /** Subtitle timing offset in ms (positive = subtitles appear later).
     *  Applied as a render-position shift in [renderFrame] so libass draws the
     *  cue that belongs to (playbackPos − delay). Reset on new media ([reset]). */
    @Volatile
    var subtitleDelayMs: Long = 0

    fun updatePosition(positionUs: Long, elapsedRealtimeUs: Long) {
        // Sanity-check: reject obviously corrupted values.
        // Valid video position must be < 24 h (86400 s = 86_400_000_000 µs).
        val maxValidPositionUs = 24L * 3_600L * 1_000_000L
        if (positionUs < 0 || positionUs > maxValidPositionUs) {
            // ExoPlayer sometimes passes elapsedRealtimeUs in positionUs slot — ignore
            return
        }
        lastPositionUs = positionUs
        lastPositionRealtimeUs = elapsedRealtimeUs
        currentTimeUs = positionUs
    }

    var player: ExoPlayer? = null

    private var videoWidth = 1920
    private var videoHeight = 1080

    /** Current renderer frame size — used to stamp PlayResX/Y into synthesized ASS. */
    fun getVideoWidth(): Int = videoWidth
    fun getVideoHeight(): Int = videoHeight

    private var lastRenderedMs: Long = Long.MIN_VALUE
    private var choreographerCallback: android.view.Choreographer.FrameCallback? = null

    init {
        setupFontconfig()
    }

    companion object {
        private const val TAG = "assrender"
    }

    fun onTrackHeader(trackId: Int, headerData: ByteArray, format: Format) {
        trackFormats[trackId] = format
        trackHeaders[trackId] = headerData

        var shouldInvokeCallback = false
        synchronized(nativeLock) {
            if (!initialized) {
                Log.i(TAG, "[INIT] first ASS track — initializing native context ($videoWidth x $videoHeight)")
                nativeHandle = AssDirectBridge.nativeInit(videoWidth, videoHeight, 1.0f)
                if (nativeHandle == 0L) {
                    Log.e(TAG, "[INIT] nativeInit returned 0 — FATAL")
                    return
                }
                Log.i(TAG, "[INIT] nativeInit OK handle=$nativeHandle")
                renderBitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
                initialized = true
                flushPendingFonts()
                shouldInvokeCallback = true
            }
        }
        if (shouldInvokeCallback) {
            onAssTrackSelected?.invoke()
        }

        pendingFormatToSelect?.let { pending ->
            selectTrackByFormat(pending)
        }
    }

    /**
     * Process one subtitle sample.
     *
     * [data] from ExoPlayer/MatroskaExtractor arrives as a full "Dialogue:" line
     * in one of two formats:
     *
     * MKV block (colon-only timestamps):
     *   Dialogue: Start(0),Duration,ReadOrder,Layer,Style,Name,ML,MR,MV,Effect,Text
     *   e.g. "Dialogue: 0:00:00:00,0:00:04:63,32,0,Italics,,0,0,0,,Hello"
     *
     * Standard ASS (dot-separated centiseconds):
     *   Dialogue: Layer,Start,End,Style,Name,ML,MR,MV,Effect,Text
     *   e.g. "Dialogue: 0,0:04:14.20,0:04:18.83,Italics,,0,0,0,,Hello"
     *
     * ass_process_chunk() expects MKV body WITHOUT timestamps/prefix:
     *   ReadOrder,Layer,Style,Name,ML,MR,MV,Effect,Text  (9 fields)
     */
    fun onSubtitleSample(trackId: Int, timeUs: Long, data: ByteArray) {
        if (!initialized || nativeHandle == 0L) return

        val line = String(data, Charsets.UTF_8).trim()
        if (!line.startsWith("Dialogue:")) return

        val content = line.removePrefix("Dialogue:").trimStart()
        val isStandardAss = isStandardAssTimestamp(
            content.split(",", limit = 3).getOrNull(1)?.trim() ?: ""
        )

        val startMs: Long
        val durationMs: Long
        val bodyFields: String // Layer,Style,Name,ML,MR,MV,Effect,Text

        if (isStandardAss) {
            // Standard ASS: Layer,Start,End,Style,Name,ML,MR,MV,Effect,Text  (10 fields)
            val f = content.split(",", limit = 10)
            if (f.size < 10) {
                Log.w(TAG, "[TRACK] standard ASS line has <10 fields, skipping")
                return
            }
            startMs    = parseStandardAssTimeMs(f[1].trim())
            durationMs = (parseStandardAssTimeMs(f[2].trim()) - startMs).coerceAtLeast(0L)
            
            bodyFields = "${f[0].trim()},${f[3].trim()},${f[4].trim()},${f[5].trim()}," +
                        "${f[6].trim()},${f[7].trim()},${f[8].trim()},${f[9]}"
        } else {
            // MKV block: Start(0),Duration,ReadOrder,Layer,Style,Name,ML,MR,MV,Effect,Text (11 fields)
            val f = content.split(",", limit = 11)
            if (f.size < 11) {
                Log.w(TAG, "[TRACK] MKV line has <11 fields, skipping")
                return
            }
            durationMs = parseMkvAssTimeMs(f[1].trim())
            startMs    = timeUs / 1000
            
            bodyFields = "${f[3].trim()},${f[4].trim()},${f[5].trim()}," +
                        "${f[6].trim()},${f[7].trim()},${f[8].trim()},${f[9].trim()},${f[10]}"
        }

        val events = trackEvents.getOrPut(trackId) { CopyOnWriteArrayList() }
        var existingIdx = -1
        // Scan backwards since seek-redelivered cues are likely near the end of the list
        for (i in events.indices.reversed()) {
            val evt = events[i]
            if (evt.first == startMs && evt.second == durationMs && evt.third == bodyFields) {
                existingIdx = i
                break
            }
        }

        val readOrder: Int
        if (existingIdx != -1) {
            readOrder = existingIdx
        } else {
            events.add(Triple(startMs, durationMs, bodyFields))
            readOrder = events.size - 1
        }

        val chunkBytes = "$readOrder,$bodyFields".toByteArray(Charsets.UTF_8)

        if (trackId == activeTrackId) {
            synchronized(nativeLock) {
                if (nativeHandle != 0L) {
                    AssDirectBridge.nativeProcessChunk(nativeHandle, chunkBytes, startMs, durationMs)
                }
            }
        }
    }

    fun onFontAttachment(name: String, data: ByteArray) {
        // Peek at magic bytes to confirm it's a real font binary
        val magic = if (data.size >= 4)
            data.take(4).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        else "(too short)"
        Log.d(TAG, "[FONT] onFontAttachment: name='$name' size=${data.size}B magic=[$magic] initialized=$initialized")

        synchronized(nativeLock) {
            if (!initialized || nativeHandle == 0L) {
                pendingFonts.add(Pair(name, data))
                Log.d(TAG, "[FONT] buffered (not yet initialized) — pendingFonts.size=${pendingFonts.size}")
                return
            }
            AssDirectBridge.nativeAddFont(nativeHandle, name, data)
            needsFontReload = true
            Log.d(TAG, "[FONT] nativeAddFont done, needsFontReload=true")
        }
    }

    /**
     * Load an external `.ass`/`.ssa`/converted file into libass and register it as
     * a synthetic subtitle track so it appears in the selection dialog. Unlike
     * embedded tracks (one codec-private header fed via [onTrackHeader]), external
     * ASS files carry their own header so we load it directly into the native
     * context without touching [trackHeaders]. Each call appends a new track; the
     * previously active external track is cleared (libass renders one track at a
     * time) but its registration stays so it can be re-selected later.
     */
    fun loadExternalTrack(data: ByteArray, displayName: String = "External subtitle") {
        var shouldInvokeCallback = false
        val trackId: Int
        synchronized(nativeLock) {
            if (nativeHandle == 0L) {
                nativeHandle = AssDirectBridge.nativeInit(videoWidth, videoHeight, 1.0f)
                if (nativeHandle == 0L) {
                    Log.e(TAG, "Failed to init native context for external ASS")
                    return
                }
                renderBitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
                initialized = true
                flushPendingFonts()
                shouldInvokeCallback = true
            }
            trackId = nextExternalTrackId++
            // Store the raw header bytes so re-selecting this track reloads it.
            externalTrackIds.add(trackId)
            externalTrackNames.add(displayName)
            externalTrackData.add(data)

            // Load as the active track immediately (renders at once).
            activeTrackId = trackId
            pendingFonts.clear()
            AssDirectBridge.nativeFlush(nativeHandle)
            val ok = AssDirectBridge.nativeLoadHeader(nativeHandle, data)
            if (ok != 0) {
                Log.e(TAG, "Failed to load external ASS (${data.size} bytes)")
                return
            }
        }
        if (shouldInvokeCallback) {
            onAssTrackSelected?.invoke()
        }
        overlayView.clear()
        onExternalTrackListChanged?.invoke()
        Log.i(TAG, "Loaded external subtitle: '$displayName' ${data.size} bytes (trackId=$trackId)")
        startRenderLoop()
    }

    /** Names of registered external subtitle tracks, aligned 1:1 with [getExternalTrackIds]. */
    fun getExternalTrackNames(): List<String> = externalTrackNames.toList()

    /** Ids of registered external subtitle tracks, aligned 1:1 with [getExternalTrackNames]. */
    fun getExternalTrackIds(): List<Int> = externalTrackIds.toList()

    /** Index of the currently active external track in [getExternalTrackIds], or -1. */
    fun getActiveExternalTrackIndex(): Int {
        if (!isExternalTrack(activeTrackId)) return -1
        return externalTrackIds.indexOf(activeTrackId)
    }

    /** Whether [trackId] is a registered external subtitle track. */
    fun isExternalTrack(trackId: Int): Boolean = trackId in externalTrackIds

    private fun flushPendingFonts() {
        Log.d(TAG, "[FONT] flushPendingFonts: count=${pendingFonts.size}")
        if (pendingFonts.isEmpty()) {
            Log.d(TAG, "[FONT] no pending fonts to flush")
            return
        }
        synchronized(nativeLock) {
            if (nativeHandle != 0L) {
                pendingFonts.forEachIndexed { idx, (name, data) ->
                    Log.d(TAG, "[FONT]   flushing[$idx]: '$name' ${data.size}B")
                    AssDirectBridge.nativeAddFont(nativeHandle, name, data)
                }
                Log.d(TAG, "[FONT] flushed ${pendingFonts.size} fonts → scheduling reload")
                pendingFonts.clear()
                needsFontReload = true
            }
        }
    }

    fun selectTrack(trackId: Int) {
        if (!initialized || nativeHandle == 0L) {
            Log.w(TAG, "[TRACK] selectTrack skipped — not initialized")
            return
        }
        val header = trackHeaders[trackId]
        val externalData = if (isExternalTrack(trackId)) {
            val idx = externalTrackIds.indexOf(trackId)
            if (idx >= 0) externalTrackData[idx] else null
        } else null

        if (header == null && externalData == null) {
            Log.e(TAG, "[TRACK] selectTrack: no header for trackId=$trackId  knownTracks=${trackHeaders.keys} external=${externalTrackIds}")
            return
        }

        mainHandler.post {
            synchronized(nativeLock) {
                if (nativeHandle == 0L) return@synchronized
                activeTrackId = trackId
                overlayView.clear()
                pendingFormatToSelect = null

                AssDirectBridge.nativeFlush(nativeHandle)

                // External tracks: the stored header IS the full document — reload it.
                if (externalData != null) {
                    AssDirectBridge.nativeLoadHeader(nativeHandle, externalData)
                    startRenderLoop()
                    return@synchronized
                }

                if (header == null) { startRenderLoop(); return@synchronized }

                AssDirectBridge.nativeLoadHeader(nativeHandle, header)
                if (needsFontReload) {
                    needsFontReload = false
                    AssDirectBridge.nativeReloadFonts(nativeHandle)
                }
                val events = trackEvents[trackId]
                if (events != null) {
                    events.forEachIndexed { idx, (startMs, durationMs, bodyFields) ->
                        val chunkBytes = "$idx,$bodyFields".toByteArray(Charsets.UTF_8)
                        AssDirectBridge.nativeProcessChunk(nativeHandle, chunkBytes, startMs, durationMs)
                    }
                }
            }

            startRenderLoop()
        }
    }

    fun selectTrackByFormat(format: Format) {
        Log.d(TAG, "selectTrackByFormat: format=$format, initialized=$initialized")
        if (!initialized) {
            pendingFormatToSelect = format
            return
        }
        val targetLang = format.language?.lowercase() ?: ""
        val targetLabel = format.label?.lowercase() ?: ""

        // 1. Match by exact format ID if available
        if (!format.id.isNullOrEmpty()) {
            for ((id, fmt) in trackFormats) {
                if (fmt.id == format.id) {
                    selectTrack(id)
                    return
                }
            }
        }

        // 2. Match by non-empty label and language
        for ((id, fmt) in trackFormats) {
            val lang = fmt.language?.lowercase() ?: ""
            val label = fmt.label?.lowercase() ?: ""
            if (targetLabel.isNotEmpty() && label == targetLabel) {
                selectTrack(id)
                return
            }
            if (targetLang.isNotEmpty() && lang == targetLang) {
                selectTrack(id)
                return
            }
        }

        // 3. Auto-select if there is exactly one ASS track
        if (trackFormats.size == 1) {
            selectTrack(trackFormats.keys.first())
            return
        }

        // 4. Match by MIME/codec signature. Embedded SRT/VTT formats carry no
        //    language/label/id that matches our stored Format, but their
        //    sampleMimeType (e.g. application/x-subrip) + codecs uniquely identify
        //    the registered track. First exact codec, then mime-only.
        val targetMime = format.sampleMimeType?.lowercase()
        val targetCodecs = format.codecs?.lowercase() ?: ""
        if (!targetMime.isNullOrBlank()) {
            val byCodec = trackFormats.entries.firstOrNull { (_, fmt) ->
                val c = fmt.codecs?.lowercase() ?: ""
                targetCodecs.isNotBlank() && c == targetCodecs
            }
            if (byCodec != null) {
                selectTrack(byCodec.key)
                return
            }
            val byMime = trackFormats.entries.firstOrNull { (_, fmt) ->
                fmt.sampleMimeType?.lowercase() == targetMime
            }
            if (byMime != null) {
                selectTrack(byMime.key)
                return
            }
        }

        // 5. Fallback: match by index/id if the format has a numeric ID
        val numericId = format.id?.toIntOrNull()
        if (numericId != null && trackFormats.containsKey(numericId)) {
            selectTrack(numericId)
            return
        }

        clearOverlay()
        Log.d(TAG, "No matching ASS track for lang=$targetLang, label=$targetLabel")
    }

    private fun startRenderLoop() {
        stopRenderLoop()

        val callback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                renderFrame()
                if (choreographerCallback === this) {
                    android.view.Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
        choreographerCallback = callback
        android.view.Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun stopRenderLoop() {
        choreographerCallback?.let {
            android.view.Choreographer.getInstance().removeFrameCallback(it)
        }
        choreographerCallback = null
    }

    private var renderDiagLogged = false

    private fun renderFrame() {
        if (!initialized || nativeHandle == 0L) return
        val bitmap = renderBitmap
        if (bitmap == null) {
            if (!renderDiagLogged) {
                Log.w(TAG, "[RENDER] bitmap NULL — abort (initialized=$initialized handle=$nativeHandle video=${videoWidth}x$videoHeight)")
                renderDiagLogged = true
            }
            return
        }

        val p = player
        val playbackState = p?.playbackState ?: androidx.media3.common.Player.STATE_IDLE
        if (playbackState == androidx.media3.common.Player.STATE_READY) {
            hasLoadedFirstTime = true
        }

        if (!hasLoadedFirstTime) {
            if (!renderDiagLogged) {
                Log.w(TAG, "[RENDER] not loaded-first-time — abort (playbackState=$playbackState playerNull=${p == null} activeTrackId=$activeTrackId)")
                renderDiagLogged = true
            }
            overlayView.clear()
            return
        }

        // Converted cues are live-chunked via nativeProcessChunk, no full document reload needed.

        val isPlaying       = p?.isPlaying == true
        val speed           = p?.playbackParameters?.speed ?: 1.0f
        val mediaDurationMs = p?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE

        val positionMs: Long

        if (isPlaying && lastPositionRealtimeUs != 0L && lastPositionUs > 0L) {
            val currentRealtimeUs = android.os.SystemClock.elapsedRealtime() * 1000L
            val elapsedUs         = currentRealtimeUs - lastPositionRealtimeUs
            val interpolated      = (lastPositionUs + (elapsedUs * speed).toLong()) / 1000L
            // Safe clamp: always [0, mediaDuration], no invalid range possible
            positionMs = interpolated.coerceAtLeast(0L)
                .let { v -> if (mediaDurationMs < Long.MAX_VALUE) v.coerceAtMost(mediaDurationMs) else v }
        } else {
            positionMs = p?.currentPosition ?: (lastPositionUs / 1000L).coerceAtLeast(0L)
        }

        var hasContent = false
        // Apply the timing offset: libass renders the cue anchored at (pos − delay).
        // Positive delayMs ⇒ subtitles lag the audio; negative ⇒ lead. Coerce so we
        // never pass a negative position to nativeRender (cue-times are ≥ 0).
        val renderPosMs = (positionMs - subtitleDelayMs).coerceAtLeast(0L)
        synchronized(nativeLock) {
            if (nativeHandle != 0L) {
                if (needsFontReload) {
                    Log.d(TAG, "[FONT] renderFrame: triggering deferred font reload")
                    needsFontReload = false
                    AssDirectBridge.nativeReloadFonts(nativeHandle)
                    Log.d(TAG, "[FONT] font reload complete")
                }
                hasContent = AssDirectBridge.nativeRender(nativeHandle, renderPosMs, bitmap)
            }
        }

        if (hasContent) {
            if (!renderDiagLogged) {
                Log.i(TAG, "[RENDER] FIRST content rendered at posMs=$positionMs (was waiting on READY)")
                renderDiagLogged = true
            }
            overlayView.updateBitmap(bitmap)
        } else {
            if (positionMs % 2000 < 100) { // ~every 2s
                Log.d(TAG, "[RENDER] no content at posMs=$positionMs initialized=$initialized activeTrackId=$activeTrackId hasLoaded=$hasLoadedFirstTime nEvents=${trackEvents[activeTrackId]?.size}")
            }
            overlayView.clear()
        }
    }

    /**
     * Dump a comprehensive diagnostic snapshot to logcat.
     * Call this after track selection to see the full pipeline state.
     */
    fun logDiagnostics() {
        Log.d(TAG, "━━━━━━━━━━━ ASS DIAGNOSTIC DUMP ━━━━━━━━━━━")
        Log.d(TAG, "[DIAG] initialized=$initialized  handle=$nativeHandle")
        Log.d(TAG, "[DIAG] activeTrackId=$activeTrackId  needsFontReload=$needsFontReload")
        Log.d(TAG, "[DIAG] videoSize=${videoWidth}x${videoHeight}")
        Log.d(TAG, "[DIAG] pendingFonts.size=${pendingFonts.size}")
        Log.d(TAG, "[DIAG] trackFormats.keys=${trackFormats.keys.toList()}")
        Log.d(TAG, "[DIAG] trackHeaders.keys=${trackHeaders.keys.toList()}")
        trackHeaders.forEach { (id, hdr) ->
            Log.d(TAG, "[DIAG] header[$id]: ${hdr.size}B  preview=${String(hdr, 0, minOf(120, hdr.size), Charsets.UTF_8).replace('\n','|')}")
        }
        trackEvents.forEach { (id, evts) ->
            Log.d(TAG, "[DIAG] events[$id]: ${evts.size} items")
        }
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    /**
     * Returns true if [time] looks like a standard ASS timestamp (H:MM:SS.CC)
     * i.e. the last separator before centiseconds is a dot, not a colon.
     */
    private fun isStandardAssTimestamp(time: String): Boolean {
        // Standard: "0:03:51.88" — has exactly 2 colons and 1 dot
        // MKV:      "0:00:03:63" — has exactly 3 colons, no dot
        return time.contains('.')
    }

    /**
     * Parse standard ASS timestamp "H:MM:SS.CC" → milliseconds.
     * The centiseconds part is separated by a dot.
     */
    private fun parseStandardAssTimeMs(time: String): Long {
        return try {
            val dotIdx = time.lastIndexOf('.')
            if (dotIdx < 0) return 0L
            val cs = time.substring(dotIdx + 1).toLongOrNull() ?: 0L
            val hms = time.substring(0, dotIdx).split(":")
            val h = hms.getOrNull(0)?.toLongOrNull() ?: 0L
            val m = hms.getOrNull(1)?.toLongOrNull() ?: 0L
            val s = hms.getOrNull(2)?.toLongOrNull() ?: 0L
            h * 3_600_000L + m * 60_000L + s * 1_000L + cs * 10L
        } catch (e: Exception) { 0L }
    }

    /**
     * Parse MKV ASS timestamp "H:MM:SS:CC" (all colon-separated) → milliseconds.
     */
    private fun parseMkvAssTimeMs(time: String): Long {
        val parts = time.split(":")
        if (parts.size != 4) return 0L
        val h  = parts[0].toLongOrNull() ?: 0L
        val m  = parts[1].toLongOrNull() ?: 0L
        val s  = parts[2].toLongOrNull() ?: 0L
        val cs = parts[3].toLongOrNull() ?: 0L
        return h * 3_600_000L + m * 60_000L + s * 1_000L + cs * 10L
    }

    /**
     * Clear the overlay and stop rendering ASS (e.g. when switching to non-ASS track).
     */
    fun clearOverlay() {
        activeTrackId = -1
        lastPositionUs = 0L
        lastPositionRealtimeUs = 0L
        currentTimeUs = 0L
        pendingFormatToSelect = null
        synchronized(nativeLock) {
            if (nativeHandle != 0L) {
                AssDirectBridge.nativeFlush(nativeHandle)
            }
        }
        stopRenderLoop()
        mainHandler.post { overlayView.clear() }
    }

    /**
     * Called by AssTimeRenderer when a seek occurs, ensuring rendering buffers
     * are cleared and libass is flushed immediately.
     */
    fun onSeek() {
        lastPositionUs = 0L
        lastPositionRealtimeUs = 0L
        synchronized(nativeLock) {
            if (nativeHandle != 0L) {
                AssDirectBridge.nativeFlush(nativeHandle)
                // External tracks carry the whole ASS document in their stored bytes
                // (no per-event chunks in trackEvents). Reloading the header after a
                // flush re-populates all cues; using the empty trackEvents map would
                // wipe the subtitle after every seek.
                val externalData = if (isExternalTrack(activeTrackId)) {
                    val idx = externalTrackIds.indexOf(activeTrackId)
                    if (idx >= 0) externalTrackData[idx] else null
                } else null
                if (externalData != null) {
                    AssDirectBridge.nativeLoadHeader(nativeHandle, externalData)
                } else {
                    val events = trackEvents[activeTrackId]
                    if (events != null) {
                        events.forEachIndexed { idx, (startMs, durationMs, bodyFields) ->
                            val chunkBytes = "$idx,$bodyFields".toByteArray(Charsets.UTF_8)
                            AssDirectBridge.nativeProcessChunk(nativeHandle, chunkBytes, startMs, durationMs)
                        }
                    }
                }
            }
        }
        mainHandler.post { overlayView.clear() }
    }

    /** Reset subtitle state when loading a new media item. */
    fun reset() {
        synchronized(nativeLock) {
            trackFormats.clear()
            trackHeaders.clear()
            trackEvents.clear()
            activeTrackId = -1
            pendingFonts.clear()
            lastPositionUs = 0L
            lastPositionRealtimeUs = 0L
            currentTimeUs = 0L
            pendingFormatToSelect = null
            hasLoadedFirstTime = false
            subtitleDelayMs = 0
            externalTrackNames.clear()
            externalTrackIds.clear()
            externalTrackData.clear()
            nextExternalTrackId = 100_000
            if (nativeHandle != 0L) {
                AssDirectBridge.nativeFlush(nativeHandle)
            }
        }
        stopRenderLoop()
        mainHandler.post { overlayView.clear() }
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == videoWidth && height == videoHeight && initialized) return
        videoWidth = width
        videoHeight = height
        synchronized(nativeLock) {
            if (nativeHandle != 0L) {
                AssDirectBridge.nativeSetFrameSize(nativeHandle, videoWidth, videoHeight)
                renderBitmap?.recycle()
                renderBitmap =
                    Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
            }
        }
    }

    /**
     * Set up fontconfig by extracting fonts.conf to app's files dir
     * and setting FONTCONFIG_PATH environment variable.
     */
    private fun setupFontconfig() {
        Log.d(TAG, "[INIT] setupFontconfig — filesDir=${context.filesDir}")
        try {
            val fontconfigDir = File(context.filesDir, "fontconfig")
            fontconfigDir.mkdirs()
            val cacheDir = File(context.cacheDir, "fontconfig")
            cacheDir.mkdirs()
            Log.d(TAG, "[INIT] fontconfig dir: ${fontconfigDir.absolutePath}  exists=${fontconfigDir.exists()}")
            Log.d(TAG, "[INIT] fontconfig cache: ${cacheDir.absolutePath}  exists=${cacheDir.exists()}")

            val confFile = File(fontconfigDir, "fonts.conf")
            confFile.writeText(
                """<?xml version="1.0"?>
 <!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
 <fontconfig>
     <dir>/system/fonts</dir>
     <cachedir>${cacheDir.absolutePath}</cachedir>
     <match target="pattern">
         <edit name="antialias" mode="assign"><bool>true</bool></edit>
         <edit name="hinting" mode="assign"><bool>true</bool></edit>
     </match>
 </fontconfig>""",
            )
            Log.d(TAG, "[INIT] fonts.conf written: ${confFile.absolutePath} (${confFile.length()}B)")

            Os.setenv("FONTCONFIG_PATH", fontconfigDir.absolutePath, true)
            Log.d(TAG, "[INIT] FONTCONFIG_PATH set to ${fontconfigDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "[INIT] setupFontconfig FAILED", e)
        }
    }

    fun release() {
        stopRenderLoop()
        synchronized(nativeLock) {
            if (nativeHandle != 0L) {
                AssDirectBridge.nativeDestroy(nativeHandle)
                nativeHandle = 0L
            }
            renderBitmap?.recycle()
            renderBitmap = null
            initialized = false
            hasLoadedFirstTime = false
            trackFormats.clear()
        }
        mainHandler.post { overlayView.clear() }
        Log.d(TAG, "Released")
    }
}
