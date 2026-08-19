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
    @Volatile
    private var activeTrackId: Int = -1
    /** The currently active track ID — exposed so FfmpegNativeEngine can map it to a list index. */
    val currentActiveTrackId: Int get() = activeTrackId

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

    @Volatile
    private var isPlayingState: Boolean = false

    fun setIsPlaying(playing: Boolean) {
        isPlayingState = playing
    }

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
    var playbackSpeed: Float = 1.0f

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

    fun onTrackHeader(trackId: Int, headerData: ByteArray, title: String = "") {
        val format = Format.Builder()
            .setId(trackId.toString())
            .setSampleMimeType(MimeTypes.TEXT_SSA)
            .setLabel(title.ifEmpty { "Subtitle $trackId" })
            .build()
        onTrackHeader(trackId, headerData, format)
    }

    fun onTrackHeader(trackId: Int, headerData: ByteArray, format: Format) {
        val safeHeader = if (headerData.isNotEmpty() && (
                String(headerData, 0, minOf(50, headerData.size), Charsets.UTF_8).contains("[Script Info]") ||
                String(headerData, 0, minOf(50, headerData.size), Charsets.UTF_8).contains("ScriptType:")
            )) {
            headerData
        } else {
            SubtitleConverters.buildMinimalAssHeader(videoWidth, videoHeight)
        }

        trackFormats[trackId] = format
        trackHeaders[trackId] = safeHeader

        var shouldInvokeCallback = false
        synchronized(nativeLock) {
            if (!initialized) {
                val bw = if (videoWidth > 0) videoWidth else 1920
                val bh = if (videoHeight > 0) videoHeight else 1080
                Log.i(TAG, "[INIT] first ASS track — initializing native context ($bw x $bh)")
                nativeHandle = AssDirectBridge.nativeInit(bw, bh, 1.0f)
                if (nativeHandle != 0L) {
                    renderBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    initialized = true
                    flushPendingFonts()
                    shouldInvokeCallback = true
                } else {
                    Log.e(TAG, "[INIT] nativeInit returned 0 — FATAL")
                }
            }
            if (activeTrackId == -1) {
                activeTrackId = trackId
            }
        }

        mainHandler.post {
            if (shouldInvokeCallback) {
                onAssTrackSelected?.invoke()
            }

            if (activeTrackId == trackId) {
                selectTrack(trackId)
            } else if (pendingFormatToSelect != null) {
                selectTrackByFormat(pendingFormatToSelect!!)
            }
        }
    }

    /**
     * Process one subtitle sample.
     *
     * [data] arrives either as:
     * - A full "Dialogue:" line (from ExoPlayer / MatroskaExtractor)
     * - A raw MKV block from FFmpeg demuxer: ReadOrder,Layer,Style,Name,ML,MR,MV,Effect,Text
     *
     * [durationUs] is the packet duration in microseconds (from FFmpeg pkt->duration).
     */
    fun onSubtitleSample(trackId: Int, timeUs: Long, durationUs: Long = 0L, data: ByteArray) {
        val line = String(data, Charsets.UTF_8).trim()
        if (line.isEmpty()) return

        val startMs: Long
        var durationMs: Long = 0L
        val bodyFields: String // Layer,Style,Name,ML,MR,MV,Effect,Text

        if (line.startsWith("Dialogue:")) {
            val content = line.removePrefix("Dialogue:").trimStart()
            val isStandardAss = isStandardAssTimestamp(
                content.split(",", limit = 3).getOrNull(1)?.trim() ?: ""
            )

            if (isStandardAss) {
                // Standard ASS: Layer,Start,End,Style,Name,ML,MR,MV,Effect,Text (10 fields)
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
        } else {
            // Raw MKV block from FFmpeg demuxer: ReadOrder,Layer,Style,Name,ML,MR,MV,Effect,Text (9 fields)
            // Duration is carried separately in the durationUs parameter (from pkt->duration).
            startMs    = timeUs / 1000
            durationMs = if (durationUs > 0L) durationUs / 1000L else 3000L // 3 s fallback
            val f = line.split(",", limit = 9)
            if (f.size >= 9) {
                bodyFields = "${f[1].trim()},${f[2].trim()},${f[3].trim()}," +
                            "${f[4].trim()},${f[5].trim()},${f[6].trim()},${f[7].trim()},${f[8]}"
            } else {
                // Plain-text SRT / WebVTT cue from FFmpeg demuxer
                val sanitizedText = line.replace("\r\n", "\\N").replace("\n", "\\N")
                bodyFields = "0,Default,,0,0,0,,$sanitizedText"
            }
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

        // ── Diagnostic: log first 10 events received ─────────────────────────
        val totalEvents = events.size
        if (totalEvents <= 10) {
            Log.i(TAG, "[SUB-DBG] event #$readOrder trackId=$trackId activeTrackId=$activeTrackId " +
                "startMs=$startMs durMs=$durationMs raw='${line.take(80)}'")
        }

        val chunkBytes = "$readOrder,$bodyFields".toByteArray(Charsets.UTF_8)

        if (trackId == activeTrackId) {
            synchronized(nativeLock) {
                if (nativeHandle != 0L) {
                    if (totalEvents <= 10) Log.i(TAG, "[SUB-DBG] → nativeProcessChunk($readOrder, startMs=$startMs, durMs=$durationMs) handle=$nativeHandle")
                    AssDirectBridge.nativeProcessChunk(nativeHandle, chunkBytes, startMs, durationMs)
                } else {
                    if (totalEvents <= 10) Log.w(TAG, "[SUB-DBG] → SKIPPED nativeProcessChunk: nativeHandle=0")
                }
            }
        } else {
            if (totalEvents <= 10) Log.w(TAG, "[SUB-DBG] → SKIPPED: trackId($trackId) != activeTrackId($activeTrackId)")
        }
    }

    fun onFontAttachment(name: String, data: ByteArray) {
        val magic = if (data.size >= 4)
            data.take(4).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        else "(too short)"

        synchronized(nativeLock) {
            if (!initialized || nativeHandle == 0L) {
                pendingFonts.add(Pair(name, data))
                return
            }
            AssDirectBridge.nativeAddFont(nativeHandle, name, data)
            needsFontReload = true
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
            // ponytail: deduplicate by data content — same bytes = same track already loaded.
            // Catches both neighbor auto-discovery and manual addExternalSubtitle calls.
            if (externalTrackData.any { it.contentEquals(data) }) {
                Log.i(TAG, "loadExternalTrack: duplicate data, skipping '$displayName' (${data.size}B)")
                return
            }
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

    /**
     * Combined list of ALL subtitle track display names: embedded tracks first (from
     * [trackFormats], in insertion order), then external tracks. Aligned 1:1 with [getAllTrackIds].
     * Use this in FfmpegNativeEngine to expose both embedded and external tracks to the UI.
     */
    fun getAllTrackNames(): List<String> {
        val names = mutableListOf<String>()
        trackFormats.forEach { (_, fmt) -> names.add(fmt.label ?: "Subtitle") }
        names.addAll(externalTrackNames)
        return names
    }

    /**
     * Combined list of ALL subtitle track IDs: embedded tracks first, then external.
     * Aligned 1:1 with [getAllTrackNames].
     */
    fun getAllTrackIds(): List<Int> {
        val ids = mutableListOf<Int>()
        trackFormats.keys.forEach { ids.add(it) }
        ids.addAll(externalTrackIds)
        return ids
    }

    /**
     * Index of the currently active track within [getAllTrackIds], or -1 if none is active.
     */
    fun getActiveTrackIndex(): Int = getAllTrackIds().indexOf(activeTrackId)

    private fun flushPendingFonts() {
        
        if (pendingFonts.isEmpty()) {
            
            return
        }
        synchronized(nativeLock) {
            if (nativeHandle != 0L) {
                pendingFonts.forEachIndexed { idx, (name, data) ->
                    
                    AssDirectBridge.nativeAddFont(nativeHandle, name, data)
                }
                
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
                if (nativeHandle == 0L) {
                    Log.e(TAG, "[TRACK] selectTrack($trackId): nativeHandle=0 — cannot load!")
                    return@synchronized
                }
                activeTrackId = trackId
                overlayView.clear()
                pendingFormatToSelect = null
                renderDiagLogged = false  // Reset so first-render is always logged

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
                val eventCount = events?.size ?: 0
                Log.i(TAG, "[TRACK] selectTrack($trackId): replaying $eventCount stored events, handle=$nativeHandle")
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
        
    }

    private fun startRenderLoop() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { startRenderLoop() }
            return
        }
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
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { stopRenderLoop() }
            return
        }
        choreographerCallback?.let {
            android.view.Choreographer.getInstance().removeFrameCallback(it)
        }
        choreographerCallback = null
    }

    private var renderDiagLogged = false
    private var renderDiagFrameCount: Long = 0L

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
        val isExoActive = p != null && p.playbackState != androidx.media3.common.Player.STATE_IDLE

        if (isExoActive) {
            if (p!!.playbackState == androidx.media3.common.Player.STATE_READY) {
                hasLoadedFirstTime = true
            }
        } else {
            // In FFmpeg-native mode (or when ExoPlayer is not active), readiness is established
            // as soon as native libass context is alive and a track is selected.
            if (initialized && activeTrackId != -1) {
                hasLoadedFirstTime = true
            }
        }

        if (!hasLoadedFirstTime) {
            if (!renderDiagLogged) {
                Log.w(TAG, "[RENDER] not loaded-first-time — abort (isExoActive=$isExoActive activeTrackId=$activeTrackId)")
                renderDiagLogged = true
            }
            overlayView.clear()
            return
        }

        val isPlaying       = if (isExoActive) p!!.isPlaying else isPlayingState
        val speed           = if (isExoActive) (p!!.playbackParameters.speed) else playbackSpeed
        val mediaDurationMs = if (isExoActive) (p!!.duration.takeIf { it > 0 } ?: Long.MAX_VALUE) else Long.MAX_VALUE

        val positionMs: Long

        if (isPlaying && lastPositionRealtimeUs != 0L && lastPositionUs > 0L) {
            val currentRealtimeUs = android.os.SystemClock.elapsedRealtime() * 1000L
            val elapsedUs         = (currentRealtimeUs - lastPositionRealtimeUs).coerceAtLeast(0L)
            val interpolated      = (lastPositionUs + (elapsedUs * speed).toLong()) / 1000L
            positionMs = interpolated.coerceAtLeast(0L)
                .let { v -> if (mediaDurationMs < Long.MAX_VALUE) v.coerceAtMost(mediaDurationMs) else v }
        } else {
            positionMs = if (isExoActive) p!!.currentPosition else (lastPositionUs / 1000L).coerceAtLeast(0L)
        }

        var hasContent = false
        // Apply the timing offset: libass renders the cue anchored at (pos − delay).
        // Positive delayMs ⇒ subtitles lag the audio; negative ⇒ lead. Coerce so we
        // never pass a negative position to nativeRender (cue-times are ≥ 0).
        val renderPosMs = (positionMs - subtitleDelayMs).coerceAtLeast(0L)

        // ── Diagnostic: log every ~300 frames ────────────────────────────────
        renderDiagFrameCount++
        val shouldLog = renderDiagFrameCount % 300 == 0L
        
        synchronized(nativeLock) {
            if (nativeHandle != 0L) {
                if (needsFontReload) {
                    needsFontReload = false
                    AssDirectBridge.nativeReloadFonts(nativeHandle)
                }
                hasContent = AssDirectBridge.nativeRender(nativeHandle, renderPosMs, bitmap)
            }
        }

        if (shouldLog) {
            Log.i(TAG, "[RENDER-DBG] frame=$renderDiagFrameCount posMs=$positionMs renderPosMs=$renderPosMs " +
                "activeTrack=$activeTrackId handle=$nativeHandle hasContent=$hasContent")
        }

        if (hasContent) {
            if (!renderDiagLogged) {
                Log.i(TAG, "[RENDER] FIRST content rendered at posMs=$positionMs (was waiting on READY)")
                renderDiagLogged = true
            }
            overlayView.updateBitmap(bitmap)
        } else {
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
            isPlayingState = false
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
            

            Os.setenv("FONTCONFIG_PATH", fontconfigDir.absolutePath, true)
            
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
        
    }
}
