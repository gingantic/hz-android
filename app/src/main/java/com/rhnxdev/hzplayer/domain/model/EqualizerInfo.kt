package com.rhnxdev.hzplayer.domain.model

/** One equalizer frequency band. Levels are in millibels (100 mB = 1 dB). */
data class EqualizerBand(
    val index: Int,
    val centerFreqHz: Int,
    val levelMb: Int,
)

/**
 * Live equalizer snapshot exposed to the UI. [available] is false when the
 * engine has no equalizer or no audio session is active yet; band count,
 * level range and preset list are device-dependent and queried at runtime.
 */
data class EqualizerInfo(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val bands: List<EqualizerBand> = emptyList(),
    val minLevelMb: Int = -1500,
    val maxLevelMb: Int = 1500,
    val presets: List<String> = emptyList(),
    /** Index into [presets], or -1 for user-customized band levels. */
    val currentPreset: Int = -1,
    val bassBoostAvailable: Boolean = false,
    /** Bass boost strength, 0..1000. */
    val bassBoostStrength: Int = 0,
    val loudnessAvailable: Boolean = false,
    /** Loudness enhancer target gain in millibels, 0..1000. */
    val loudnessGainMb: Int = 0,
)

/** Persisted subset of the equalizer configuration (survives app restarts). */
data class EqualizerSettings(
    val enabled: Boolean = false,
    /** Device preset index, or -1 when [bandLevelsMb] holds custom levels. */
    val preset: Int = -1,
    val bandLevelsMb: List<Int> = emptyList(),
    val bassBoostStrength: Int = 0,
    val loudnessGainMb: Int = 0,
)
