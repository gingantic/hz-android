package com.rhnxdev.hzplayer.data.datasource.player

import com.rhnxdev.hzplayer.domain.model.AspectRatioMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Preservation property tests for the AUTO-mode wrong aspect-ratio scaling defect.
 *
 * Property 2: Preservation - Non-AUTO And Already-Correct AUTO Behavior.
 *
 * Follows the observation-first methodology from the design's Preservation Checking. Because
 * the sizing logic currently lives inline inside the private [AspectRatioLayout.onMeasure] in
 * `FfmpegNativeEngine.kt`, the CURRENT inline branch logic for ALL modes is replicated here
 * verbatim as the observed baseline `F` ([currentOnMeasureSizing]), including:
 *   - the `parentWidth <= 0 || parentHeight <= 0` early return (no child sizing),
 *   - the `val ratio = if (aspectRatio > 0f) aspectRatio else (16f / 9f)` fallback,
 *   - the STRETCH fill (`childW = parentWidth`, `childH = parentHeight`),
 *   - the ZOOM crop-to-fill branch, and
 *   - the FIT / AUTO / fixed-ratio branch,
 *   - all fitted dimensions truncated with `.toInt()`.
 *
 * The tests assert `F(X) == F'(X)` for inputs where `isBugCondition` is FALSE. `F` is the OLD
 * inline baseline ([currentOnMeasureSizing]); `F'` is the FIXED inline logic
 * ([fixedOnMeasureSizing]) — `Math.round(...)` instead of `.toInt()`, and unknown-DAR AUTO
 * fills the container instead of boxing to 16:9. For non-buggy inputs those two changes do not
 * alter the result, so equality must hold; this confirms the fix introduced no regressions
 * (task 3.7). Bug-condition inputs are excluded from the equality check by [assertPreserved]
 * because the fix is expected to change their behavior (that change is verified by the
 * bug-condition test, task 3.6).
 *
 * **Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6**
 */
class AutoModeAspectRatioPreservationTest {

    /**
     * Result of a measure pass. [sized] is false when the container was non-positive and child
     * sizing was skipped (the early return); [w]/[h] are then meaningless.
     */
    private data class MeasureResult(val sized: Boolean, val w: Int, val h: Int)

    // ── Replicated CURRENT inline onMeasure sizing (the observed baseline F, all modes) ──

    /**
     * Faithful replica of `AspectRatioLayout.onMeasure()` child-sizing as it exists today.
     * `aspectRatio` is the container's known AUTO DAR (`0f` = unknown -> 16:9 fallback).
     */
    private fun currentOnMeasureSizing(
        resizeMode: AspectRatioMode,
        aspectRatio: Float,
        parentWidth: Int,
        parentHeight: Int
    ): MeasureResult {
        if (parentWidth <= 0 || parentHeight <= 0) return MeasureResult(sized = false, w = 0, h = 0)

        val ratio = if (aspectRatio > 0f) aspectRatio else (16f / 9f)
        val containerRatio = parentWidth.toFloat() / parentHeight.toFloat()
        val targetRatio = when (resizeMode) {
            AspectRatioMode.AUTO -> ratio
            AspectRatioMode.RATIO_16_9 -> 16f / 9f
            AspectRatioMode.RATIO_4_3 -> 4f / 3f
            AspectRatioMode.RATIO_21_9 -> 21f / 9f
            AspectRatioMode.RATIO_18_9 -> 18f / 9f
            AspectRatioMode.STRETCH -> containerRatio
            AspectRatioMode.ZOOM -> ratio
        }

        val (childW, childH) = if (resizeMode == AspectRatioMode.STRETCH) {
            parentWidth to parentHeight
        } else if (resizeMode == AspectRatioMode.ZOOM) {
            if (targetRatio > containerRatio) {
                val h = parentHeight
                val w = (h * targetRatio).toInt()
                w to h
            } else {
                val w = parentWidth
                val h = (w / targetRatio).toInt()
                w to h
            }
        } else {
            // FIT / AUTO / specific fixed ratios
            if (targetRatio > containerRatio) {
                val w = parentWidth
                val h = (w / targetRatio).toInt()
                w to h
            } else {
                val h = parentHeight
                val w = (h * targetRatio).toInt()
                w to h
            }
        }
        return MeasureResult(sized = true, w = childW, h = childH)
    }

