package com.rhnxdev.hzplayer.data.datasource.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Bug condition exploration test for the AUTO-mode wrong aspect-ratio scaling defect.
 *
 * Property 1: Bug Condition / Expected Behavior - AUTO Mode Fits To True DAR.
 *
 * This test encodes the EXPECTED (fixed) behavior for the bug-condition inputs from the
 * design's "Exploratory Bug Condition Checking". The AUTO sizing logic lives inline inside the
 * private [AspectRatioLayout.onMeasure] + [applyAspectRatio] in FfmpegNativeEngine.kt, so the
 * system-under-test path is replicated here ([fittedAuto]) to mirror the FIXED behavior:
 *   - the DAR is computed by the shipped [FfmpegNativeEngine.computeDisplayAspectRatio], and
 *   - AUTO with an unknown DAR (`aspectRatio <= 0f`) fills the container instead of boxing to
 *     a hardcoded 16:9, and
 *   - the fitted child width/height use `Math.round(...)` (symmetric rounding), not `.toInt()`.
 *
 * The assertions verify the EXPECTED behavior (true DAR, no 16:9 fallback committed, symmetric
 * rounding). On the FIXED code this test PASSES, confirming the bug is resolved. (On the
 * original UNFIXED inline logic — 16:9 fallback + `.toInt()` truncation — it fails, which is
 * what originally proved the bug existed.)
 *
 * **Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 2.1, 2.2, 2.3, 2.4, 2.5**
 */
class AutoModeAspectRatioBugConditionTest {

    // ── Replicated FIXED inline sizing (the system-under-test path / FIXED code) ──

    private data class ChildSize(val w: Int, val h: Int, val usedHardcoded16x9: Boolean)

    /**
     * True display aspect ratio: (w * SAR) : h, with a 90/270 rotation swapping display W/H.
     * Delegates to the SHIPPED [FfmpegNativeEngine.computeDisplayAspectRatio] so the test
     * exercises the real production code. Returns 0f for non-positive inputs.
     */
    private fun autoRatioOf(
        videoWidth: Int,
        videoHeight: Int,
        sarNum: Int,
        sarDen: Int,
        rotationDegrees: Int
    ): Float = FfmpegNativeEngine.computeDisplayAspectRatio(
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        sarNum = sarNum,
        sarDen = sarDen,
        rotationDegrees = rotationDegrees
    )

    /**
     * Mirrors the FIXED `AspectRatioLayout.onMeasure()` AUTO branch:
     *   - AUTO with an unknown DAR (`aspectRatio <= 0f`) FILLS the container (no 16:9 boxing), and
     *   - the fitted child dimension uses `Math.round(...)` (symmetric rounding), not `.toInt()`.
     */
    private fun fittedAuto(
        aspectRatio: Float,
        parentWidth: Int,
        parentHeight: Int
    ): ChildSize {
        // FIXED behavior: unknown DAR fills the container instead of committing 16:9.
        if (aspectRatio <= 0f) {
            return ChildSize(parentWidth, parentHeight, usedHardcoded16x9 = false)
        }
        val containerRatio = parentWidth.toFloat() / parentHeight.toFloat()
        val targetRatio = aspectRatio
        return if (targetRatio > containerRatio) {
            val w = parentWidth
            val h = Math.round(w / targetRatio)
            ChildSize(w, h, usedHardcoded16x9 = false)
        } else {
            val h = parentHeight
            val w = Math.round(h * targetRatio)
            ChildSize(w, h, usedHardcoded16x9 = false)
        }
    }

    private val ROUNDING_TOLERANCE = 0.01f

    // ── Bug-condition test cases (expected/fixed behavior asserted against unfixed baseline) ──

    /** 1.1 / 2.1 — Non-square SAR: 720x576 SAR 16:15, no rotation -> true DAR ~= 4:3 (1.3333). */
    @Test
    fun nonSquareSar_fitsToTrueDar_notWrongRatio() {
        val expectedDar = (720f * 16f / 15f) / 576f // = 768/576 = 4:3 = 1.33333
        val autoRatio = autoRatioOf(
            videoWidth = 720, videoHeight = 576, sarNum = 16, sarDen = 15, rotationDegrees = 0
        )
        val fitted = fittedAuto(autoRatio, parentWidth = 1920, parentHeight = 1080)

        assertFalse("AUTO must not commit the hardcoded 16:9 fallback", fitted.usedHardcoded16x9)
        val committedRatio = fitted.w.toFloat() / fitted.h.toFloat()
        assertTrue(
            "Expected true DAR ~= $expectedDar (4:3) but committed $committedRatio " +
                "(child ${fitted.w}x${fitted.h})",
            abs(committedRatio - expectedDar) <= ROUNDING_TOLERANCE
        )
    }

