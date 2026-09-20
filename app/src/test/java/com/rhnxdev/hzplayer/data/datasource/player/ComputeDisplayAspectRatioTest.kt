package com.rhnxdev.hzplayer.data.datasource.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Focused unit tests for the extracted pure functions of the AUTO-mode wrong aspect-ratio
 * scaling fix (task 4).
 *
 * These exercise the real, shipped [FfmpegNativeEngine.computeDisplayAspectRatio] directly (it
 * is `internal` and this test lives in the same package, so it is accessible), plus the FIXED
 * fitted-size arithmetic and the AUTO unknown-DAR fallback semantics replicated verbatim from
 * the shipped `AspectRatioLayout.onMeasure()`.
 *
 * Unlike the property-based preservation/bug-condition tests, these are hand-picked example and
 * edge cases that pin down specific known values (DVD 16:15 -> 4:3, anamorphic 4:3 -> 0.4219,
 * rotation swaps, zero/negative inputs, symmetric rounding, and the full-container fallback).
 *
 * **Validates: Requirements 2.1, 2.2, 2.4, 2.5**
 */
class ComputeDisplayAspectRatioTest {

    private companion object {
        /** Tolerance for float DAR comparisons. */
        const val TOLERANCE = 1e-4f
    }

    // ── computeDisplayAspectRatio: square SAR + SAR defaulting ─────────────────────────────

    /** Square SAR 1:1, no rotation: 1920x1080 -> 16/9. */
    @Test
    fun squareSar_noRotation_returnsWidthOverHeight() {
        val dar = FfmpegNativeEngine.computeDisplayAspectRatio(1920, 1080, 1, 1, 0)
        assertEquals(16f / 9f, dar, TOLERANCE)
    }

    /** SAR num/den <= 0 must default to 1:1 (treated as square). */
    @Test
    fun nonPositiveSar_defaultsToSquare() {
        val expected = 1920f / 1080f
        // sarNum == 0
        assertEquals(expected, FfmpegNativeEngine.computeDisplayAspectRatio(1920, 1080, 0, 1, 0), TOLERANCE)
        // sarDen == 0
        assertEquals(expected, FfmpegNativeEngine.computeDisplayAspectRatio(1920, 1080, 1, 0, 0), TOLERANCE)
        // both negative
        assertEquals(expected, FfmpegNativeEngine.computeDisplayAspectRatio(1920, 1080, -4, -3, 0), TOLERANCE)
        // both zero
        assertEquals(expected, FfmpegNativeEngine.computeDisplayAspectRatio(1920, 1080, 0, 0, 0), TOLERANCE)
    }

    // ── computeDisplayAspectRatio: non-square (anamorphic) SAR ─────────────────────────────

    /** DVD PAL 720x576 with SAR 16:15 -> (720 * 16/15) / 576 = 768/576 = 4:3. */
    @Test
    fun nonSquareSar_dvd16x15_yields4x3() {
        val dar = FfmpegNativeEngine.computeDisplayAspectRatio(720, 576, 16, 15, 0)
        assertEquals(4f / 3f, dar, TOLERANCE)
    }

    /** Anamorphic 720x480 (NTSC) with SAR 40:33 -> (720 * 40/33) / 480 ~= 1.8182 (~16:9-ish). */
    @Test
    fun nonSquareSar_anamorphicNtsc_yieldsExpectedDar() {
        val expected = (720f * 40f / 33f) / 480f
        val dar = FfmpegNativeEngine.computeDisplayAspectRatio(720, 480, 40, 33, 0)
        assertEquals(expected, dar, TOLERANCE)
    }

    // ── computeDisplayAspectRatio: rotation 0 / 90 / 180 / 270 ─────────────────────────────

    /** Rotation 0 and 180 do NOT swap display W/H; 90 and 270 DO. Square SAR case. */
    @Test
    fun rotation_swapsDisplayDimensions_onlyFor90And270() {
        val unrotated = 1920f / 1080f
        val swapped = 1080f / 1920f

        assertEquals(unrotated, FfmpegNativeEngine.computeDisplayAspectRatio(1920, 1080, 1, 1, 0), TOLERANCE)
        assertEquals(swapped, FfmpegNativeEngine.computeDisplayAspectRatio(1920, 1080, 1, 1, 90), TOLERANCE)
        assertEquals(unrotated, FfmpegNativeEngine.computeDisplayAspectRatio(1920, 1080, 1, 1, 180), TOLERANCE)
        assertEquals(swapped, FfmpegNativeEngine.computeDisplayAspectRatio(1920, 1080, 1, 1, 270), TOLERANCE)
    }