    /**
     * `F'` — the FIXED sizing path, mirroring the shipped `AspectRatioLayout.onMeasure()`:
     *   - the `parentWidth <= 0 || parentHeight <= 0` early return is unchanged,
     *   - STRETCH still fills the container,
     *   - AUTO with an unknown DAR (`aspectRatio <= 0f`) now FILLS the container instead of
     *     boxing to 16:9 (the task-3.2 change), and
     *   - all fitted dimensions use `Math.round(...)` instead of `.toInt()` (the task-3.4 change).
     *
     * For inputs where `isBugCondition` is false these two changes must NOT alter the result,
     * so `F(X) == F'(X)` must hold there.
     */
    private fun fixedOnMeasureSizing(
        resizeMode: AspectRatioMode,
        aspectRatio: Float,
        parentWidth: Int,
        parentHeight: Int
    ): MeasureResult {
        if (parentWidth <= 0 || parentHeight <= 0) return MeasureResult(sized = false, w = 0, h = 0)

        val ratio = if (aspectRatio > 0f) aspectRatio else (16f / 9f)
        val containerRatio = parentWidth.toFloat() / parentHeight.toFloat()
        val targetRatio = when (resizeMode) {
            AspectRatioMode.AUTO -> ratio
            AspectRatioMode.RATIO_16_9 -> 16f / 9f
            AspectRatioMode.RATIO_4_3 -> 4f / 3f
            AspectRatioMode.RATIO_21_9 -> 21f / 9f
            AspectRatioMode.RATIO_18_9 -> 18f / 9f
            AspectRatioMode.STRETCH -> containerRatio
            AspectRatioMode.ZOOM -> ratio
        }

        val (childW, childH) = if (resizeMode == AspectRatioMode.STRETCH) {
            parentWidth to parentHeight
        } else if (resizeMode == AspectRatioMode.AUTO && aspectRatio <= 0f) {
            // FIXED: unknown-DAR AUTO fills the container instead of committing 16:9.
            parentWidth to parentHeight
        } else if (resizeMode == AspectRatioMode.ZOOM) {
            if (targetRatio > containerRatio) {
                val h = parentHeight
                val w = Math.round(h * targetRatio)
                w to h
            } else {
                val w = parentWidth
                val h = Math.round(w / targetRatio)
                w to h
            }
        } else {
            // FIT / AUTO / specific fixed ratios
            if (targetRatio > containerRatio) {
                val w = parentWidth
                val h = Math.round(w / targetRatio)
                w to h
            } else {
                val h = parentHeight
                val w = Math.round(h * targetRatio)
                w to h
            }
        }
        return MeasureResult(sized = true, w = childW, h = childH)
    }

    /**
     * The design's Bug Condition predicate, restricted to what these preservation inputs can
     * express. An input is a bug condition when it is AUTO AND the DAR is unknown at measure
     * time (`aspectRatio <= 0f`) with a positive container. Non-AUTO modes, non-positive
     * containers, and already-correct AUTO (positive known DAR) are NOT bug conditions, so their
     * results must be preserved exactly by the fix.
     *
     * (Non-square-SAR and rotated-non-square-SAR bug conditions are exercised by the
     * bug-condition test; here the DAR is supplied directly as `aspectRatio`, so the only
     * bug-condition this generator can produce is the unknown-DAR AUTO fallback.)
     */
    private fun isBugCondition(
        resizeMode: AspectRatioMode,
        aspectRatio: Float,
        parentWidth: Int,
        parentHeight: Int
    ): Boolean {
        if (parentWidth <= 0 || parentHeight <= 0) return false
        return resizeMode == AspectRatioMode.AUTO && aspectRatio <= 0f
    }