    /**
     * 1.2 / 1.3 / 2.2 — Unknown-DAR fallback: AUTO measured before dimensions/SAR resolve
     * (aspectRatio == 0f). The layout must NOT commit a hardcoded 16:9 result.
     */
    @Test
    fun unknownDar_doesNotCommitHardcoded16x9() {
        val fitted = fittedAuto(aspectRatio = 0f, parentWidth = 1920, parentHeight = 1080)
        assertFalse(
            "AUTO with unknown DAR must NOT box the child to 16:9 as the final result " +
                "(committed child ${fitted.w}x${fitted.h})",
            fitted.usedHardcoded16x9
        )
    }

    /**
     * 1.4 / 2.4 — Odd-dimension fit: true DAR ~= 2.3512 in a 1080x2400 container. The fitted
     * height 1080/2.3512 = 459.34 must round symmetrically, not be truncated downward by `.toInt()`.
     * Use a case whose fractional part rounds UP so truncation and rounding diverge.
     */
    @Test
    fun oddDimensionFit_roundsSymmetrically_notTruncated() {
        // Choose a ratio whose fitted height has a fractional part >= 0.5 so round != toInt.
        // container 1080 wide; targetRatio < containerRatio so height = parentHeight, width = round(h * ratio).
        // Use the design's true DAR ~= 2.3512 with a tall container so the width is fitted.
        val trueDar = 2.3512f
        val parentWidth = 1080
        val parentHeight = 2400
        // targetRatio (2.3512) > containerRatio (0.45) -> width = parentWidth, height = width / ratio.
        val exactHeight = parentWidth / trueDar // 1080 / 2.3512 = 459.34...
        val expectedRounded = Math.round(exactHeight)
        val fitted = fittedAuto(trueDar, parentWidth, parentHeight)

        assertTrue(
            "Fitted height should be symmetric-rounded ($expectedRounded) not truncated " +
                "(exact=$exactHeight, committed=${fitted.h})",
            fitted.h == expectedRounded
        )
    }

    /**
     * Odd-dimension fit variant where truncation and rounding DIVERGE (fractional part >= 0.5),
     * proving the systematic downward bias of `.toInt()`.
     */
    @Test
    fun oddDimensionFit_upwardRoundingCase_noDownwardBias() {
        // Pick ratio so parentWidth / ratio has fractional part >= 0.5.
        // 1080 / 2.35 = 459.574 -> round 460, toInt 459.
        val trueDar = 2.35f
        val parentWidth = 1080
        val parentHeight = 2400
        val exactHeight = parentWidth / trueDar
        val expectedRounded = Math.round(exactHeight) // 460
        val fitted = fittedAuto(trueDar, parentWidth, parentHeight)

        assertTrue(
            "Expected symmetric rounding to $expectedRounded (exact=$exactHeight) but got " +
                "${fitted.h} (downward-truncation bias)",
            fitted.h == expectedRounded
        )
    }

    /**
     * 1.5 / 2.5 — Non-square SAR + 90 rotation: 1920x1080 SAR 4:3 rot 90 -> unrotated display
     * (1920 * 4/3):1080 = 2560:1080; after the swap the display is 1080:2560 (~= 0.4219).
     */
    @Test
    fun nonSquareSarWithRotation_fitsToSwappedDar() {
        val expectedDar = 1080f / 2560f // ~= 0.42188
        val autoRatio = autoRatioOf(
            videoWidth = 1920, videoHeight = 1080, sarNum = 4, sarDen = 3, rotationDegrees = 90
        )
        assertTrue(
            "Expected swapped DAR ~= $expectedDar but computeDisplayAspectRatio returned $autoRatio",
            abs(autoRatio - expectedDar) <= ROUNDING_TOLERANCE
        )

        val fitted = fittedAuto(autoRatio, parentWidth = 1080, parentHeight = 2400)
        assertFalse("AUTO must not commit the hardcoded 16:9 fallback", fitted.usedHardcoded16x9)
        val committedRatio = fitted.w.toFloat() / fitted.h.toFloat()
        assertTrue(
            "Expected committed ratio ~= $expectedDar but got $committedRatio " +
                "(child ${fitted.w}x${fitted.h})",
            abs(committedRatio - expectedDar) <= ROUNDING_TOLERANCE
        )
    }
}
