package com.rhnxdev.hzplayer.data.datasource.player

import android.media.audiofx.BassBoost
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import com.rhnxdev.hzplayer.domain.model.EqualizerBand
import com.rhnxdev.hzplayer.domain.model.EqualizerInfo
import com.rhnxdev.hzplayer.domain.model.EqualizerSettings
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Audio effects for the ExoPlayer engine.
 *
 * Band EQ: the in-sink [TenBandEqualizerProcessor] — a fixed 10-band DSP
 * running inside the player's audio pipeline, so it is device-independent
 * and configurable even before any playback session exists. Presets are the
 * app's own curves ([PRESETS]).
 *
 * Bass boost / loudness: platform session effects, attached to the audio
 * session ID emitted by [MediaPlayerHolder] (which survives decoder rebuilds
 * — the new player emits a fresh session through the same StateFlow).
 *
 * Settings persist via DataStore and are loaded once at startup, so a saved
 * EQ applies from the first played media.
 */
@Singleton
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EqualizerController @Inject constructor(
    playerHolder: MediaPlayerHolder,
    private val eqProcessor: TenBandEqualizerProcessor,
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    // Process-lifetime scope, same as the other playback singletons. Main
    // dispatcher: audiofx objects are not thread-safe, so all access is
    // serialized on the main thread alongside the session collector.
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var bassBoost: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null
    private var attachedSessionId = 0

    /** Desired configuration; band EQ applies immediately, session effects on attach. */
    private var settings = EqualizerSettings()
    private var settingsLoaded = false

    private val _state = MutableStateFlow(EqualizerInfo())
    val state: StateFlow<EqualizerInfo> = _state.asStateFlow()

    init {
        scope.launch {
            // Load saved settings before anything plays so the first media
            // already goes through the user's EQ curve.
            settings = runCatching { userPreferencesRepository.equalizerSettings.first() }
                .getOrDefault(EqualizerSettings())
            settingsLoaded = true
            applySettings()
            publishState()
            playerHolder.audioSessionId.collect { sessionId -> attach(sessionId) }
        }
    }

    // ── Public mutators (called from the UI via the engine) ────────────

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    fun setBandLevel(band: Int, levelMb: Int) {
        // Editing a band leaves preset mode; the custom curve starts from the
        // current live levels so the other bands keep the preset's shape.
        val levels = eqProcessor.getBandGains().toMutableList()
        if (band !in levels.indices) return
        levels[band] = levelMb
        update { it.copy(preset = -1, bandLevelsMb = levels) }
    }

    fun applyPreset(preset: Int) = update {
        // -1 = switch to custom mode seeded from the current live curve, so
        // leaving a preset doesn't audibly flatten the bands.
        if (preset < 0) it.copy(preset = -1, bandLevelsMb = eqProcessor.getBandGains().toList())
        else it.copy(preset = preset, bandLevelsMb = emptyList())
    }

    fun resetBands() = update {
        it.copy(preset = -1, bandLevelsMb = List(TenBandEqualizerProcessor.BAND_COUNT) { 0 })
    }

    fun setBassBoostStrength(strength: Int) =
        update { it.copy(bassBoostStrength = strength.coerceIn(0, 1000)) }

    fun setLoudnessGain(gainMb: Int) =
        update { it.copy(loudnessGainMb = gainMb.coerceIn(0, MAX_LOUDNESS_GAIN_MB)) }

    // ── Session lifecycle (bass boost / loudness only) ─────────────────

    private fun attach(sessionId: Int) {
        if (sessionId == attachedSessionId) return
        detach()
        attachedSessionId = sessionId
        if (sessionId == 0) {
            // Session gone (stop/release) — the band EQ keeps working, only
            // the session effects drop until the next session.
            publishState()
            return
        }
        // audiofx construction throws on devices without the effect (and on
        // some emulators); each effect degrades independently.
        bassBoost = runCatching { BassBoost(0, sessionId) }
            .onFailure { Log.w(TAG, "BassBoost unavailable: ${it.message}") }
            .getOrNull()
        loudness = runCatching { LoudnessEnhancer(sessionId) }
            .onFailure { Log.w(TAG, "LoudnessEnhancer unavailable: ${it.message}") }
            .getOrNull()
        applySettings()
        publishState()
        Log.d(TAG, "Session effects attached to session $sessionId")
    }

    private fun detach() {
        runCatching { bassBoost?.release() }
        runCatching { loudness?.release() }
        bassBoost = null
        loudness = null
        attachedSessionId = 0
    }

    // ── Apply / publish ─────────────────────────────────────────────────

    private inline fun update(transform: (EqualizerSettings) -> EqualizerSettings) {
        settings = transform(settings)
        applySettings()
        publishState()
        persist()
    }

    private fun applySettings() {
        val s = settings
        val gains = if (s.preset in PRESETS.indices) {
            PRESETS[s.preset].second
        } else {
            IntArray(TenBandEqualizerProcessor.BAND_COUNT) { s.bandLevelsMb.getOrNull(it) ?: 0 }
        }
        eqProcessor.setBandGains(gains)
        eqProcessor.setEnabled(s.enabled)
        bassBoost?.let { bb ->
            runCatching {
                bb.setStrength(s.bassBoostStrength.toShort())
                bb.enabled = s.enabled && s.bassBoostStrength > 0
            }.onFailure { Log.w(TAG, "BassBoost apply failed: ${it.message}") }
        }
        loudness?.let { le ->
            runCatching {
                le.setTargetGain(s.loudnessGainMb)
                le.enabled = s.enabled && s.loudnessGainMb > 0
            }.onFailure { Log.w(TAG, "LoudnessEnhancer apply failed: ${it.message}") }
        }
    }

    private fun publishState() {
        val gains = eqProcessor.getBandGains()
        _state.value = EqualizerInfo(
            available = true,
            enabled = settings.enabled,
            bands = gains.mapIndexed { index, levelMb ->
                EqualizerBand(
                    index = index,
                    centerFreqHz = TenBandEqualizerProcessor.CENTER_FREQUENCIES_HZ[index],
                    levelMb = levelMb,
                )
            },
            minLevelMb = TenBandEqualizerProcessor.MIN_GAIN_MB,
            maxLevelMb = TenBandEqualizerProcessor.MAX_GAIN_MB,
            presets = PRESETS.map { it.first },
            currentPreset = settings.preset,
            bassBoostAvailable = bassBoost != null,
            bassBoostStrength = settings.bassBoostStrength,
            loudnessAvailable = loudness != null,
            loudnessGainMb = settings.loudnessGainMb,
        )
    }

    private fun persist() {
        if (!settingsLoaded) return
        val snapshot = settings
        scope.launch { userPreferencesRepository.setEqualizerSettings(snapshot) }
    }

    companion object {
        private const val TAG = "EqualizerController"

        /** Cap the loudness enhancer at +10 dB to keep it clip-safe. */
        const val MAX_LOUDNESS_GAIN_MB = 1000

        /**
         * Built-in preset curves in millibels for the 10 bands
         * (31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz).
         */
        private val PRESETS: List<Pair<String, IntArray>> = listOf(
            "Flat" to intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            "Bass Boost" to intArrayOf(600, 500, 400, 200, 0, 0, 0, 0, 0, 0),
            "Treble Boost" to intArrayOf(0, 0, 0, 0, 0, 0, 200, 400, 500, 600),
            "Rock" to intArrayOf(500, 400, 300, 100, -100, -100, 0, 200, 300, 400),
            "Pop" to intArrayOf(-100, 100, 300, 400, 300, 0, -100, -100, 100, 200),
            "Jazz" to intArrayOf(300, 200, 100, 200, -100, -100, 0, 100, 200, 300),
            "Classical" to intArrayOf(300, 200, 100, 0, 0, 0, -100, 100, 200, 300),
            "Dance" to intArrayOf(500, 400, 200, 0, 0, -200, -200, 0, 300, 400),
            "Hip-Hop" to intArrayOf(500, 400, 100, 200, -100, -100, 100, -100, 200, 300),
            "Vocal" to intArrayOf(-200, -100, 0, 200, 400, 400, 300, 100, 0, -100),
        )
    }
}