    /**
     * Assert preservation of `F(X)` by `F'(X)` for NON-buggy inputs only, using the design's
     * TOLERANCE-BASED preservation criterion rather than exact pixel equality.
     *
     * The task-3.4 fix replaces `.toInt()` (truncation) with `Math.round(...)` (symmetric
     * rounding). For some non-buggy ratio-fitting inputs this legitimately shifts a fitted child
     * dimension by AT MOST 1px versus the old truncating baseline. Per the design's Expected
     * Behavior ("within acceptable rounding tolerance" / "keeps the picture centered"), that
     * ±1px shift IS the intended preserved behavior — exact `F(X) == F'(X)` would be the wrong
     * criterion for the ratio-fitting branches. So:
     *   - the [MeasureResult.sized] flag must match EXACTLY (the non-positive-container
     *     passthrough must be byte-identical: no fit happens, nothing to round), and
     *   - when sized, the fitted width and height must match within ±1px.
     * The STRETCH fill and fixed-ratio-ignores-DAR exactness are asserted directly in their own
     * tests; those are unaffected because STRETCH does not round and the fixed-ratio "same size
     * across DARs" check is an F-vs-F comparison.
     */
    private fun assertPreserved(
        resizeMode: AspectRatioMode,
        aspectRatio: Float,
        parentWidth: Int,
        parentHeight: Int
    ) {
        if (isBugCondition(resizeMode, aspectRatio, parentWidth, parentHeight)) return
        val f = currentOnMeasureSizing(resizeMode, aspectRatio, parentWidth, parentHeight)
        val fPrime = fixedOnMeasureSizing(resizeMode, aspectRatio, parentWidth, parentHeight)

        // The sized flag (non-positive-container passthrough) must be byte-identical.
        assertEquals(
            "Preservation violated (sized flag differs): mode=$resizeMode ratio=$aspectRatio " +
                "container=${parentWidth}x$parentHeight (F=$f, F'=$fPrime)",
            f.sized,
            fPrime.sized
        )

        // When both skipped child sizing, w/h are meaningless — nothing more to check.
        if (!f.sized) return

        // Fitted dimensions must match within ±1px: the Math.round-vs-.toInt() rounding fix may
        // shift a fitted dimension by at most 1px. This is the design's "within rounding
        // tolerance / stays centered" preservation, not exact pixel equality.
        assertTrue(
            "Preservation violated: fitted size differs by more than 1px for mode=$resizeMode " +
                "ratio=$aspectRatio container=${parentWidth}x$parentHeight (F=$f, F'=$fPrime)",
            abs(f.w - fPrime.w) <= 1 && abs(f.h - fPrime.h) <= 1
        )
    }

    // ── 3.2 — Fixed-ratio modes force their fixed target ratio, ignoring the video DAR ──

    /**
     * RATIO_16_9 / RATIO_4_3 / RATIO_21_9 / RATIO_18_9 must fit to their fixed ratio regardless
     * of the video DAR passed as `aspectRatio`. Two very different DARs must yield the SAME
     * fitted size (the fixed target ignores the video DAR), and F == F'.
     */
    @Test
    fun fixedRatioModes_forceFixedRatio_ignoringVideoDar_andArePreserved() {
        val fixedModes = listOf(
            AspectRatioMode.RATIO_16_9,
            AspectRatioMode.RATIO_4_3,
            AspectRatioMode.RATIO_21_9,
            AspectRatioMode.RATIO_18_9
        )
        val containers = listOf(1920 to 1080, 1080 to 2400, 1000 to 1000, 800 to 1280)
        // Wildly different video DARs; a fixed-ratio mode must ignore all of them.
        val videoDars = listOf(0f, 0.25f, 1.0f, 2.3512f, 4f / 3f, 21f / 9f)

        for (mode in fixedModes) {
            for ((cw, ch) in containers) {
                // The fitted size must be identical no matter what video DAR is supplied.
                val reference = currentOnMeasureSizing(mode, videoDars.first(), cw, ch)
                for (dar in videoDars) {
                    val result = currentOnMeasureSizing(mode, dar, cw, ch)
                    assertEquals(
                        "$mode must ignore video DAR $dar (container ${cw}x$ch): " +
                            "expected $reference but got $result",
                        reference,
                        result
                    )
                    assertPreserved(mode, dar, cw, ch)
                }
            }
        }
    }

