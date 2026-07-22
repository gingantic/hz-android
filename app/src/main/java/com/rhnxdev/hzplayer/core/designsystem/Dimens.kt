package com.rhnxdev.hzplayer.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// ── Spacing (8dp system) ────────────────────────────────────────

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

// ── Corner Radii ────────────────────────────────────────────────

object CornerRadii {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

// ── Card Sizes ──────────────────────────────────────────────────

object CardSizes {
    val videoThumbnailHeight = 140.dp
    val albumCoverSize = 160.dp
    val artistImageSize = 120.dp
    val mediaIconSize = 48.dp
    val miniPlayerHeight = 64.dp
}

// ── Material Shapes ─────────────────────────────────────────────

val HzPlayerShapes = Shapes(
    extraSmall = RoundedCornerShape(CornerRadii.xs),
    small = RoundedCornerShape(CornerRadii.sm),
    medium = RoundedCornerShape(CornerRadii.md),
    large = RoundedCornerShape(CornerRadii.lg),
    extraLarge = RoundedCornerShape(CornerRadii.xl),
)

// ── Browser-specific Dimensions ───────────────────────────────

object BrowserDimens {
    val toolbarHeight = 56.dp
    val tabStripHeight = 44.dp
    val urlBarHeight = 44.dp
    val newTabGridColumns = 2
    val newTabGridLandscapeColumns = 3
}
