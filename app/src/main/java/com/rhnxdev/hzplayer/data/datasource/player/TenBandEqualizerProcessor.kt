package com.rhnxdev.hzplayer.data.datasource.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Fixed 10-band graphic equalizer as a Media3 [AudioProcessor]: one RBJ
 * peaking biquad per band at the ISO octave centers (31 Hz .. 16 kHz),
 * cascaded per channel over 16-bit PCM.
 *
 * Unlike the platform `android.media.audiofx.Equalizer`, this runs inside the
 * player's own audio sink, so the band count and response are identical on
 * every device. It does NOT apply to passthrough/bitstream audio — the same
 * limitation session effects have.
 *
 * Threading: gains are set from the main thread; [queueInput] runs on the
 * playback thread. Coefficients are rebuilt on set and swapped as one
 * immutable snapshot via [coefficients], so the audio thread never sees a
 * half-updated filter. Filter memory keeps across a coefficient swap (the
 * one-buffer transient is inaudible) and resets on flush/seek.
 *
 * The processor stays active even when disabled (one pass-through buffer
 * copy) so toggling the EQ mid-playback takes effect immediately instead of
 * waiting for the next sink reconfiguration.
 */
@UnstableApi
@Singleton
class TenBandEqualizerProcessor @Inject constructor() : BaseAudioProcessor() {

    @Volatile
    private var enabled = false

    /** Desired gains in millibels, one per band. Guarded by `this`. */
    private val gainsMb = IntArray(BAND_COUNT)

    /** Coefficient snapshot per band: [b0, b1, b2, a1, a2]; null = flat band (skipped). */
    @Volatile
    private var coefficients: Array<FloatArray?> = arrayOfNulls(BAND_COUNT)

    /** Per-channel filter memory: BAND_COUNT * (x1, x2, y1, y2). Audio thread only. */
    private var filterState: Array<FloatArray> = emptyArray()

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    @Synchronized
    fun setBandGains(levelsMb: IntArray) {
        for (band in 0 until BAND_COUNT) {
            gainsMb[band] = (levelsMb.getOrElse(band) { 0 }).coerceIn(MIN_GAIN_MB, MAX_GAIN_MB)
        }
        rebuildCoefficients()
    }

    @Synchronized
    fun getBandGains(): IntArray = gainsMb.copyOf()

    @Synchronized
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // Sample rate may have changed — recompute the filters for it.
        rebuildCoefficients(inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val coeffs = coefficients
        var anyBandActive = false
        for (c in coeffs) if (c != null) { anyBandActive = true; break }
        if (!enabled || !anyBandActive) {
            output.put(inputBuffer)
        } else {
            val channelCount = inputAudioFormat.channelCount
            if (filterState.size != channelCount) {
                filterState = Array(channelCount) { FloatArray(BAND_COUNT * 4) }
            }
            var channel = 0
            while (inputBuffer.remaining() >= 2) {
                var sample = inputBuffer.short.toFloat()
                val state = filterState[channel]
                for (band in 0 until BAND_COUNT) {
                    val c = coeffs[band] ?: continue
                    val base = band * 4
                    val x1 = state[base]
                    val x2 = state[base + 1]
                    val y1 = state[base + 2]
                    val y2 = state[base + 3]
                    val y = c[0] * sample + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2
                    state[base] = sample
                    state[base + 1] = x1
                    state[base + 2] = y
                    state[base + 3] = y1
                    sample = y
                }
                output.putShort(sample.coerceIn(-32768f, 32767f).toInt().toShort())
                channel = if (channel + 1 == channelCount) 0 else channel + 1
            }
        }
        output.flip()
    }

    override fun onFlush() {
        clearFilterState()
    }

    override fun onReset() {
        clearFilterState()
    }

    private fun clearFilterState() {
        for (state in filterState) state.fill(0f)
    }

    /** Rebuild the RBJ peaking-EQ coefficient snapshot for the current gains. */
    private fun rebuildCoefficients(sampleRate: Int = inputAudioFormat.sampleRate) {
        if (sampleRate <= 0) return // Not configured yet; onConfigure rebuilds.
        val newCoefficients = arrayOfNulls<FloatArray>(BAND_COUNT)
        for (band in 0 until BAND_COUNT) {
            val mb = gainsMb[band]
            if (mb == 0) continue // Flat band — identity filter, skip entirely.
            val w0 = 2.0 * PI * CENTER_FREQUENCIES_HZ[band] / sampleRate
            if (w0 >= PI) continue // Band at/above Nyquist for this sample rate.
            val amp = 10.0.pow(mb / 100.0 / 40.0)
            val alpha = sin(w0) / (2.0 * Q)
            val cosW0 = cos(w0)
            val a0 = 1.0 + alpha / amp
            newCoefficients[band] = floatArrayOf(
                ((1.0 + alpha * amp) / a0).toFloat(),
                (-2.0 * cosW0 / a0).toFloat(),
                ((1.0 - alpha * amp) / a0).toFloat(),
                (-2.0 * cosW0 / a0).toFloat(),
                ((1.0 - alpha / amp) / a0).toFloat(),
            )
        }
        coefficients = newCoefficients
    }

    companion object {
        const val BAND_COUNT = 10

        /** ISO octave band centers. */
        val CENTER_FREQUENCIES_HZ = intArrayOf(
            31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000,
        )

        const val MIN_GAIN_MB = -1500
        const val MAX_GAIN_MB = 1500

        /** ~1-octave bandwidth per band. */
        private const val Q = 1.41
    }
}