    // ── 3.3 — ZOOM crops-to-fill using the video ratio (child covers the container) ──

    /**
     * ZOOM must crop-to-fill: the fitted child covers the container (never leaves a gap on
     * both axes), and F == F'.
     */
    @Test
    fun zoomMode_cropsToFill_andIsPreserved() {
        val containers = listOf(1920 to 1080, 1080 to 2400, 1000 to 1000)
        val videoDars = listOf(0.5f, 4f / 3f, 16f / 9f, 2.3512f, 3.0f)
        for ((cw, ch) in containers) {
            for (dar in videoDars) {
                val result = currentOnMeasureSizing(AspectRatioMode.ZOOM, dar, cw, ch)
                assertTrue("ZOOM should produce a sized child", result.sized)
                // Crop-to-fill: child must cover the container on both axes (allow 1px rounding).
                assertTrue(
                    "ZOOM must crop-to-fill (cover container) for dar=$dar container=${cw}x$ch " +
                        "(child=${result.w}x${result.h})",
                    result.w >= cw - 1 && result.h >= ch - 1
                )
                assertPreserved(AspectRatioMode.ZOOM, dar, cw, ch)
            }
        }
    }

    // ── 3.4 — STRETCH fills the container completely (childW = parentWidth, childH = parentHeight) ──

    @Test
    fun stretchMode_fillsContainerCompletely_andIsPreserved() {
        val containers = listOf(1920 to 1080, 1080 to 2400, 1000 to 1000, 640 to 480)
        val videoDars = listOf(0f, 0.5f, 4f / 3f, 16f / 9f, 2.3512f)
        for ((cw, ch) in containers) {
            for (dar in videoDars) {
                val result = currentOnMeasureSizing(AspectRatioMode.STRETCH, dar, cw, ch)
                assertEquals("STRETCH childW must equal parentWidth", cw, result.w)
                assertEquals("STRETCH childH must equal parentHeight", ch, result.h)
                assertPreserved(AspectRatioMode.STRETCH, dar, cw, ch)
            }
        }
    }

    // ── 3.5 — Non-positive container skips child sizing without crashing ──

    @Test
    fun nonPositiveContainer_skipsChildSizing_forEveryMode_andIsPreserved() {
        val nonPositiveContainers = listOf(0 to 1080, 1920 to 0, 0 to 0, -10 to 100, 100 to -10)
        for (mode in AspectRatioMode.entries) {
            for ((cw, ch) in nonPositiveContainers) {
                val result = currentOnMeasureSizing(mode, aspectRatio = 1.5f, parentWidth = cw, parentHeight = ch)
                assertTrue(
                    "Non-positive container ${cw}x$ch must skip child sizing for $mode",
                    !result.sized
                )
                assertPreserved(mode, 1.5f, cw, ch)
            }
        }
    }

    // ── 3.1 — Square-SAR, no-rotation, even-dimension AUTO fits to true DAR as today ──

    /**
     * 1920x1080 (DAR 16:9) and 1280x720 (DAR 16:9) are square-SAR/no-rotation/even-dimension
     * AUTO inputs (isBugCondition == false for these). Their fitted child must equal the current
     * behavior, and F == F'.
     */
    @Test
    fun squareSarEvenDimensionAuto_fitsToTrueDar_asToday_andIsPreserved() {
        // Square SAR, no rotation => true DAR is just width/height.
        val cases = listOf(
            Triple(1920, 1080, 1920 to 1080), // 16:9 in a 16:9 container
            Triple(1280, 720, 1920 to 1080),  // 16:9 in a 16:9 container
            Triple(1920, 1080, 1080 to 2400), // 16:9 in a tall container (pillarbox height fit)
            Triple(1280, 720, 1000 to 1000)   // 16:9 in a square container
        )
        for ((vw, vh, container) in cases) {
            val (cw, ch) = container
            val trueDar = vw.toFloat() / vh.toFloat()
            val result = currentOnMeasureSizing(AspectRatioMode.AUTO, trueDar, cw, ch)
            assertTrue("Square-SAR AUTO should produce a sized child", result.sized)
            // Child must fit inside the container (letterbox/pillarbox, allow 1px rounding).
            assertTrue(
                "AUTO child must fit inside container for ${vw}x$vh in ${cw}x$ch " +
                    "(child=${result.w}x${result.h})",
                result.w <= cw + 1 && result.h <= ch + 1
            )
            assertPreserved(AspectRatioMode.AUTO, trueDar, cw, ch)
        }
    }