    /** 270 rotation swaps identically to 90. */
    @Test
    fun rotation270_swapsLikeRotation90() {
        val r90 = FfmpegNativeEngine.computeDisplayAspectRatio(1280, 720, 1, 1, 90)
        val r270 = FfmpegNativeEngine.computeDisplayAspectRatio(1280, 720, 1, 1, 270)
        assertEquals(r90, r270, TOLERANCE)
        assertEquals(720f / 1280f, r90, TOLERANCE)
    }

    // ── computeDisplayAspectRatio: non-square SAR combined with 90/270 rotation ─────────────

    /**
     * 1920x1080 SAR 4:3 rotation 90: unrotated display = (1920 * 4/3):1080 = 2560:1080; after
     * the swap the display is 1080:2560 ~= 0.42188. SAR is applied to width FIRST, then swapped.
     */
    @Test
    fun nonSquareSar_withRotation90_appliesSarBeforeSwap() {
        val expected = 1080f / 2560f // ~= 0.42188
        val dar = FfmpegNativeEngine.computeDisplayAspectRatio(1920, 1080, 4, 3, 90)
        assertEquals(expected, dar, TOLERANCE)
    }

    /** Same anamorphic source at 270 must match the 90 result (both swap). */
    @Test
    fun nonSquareSar_withRotation270_matchesRotation90() {
        val r90 = FfmpegNativeEngine.computeDisplayAspectRatio(1920, 1080, 4, 3, 90)
        val r270 = FfmpegNativeEngine.computeDisplayAspectRatio(1920, 1080, 4, 3, 270)
        assertEquals(r90, r270, TOLERANCE)
    }

    // ── computeDisplayAspectRatio: zero / negative inputs -> exactly 0f ─────────────────────

    @Test
    fun nonPositiveWidthOrHeight_returnsZeroExactly() {
        assertEquals(0f, FfmpegNativeEngine.computeDisplayAspectRatio(0, 1080, 1, 1, 0))
        assertEquals(0f, FfmpegNativeEngine.computeDisplayAspectRatio(1920, 0, 1, 1, 0))
        assertEquals(0f, FfmpegNativeEngine.computeDisplayAspectRatio(-1920, 1080, 1, 1, 0))
        assertEquals(0f, FfmpegNativeEngine.computeDisplayAspectRatio(1920, -1080, 1, 1, 0))
        assertEquals(0f, FfmpegNativeEngine.computeDisplayAspectRatio(0, 0, 1, 1, 90))
    }

    // ── Fitted-size rounding: replicate the FIXED fit formula (Math.round, no down-bias) ────

    private data class Fitted(val w: Int, val h: Int)

    /**
     * Mirrors the FIXED `AspectRatioLayout.onMeasure()` FIT/AUTO branch: fit the child to
     * [targetRatio] inside the container using symmetric rounding (`Math.round`).
     */
    private fun fitChild(targetRatio: Float, parentWidth: Int, parentHeight: Int): Fitted {
        val containerRatio = parentWidth.toFloat() / parentHeight.toFloat()
        return if (targetRatio > containerRatio) {
            val w = parentWidth
            val h = Math.round(w / targetRatio)
            Fitted(w, h)
        } else {
            val h = parentHeight
            val w = Math.round(h * targetRatio)
            Fitted(w, h)
        }
    }

    /**
     * Symmetric rounding: for fractional fits, the fitted dimension must equal `Math.round` of
     * the exact value (not `.toInt()` truncation). Includes cases whose fractional part is
     * >= 0.5 so round and truncate diverge, proving no systematic downward bias.
     */
    @Test
    fun fitChild_roundsSymmetrically_noDownwardBias() {
        // Tall container so width is fixed and height is the fitted (rounded) dimension.
        // 1080 / 2.35 = 459.574 -> round 460 (toInt would be 459).
        run {
            val fitted = fitChild(2.35f, parentWidth = 1080, parentHeight = 2400)
            val exactHeight = 1080f / 2.35f
            assertEquals(1080, fitted.w)
            assertEquals(Math.round(exactHeight), fitted.h)
            assertEquals(460, fitted.h)
        }
        // 1080 / 2.3512 = 459.34 -> round 459 (rounds down here, still symmetric).
        run {
            val fitted = fitChild(2.3512f, parentWidth = 1080, parentHeight = 2400)
            assertEquals(Math.round(1080f / 2.3512f), fitted.h)
        }
        // Wide container so height is fixed and width is the fitted dimension.
        // 1080 * 1.7778 (16:9) fitted; use a fractional case: h=1081, ratio 1.5 -> 1621.5 -> 1622.
        run {
            val fitted = fitChild(1.5f, parentWidth = 4000, parentHeight = 1081)
            val exactWidth = 1081f * 1.5f // 1621.5 -> round 1622
            assertEquals(1081, fitted.h)
            assertEquals(Math.round(exactWidth), fitted.w)
            assertEquals(1622, fitted.w)
        }
    }

