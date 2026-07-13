package com.rhnxdev.hzplayer.data.datasource.subtitle.assrender

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import androidx.media3.common.Format
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

    /** The overlay view the UI should host above the video surface. */
    val view: SubtitleOverlayView get() = overlayView

    private var nativeHandle: Long = 0L
    private var renderBitmap: Bitmap? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val trackFormats = mutableMapOf<Int, Format>()
    private val trackHeaders = mutableMapOf<Int, ByteArray>()
    private val trackEvents =
        ConcurrentHashMap<Int, CopyOnWriteArrayList<Triple<Long, Long, ByteArray>>>()
    private var activeTrackId: Int = -1
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

    private var lastRenderedMs: Long = Long.MIN_VALUE
    private var choreographerCallback: android.view.Choreographer.FrameCallback? = null

    init {
        setupFontconfig()
    }

    companion object {
        private const val TAG = "assrender"
    }

    fun onTrackHeader(trackId: Int, headerData: ByteArray, format: Format) {
        Log.i(TAG, "[TRACK] onTrackHeader: trackId=$trackId mime=${format.sampleMimeType} " +
                "label=${format.label} lang=${format.language} " +
                "headerBytes=${headerData.size} initialized=$initialized")
        trackFormats[trackId] = format
        trackHeaders[trackId] = headerData

        if (!initialized) {
            Log.i(TAG, "[INIT] first ASS track — initializing native context ($videoWidth x $videoHeight)")
            nativeHandle = AssDirectBridge.nativeInit(videoWidth, videoHeight, 1.0f)
            if (nativeHandle == 0L) {
                Log.e(TAG, "[INIT] nativeInit returned 0 — FATAL")
                return
            }
            Log.i(TAG, "[INIT] nativeInit OK  handle=$nativeHandle")
            renderBitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
            initialized = true
            Log.i(TAG, "[INIT] pendingFonts in queue: ${pendingFonts.size}")
            flushPendingFonts()
            onAssTrackSelected?.invoke()
        }

        pendingFormatToSelect?.let { pending ->
            Log.i(TAG, "[TRACK] applying pending format selection")
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
        val chunkBody: String // ReadOrder,Layer,Style,Name,ML,MR,MV,Effect,Text

        if (isStandardAss) {
            // Standard ASS: Layer,Start,End,Style,Name,ML,MR,MV,Effect,Text  (10 fields)
            val f = content.split(",", limit = 10)
            if (f.size < 10) {
                Log.w(TAG, "[TRACK] standard ASS line has <10 fields, skipping")
                return
            }
            startMs    = parseStandardAssTimeMs(f[1].trim())
            durationMs = (parseStandardAssTimeMs(f[2].trim()) - startMs).coerceAtLeast(0L)
            // Synthesise MKV body: use 0 as ReadOrder (libass only uses it for ordering)
            chunkBody = "0,${f[0].trim()},${f[3].trim()},${f[4].trim()}," +
                        "${f[5].trim()},${f[6].trim()},${f[7].trim()},${f[8].trim()},${f[9]}"
            Log.d(TAG, "[TRACK] stdASS → body='${chunkBody.take(80)}'  start=${startMs}ms dur=${durationMs}ms")
        } else {
            // MKV block: Start(0),Duration,ReadOrder,Layer,Style,Name,ML,MR,MV,Effect,Text (11 fields)
            val f = content.split(",", limit = 11)
            if (f.size < 11) {
                Log.w(TAG, "[TRACK] MKV line has <11 fields, skipping")
                return
            }
            durationMs = parseMkvAssTimeMs(f[1].trim())
            startMs    = timeUs / 1000
            // Body is fields[2..10]: ReadOrder,Layer,Style,Name,ML,MR,MV,Effect,Text
            chunkBody = "${f[2].trim()},${f[3].trim()},${f[4].trim()},${f[5].trim()}," +
                        "${f[6].trim()},${f[7].trim()},${f[8].trim()},${f[9].trim()},${f[10]}"
            Log.d(TAG, "[TRACK] MKV → body='${chunkBody.take(80)}'  start=${startMs}ms dur=${durationMs}ms")
        }

        val chunkBytes = chunkBody.toByteArray(Charsets.UTF_8)

        trackEvents.getOrPut(trackId) { CopyOnWriteArrayList() }
            .add(Triple(startMs, durationMs, chunkBytes))

        if (trackId == activeTrackId) {
            AssDirectBridge.nativeProcessChunk(nativeHandle, chunkBytes, startMs, durationMs)
        }
    }

    fun onFontAttachment(name: String, data: ByteArray) {
        // Peek at magic bytes to confirm it's a real font binary
        val magic = if (data.size >= 4)
            data.take(4).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        else "(too short)"
        Log.i(TAG, "[FONT] onFontAttachment: name='$name' size=${data.size}B " +
                "magic=[$magic]  initialized=$initialized  handle=$nativeHandle")

        if (!initialized || nativeHandle == 0L) {
            pendingFonts.add(Pair(name, data))
            Log.i(TAG, "[FONT] buffered (not yet initialized) — pendingFonts.size=${pendingFonts.size}")
            return
        }
        Log.i(TAG, "[FONT] calling nativeAddFont immediately")
        AssDirectBridge.nativeAddFont(nativeHandle, name, data)
        needsFontReload = true
        Log.i(TAG, "[FONT] nativeAddFont done, needsFontReload=true")
    }

    /** Load an external `.ass`/`.ssa` file into libass (bypasses ExoPlayer parsing). */
    fun loadExternalTrack(data: ByteArray) {
        if (nativeHandle == 0L) {
            nativeHandle = AssDirectBridge.nativeInit(videoWidth, videoHeight, 1.0f)
            if (nativeHandle == 0L) {
                Log.e(TAG, "Failed to init native context for external ASS")
                return
            }
            renderBitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
            initialized = true
            flushPendingFonts()
            onAssTrackSelected?.invoke()
        }
        activeTrackId = -1
        pendingFonts.clear()
        AssDirectBridge.nativeFlush(nativeHandle)
        val ok = AssDirectBridge.nativeLoadHeader(nativeHandle, data)
        if (ok != 0) {
            Log.e(TAG, "Failed to load external ASS (${data.size} bytes)")
            return
        }
        overlayView.clear()
        Log.i(TAG, "Loaded external ASS: ${data.size} bytes")
        startRenderLoop()
    }

    private fun flushPendingFonts() {
        Log.i(TAG, "[FONT] flushPendingFonts: count=${pendingFonts.size}")
        if (pendingFonts.isEmpty()) {
            Log.i(TAG, "[FONT] no pending fonts to flush")
            return
        }
        pendingFonts.forEachIndexed { idx, (name, data) ->
            Log.i(TAG, "[FONT]   flushing[$idx]: '$name' ${data.size}B")
            AssDirectBridge.nativeAddFont(nativeHandle, name, data)
        }
        Log.i(TAG, "[FONT] flushed ${pendingFonts.size} fonts → scheduling reload")
        pendingFonts.clear()
        needsFontReload = true
    }

    fun selectTrack(trackId: Int) {
        Log.i(TAG, "[TRACK] selectTrack: id=$trackId initialized=$initialized handle=$nativeHandle")
        if (!initialized || nativeHandle == 0L) {
            Log.w(TAG, "[TRACK] selectTrack skipped — not initialized")
            return
        }
        val header = trackHeaders[trackId]
        if (header == null) {
            Log.e(TAG, "[TRACK] selectTrack: no header for trackId=$trackId  knownTracks=${trackHeaders.keys}")
            return
        }
        Log.i(TAG, "[TRACK] selecting trackId=$trackId  headerBytes=${header.size}  " +
                "pendingFonts=${pendingFonts.size}  needsFontReload=$needsFontReload")

        mainHandler.post {
            activeTrackId = trackId
            overlayView.clear()
            pendingFormatToSelect = null

            AssDirectBridge.nativeFlush(nativeHandle)
            Log.i(TAG, "[TRACK] flushed old events")

            AssDirectBridge.nativeLoadHeader(nativeHandle, header)
            Log.i(TAG, "[TRACK] header loaded")

            // Font reload must happen AFTER header is loaded
            if (needsFontReload) {
                Log.i(TAG, "[FONT] selectTrack triggering font reload now")
                needsFontReload = false
                AssDirectBridge.nativeReloadFonts(nativeHandle)
                Log.i(TAG, "[FONT] font reload complete")
            } else {
                Log.i(TAG, "[FONT] no font reload needed at selectTrack")
            }

            val events = trackEvents[trackId]
            if (events != null) {
                Log.i(TAG, "[TRACK] replaying ${events.size} buffered events for track $trackId")
                for ((startMs, durationMs, chunkBytes) in events) {
                    AssDirectBridge.nativeProcessChunk(nativeHandle, chunkBytes, startMs, durationMs)
                }
                Log.i(TAG, "[TRACK] replay done")
            } else {
                Log.i(TAG, "[TRACK] no buffered events for track $trackId")
            }

            logDiagnostics()
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

        // 4. Fallback: match by index/id if the format has a numeric ID
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

    /* throttle render-frame log to once per second */
    private var renderLogCounter = 0L

    private fun renderFrame() {
        if (!initialized || nativeHandle == 0L) return
        val bitmap = renderBitmap ?: return

        if (needsFontReload) {
            Log.i(TAG, "[FONT] renderFrame: triggering deferred font reload")
            needsFontReload = false
            AssDirectBridge.nativeReloadFonts(nativeHandle)
            Log.i(TAG, "[FONT] font reload complete")
        }

        val isPlaying       = player?.isPlaying == true
        val speed           = player?.playbackParameters?.speed ?: 1.0f
        val mediaDurationMs = player?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE

        val positionMs: Long
        val posSource: String

        if (isPlaying && lastPositionRealtimeUs != 0L && lastPositionUs > 0L) {
            val currentRealtimeUs = android.os.SystemClock.elapsedRealtime() * 1000L
            val elapsedUs         = currentRealtimeUs - lastPositionRealtimeUs
            val interpolated      = (lastPositionUs + (elapsedUs * speed).toLong()) / 1000L
            // Safe clamp: always [0, mediaDuration], no invalid range possible
            positionMs = interpolated.coerceAtLeast(0L)
                .let { v -> if (mediaDurationMs < Long.MAX_VALUE) v.coerceAtMost(mediaDurationMs) else v }
            posSource  = "interpolated"
        } else {
            positionMs = player?.currentPosition ?: (lastPositionUs / 1000L).coerceAtLeast(0L)
            posSource  = if (player != null) "player.currentPosition" else "lastPositionUs"
        }

        renderLogCounter++
        if (renderLogCounter == 1L || renderLogCounter % 60 == 0L) {
            Log.d(TAG, "[RENDER] frame #$renderLogCounter  positionMs=$positionMs " +
                    "src=$posSource  isPlaying=$isPlaying  speed=$speed  " +
                    "lastPositionUs=$lastPositionUs  lastRealtimeUs=$lastPositionRealtimeUs")
        }

        val hasContent = AssDirectBridge.nativeRender(nativeHandle, positionMs, bitmap)

        if (hasContent) {
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
        Log.i(TAG, "━━━━━━━━━━━ ASS DIAGNOSTIC DUMP ━━━━━━━━━━━")
        Log.i(TAG, "[DIAG] initialized=$initialized  handle=$nativeHandle")
        Log.i(TAG, "[DIAG] activeTrackId=$activeTrackId  needsFontReload=$needsFontReload")
        Log.i(TAG, "[DIAG] videoSize=${videoWidth}x${videoHeight}")
        Log.i(TAG, "[DIAG] pendingFonts.size=${pendingFonts.size}")
        Log.i(TAG, "[DIAG] trackFormats.keys=${trackFormats.keys.toList()}")
        Log.i(TAG, "[DIAG] trackHeaders.keys=${trackHeaders.keys.toList()}")
        trackHeaders.forEach { (id, hdr) ->
            Log.i(TAG, "[DIAG] header[$id]: ${hdr.size}B  preview=${String(hdr, 0, minOf(120, hdr.size), Charsets.UTF_8).replace('\n','|')}")
        }
        trackEvents.forEach { (id, evts) ->
            Log.i(TAG, "[DIAG] events[$id]: ${evts.size} items")
        }
        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
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
     * Convert MKV ASS dialogue format to standard ASS format.
     * MKV:      "Dialogue: 0:00:00:00,Duration,ReadOrder,Layer,Style,Name,ML,MR,MV,Effect,Text"
     *           Start is always 0, Duration is relative. Real start comes from timeUs.
     * Standard: "Dialogue: Layer,Start,End,Style,Name,ML,MR,MV,Effect,Text"
     */
    private fun convertMkvDialogue(line: String, timeUs: Long): String? {
        if (!line.startsWith("Dialogue:")) return null

        val content = line.removePrefix("Dialogue:").trimStart()
        val parts = content.split(",", limit = 11)
        if (parts.size < 11) return null

        // MKV layout: [0]=start(0), [1]=duration, [2]=readOrder, [3]=layer, [4]=style, [5]=name,
        //             [6]=marginL, [7]=marginR, [8]=marginV, [9]=effect, [10]=text
        val durationStr = parts[1].trim()
        val layer = parts[3].trim()
        val style = parts[4].trim()
        val name = parts[5].trim()
        val marginL = parts[6].trim()
        val marginR = parts[7].trim()
        val marginV = parts[8].trim()
        val effect = parts[9].trim()
        val text = parts[10]

        val durationMs = parseMkvAssTimeMs(durationStr)
        val startMs = timeUs / 1000
        val endMs = startMs + durationMs

        val startStr = formatAssTime(startMs)
        val endStr = formatAssTime(endMs)

        return "Dialogue: $layer,$startStr,$endStr,$style,$name,$marginL,$marginR,$marginV,$effect,$text"
    }

    /** Format milliseconds to standard ASS time "H:MM:SS.CC" */
    private fun formatAssTime(ms: Long): String {
        val totalCs = ms / 10
        val cs = totalCs % 100
        val totalS = totalCs / 100
        val s = totalS % 60
        val totalM = totalS / 60
        val m = totalM % 60
        val h = totalM / 60
        return "%d:%02d:%02d.%02d".format(h, m, s, cs)
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
        if (nativeHandle != 0L) {
            AssDirectBridge.nativeFlush(nativeHandle)
        }
        stopRenderLoop()
        mainHandler.post { overlayView.clear() }
    }

    /** Reset subtitle state when loading a new media item. */
    fun reset() {
        trackFormats.clear()
        trackHeaders.clear()
        trackEvents.clear()
        activeTrackId = -1
        pendingFonts.clear()
        lastPositionUs = 0L
        lastPositionRealtimeUs = 0L
        currentTimeUs = 0L
        pendingFormatToSelect = null
        if (nativeHandle != 0L) {
            AssDirectBridge.nativeFlush(nativeHandle)
        }
        stopRenderLoop()
        mainHandler.post { overlayView.clear() }
    }

    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == videoWidth && height == videoHeight && initialized) return
        videoWidth = width
        videoHeight = height
        if (nativeHandle != 0L) {
            AssDirectBridge.nativeSetFrameSize(nativeHandle, videoWidth, videoHeight)
            renderBitmap?.recycle()
            renderBitmap =
                Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
        }
    }

    /**
     * Set up fontconfig by extracting fonts.conf to app's files dir
     * and setting FONTCONFIG_PATH environment variable.
     */
    private fun setupFontconfig() {
        Log.i(TAG, "[INIT] setupFontconfig — filesDir=${context.filesDir}")
        try {
            val fontconfigDir = File(context.filesDir, "fontconfig")
            fontconfigDir.mkdirs()
            val cacheDir = File(context.cacheDir, "fontconfig")
            cacheDir.mkdirs()
            Log.i(TAG, "[INIT] fontconfig dir: ${fontconfigDir.absolutePath}  exists=${fontconfigDir.exists()}")
            Log.i(TAG, "[INIT] fontconfig cache: ${cacheDir.absolutePath}  exists=${cacheDir.exists()}")

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
            Log.i(TAG, "[INIT] fonts.conf written: ${confFile.absolutePath} (${confFile.length()}B)")

            Os.setenv("FONTCONFIG_PATH", fontconfigDir.absolutePath, true)
            Log.i(TAG, "[INIT] FONTCONFIG_PATH set to ${fontconfigDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "[INIT] setupFontconfig FAILED", e)
        }
    }

    fun release() {
        stopRenderLoop()
        if (nativeHandle != 0L) {
            AssDirectBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        renderBitmap?.recycle()
        renderBitmap = null
        initialized = false
        trackFormats.clear()
        mainHandler.post { overlayView.clear() }
        Log.d(TAG, "Released")
    }
}