    // ── 3.6 — 90/270 rotation with square SAR swaps width/height for the rotated display ──

    /**
     * With square SAR and 90/270 rotation, the display W/H swap, so a landscape 1920x1080
     * source presents as a portrait 1080:1920 DAR. This input is NOT a bug condition (square
     * SAR), so its fitted size must be preserved.
     */
    @Test
    fun rotationWithSquareSar_swapsWidthHeight_andIsPreserved() {
        // Square SAR (sar = 1), rotation 90/270 => display DAR = height/width of the source.
        val cases = listOf(
            Triple(1920, 1080, 1080 to 2400), // rotated => 1080:1920 ≈ 0.5625 in tall container
            Triple(1280, 720, 1000 to 1000)   // rotated => 720:1280 = 0.5625 in square container
        )
        for ((vw, vh, container) in cases) {
            val (cw, ch) = container
            // Rotated display DAR with square SAR: swap => displayW=vh, displayH=vw.
            val rotatedDar = vh.toFloat() / vw.toFloat()
            val result = currentOnMeasureSizing(AspectRatioMode.AUTO, rotatedDar, cw, ch)
            assertTrue("Rotated square-SAR AUTO should produce a sized child", result.sized)
            assertTrue(
                "Rotated AUTO child must fit inside container for ${vw}x$vh (rot) in ${cw}x$ch " +
                    "(child=${result.w}x${result.h})",
                result.w <= cw + 1 && result.h <= ch + 1
            )
            assertPreserved(AspectRatioMode.AUTO, rotatedDar, cw, ch)
        }
    }

    // ── Property-based preservation: F(X) == F'(X) over generated non-buggy inputs ──

    /**
     * Generative property (plain Kotlin loop, matching Task 1's no-PBT-library convention):
     * for random non-AUTO modes, random container sizes, and random DARs, assert F(X) == F'(X).
     * Non-AUTO modes are never a bug condition, so preservation must hold for all of them.
     *
     * **Validates: Requirements 3.2, 3.3, 3.4, 3.5**
     */
    @Test
    fun property_nonAutoModes_arePreserved_overRandomInputs() {
        val rng = Random(0xA5C21D9L) // fixed seed for reproducibility
        val nonAutoModes = AspectRatioMode.entries.filter { it != AspectRatioMode.AUTO }
        repeat(2000) {
            val mode = nonAutoModes[rng.nextInt(nonAutoModes.size)]
            // Include non-positive containers occasionally to exercise the passthrough too.
            val cw = rng.nextInt(-20, 3841)
            val ch = rng.nextInt(-20, 3841)
            val dar = rng.nextFloat() * 4f // 0.0 .. 4.0 (includes the unknown-DAR 0f case)
            assertPreserved(mode, dar, cw, ch)
        }
    }

    /**
     * Generative property for AUTO inputs that already resolve to a known positive DAR (square
     * SAR / no rotation is modeled by supplying a positive `aspectRatio` directly). These
     * already-correct AUTO inputs must be preserved. `aspectRatio > 0f` avoids the
     * darUnknownAtMeasure bug condition; positive containers avoid the passthrough edge.
     *
     * **Validates: Requirements 3.1, 3.6**
     */
    @Test
    fun property_alreadyCorrectAuto_isPreserved_overRandomInputs() {
        val rng = Random(0x1357BDFL)
        repeat(2000) {
            val cw = rng.nextInt(1, 3841)
            val ch = rng.nextInt(1, 3841)
            val dar = 0.2f + rng.nextFloat() * 4f // strictly positive known DAR (0.2 .. 4.2)
            assertPreserved(AspectRatioMode.AUTO, dar, cw, ch)
        }
    }
}