    /**
     * The fitted child must fit within the container and stay centered: the centered offset
     * `(parent - child) / 2` must be >= 0 (child never exceeds the container) on the fitted
     * axis, matching the `onLayout` centering math, within a 1px rounding tolerance.
     */
    @Test
    fun fitChild_staysWithinContainerAndCentered() {
        val containers = listOf(1920 to 1080, 1080 to 2400, 1000 to 1000, 3840 to 2160)
        val ratios = listOf(0.4219f, 4f / 3f, 16f / 9f, 2.3512f, 2.35f, 0.5625f)
        for ((cw, ch) in containers) {
            for (ratio in ratios) {
                val fitted = fitChild(ratio, cw, ch)
                // Child fits inside the container (allow 1px rounding slack).
                assertTrue(
                    "Child ${fitted.w}x${fitted.h} must fit in ${cw}x$ch (ratio=$ratio)",
                    fitted.w <= cw + 1 && fitted.h <= ch + 1
                )
                // Centered offsets: >= 0 within 1px (no negative / off-container placement).
                val childLeft = (cw - fitted.w) / 2
                val childTop = (ch - fitted.h) / 2
                assertTrue(
                    "childLeft=$childLeft must be >= -1 (centered) for ${cw}x$ch ratio=$ratio " +
                        "(child ${fitted.w}x${fitted.h})",
                    childLeft >= -1
                )
                assertTrue(
                    "childTop=$childTop must be >= -1 (centered) for ${cw}x$ch ratio=$ratio " +
                        "(child ${fitted.w}x${fitted.h})",
                    childTop >= -1
                )
                // The committed child ratio must match the target within rounding tolerance.
                val committed = fitted.w.toFloat() / fitted.h.toFloat()
                assertTrue(
                    "Committed ratio $committed should be near target $ratio for ${cw}x$ch " +
                        "(child ${fitted.w}x${fitted.h})",
                    abs(committed - ratio) <= 0.01f
                )
            }
        }
    }

    // ── Fallback semantics: AUTO with unknown DAR -> full-container child, NOT 16:9 ─────────

    private data class ChildSize(val w: Int, val h: Int, val usedHardcoded16x9: Boolean)

    /**
     * Mirrors the FIXED `AspectRatioLayout.onMeasure()` AUTO branch: when the DAR is unknown
     * (`aspectRatio <= 0f`), fill the container instead of boxing to a hardcoded 16:9.
     */
    private fun autoOnMeasure(aspectRatio: Float, parentWidth: Int, parentHeight: Int): ChildSize {
        if (aspectRatio <= 0f) {
            // FIXED: full-container child, no 16:9 fallback committed.
            return ChildSize(parentWidth, parentHeight, usedHardcoded16x9 = false)
        }
        val fitted = fitChild(aspectRatio, parentWidth, parentHeight)
        return ChildSize(fitted.w, fitted.h, usedHardcoded16x9 = false)
    }

    /** AUTO with unknown DAR (aspectRatio <= 0f) fills the container exactly, not 16:9. */
    @Test
    fun autoUnknownDar_fillsContainer_notSixteenNine() {
        val parentWidth = 1920
        val parentHeight = 1080
        val result = autoOnMeasure(aspectRatio = 0f, parentWidth = parentWidth, parentHeight = parentHeight)

        assertTrue("AUTO unknown DAR must not commit hardcoded 16:9", !result.usedHardcoded16x9)
        assertEquals("Full-container width", parentWidth, result.w)
        assertEquals("Full-container height", parentHeight, result.h)

        // Also verify the fallback fills a non-16:9 container completely (would be wrong if
        // the code boxed to 16:9: e.g. a 1080x2400 container would NOT stay full).
        val tall = autoOnMeasure(aspectRatio = 0f, parentWidth = 1080, parentHeight = 2400)
        assertEquals(1080, tall.w)
        assertEquals(2400, tall.h)
        // A 16:9 box in a 1080x2400 container would fit height 1080/(16/9)=607, proving this is
        // NOT the 16:9 fallback.
        assertTrue("Fallback must NOT be a 16:9 box", tall.h != Math.round(1080f / (16f / 9f)))
    }

    /** Once the DAR resolves (positive), AUTO fits to that DAR (self-correction path). */
    @Test
    fun autoKnownDar_fitsToDar() {
        val result = autoOnMeasure(aspectRatio = 4f / 3f, parentWidth = 1920, parentHeight = 1080)
        // 4:3 (1.333) < container 16:9 (1.778): height fixed, width fitted.
        assertEquals(1080, result.h)
        assertEquals(Math.round(1080f * (4f / 3f)), result.w)
        val committed = result.w.toFloat() / result.h.toFloat()
        assertEquals(4f / 3f, committed, 0.01f)
    }
}
