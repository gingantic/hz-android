package com.rhnxdev.hzplayer.core.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Resting opacity of the thumb; high enough to be found, low enough not to distract. */
private const val IDLE_ALPHA = 0.4f

/** Shortest thumb, so a very long list still leaves something to grab. */
private val MIN_THUMB_HEIGHT = 36.dp

/** Keeps the thumb from covering the whole track, so movement stays visible. */
private val THUMB_TRACK_GAP = 16.dp

/** Width of the strip along the end edge that accepts a grab. */
private val HIT_AREA_WIDTH = 48.dp

/** Vertical slack around the thumb that still counts as grabbing it. */
private val GRAB_MARGIN = 20.dp

/**
 * Abstraction across scrollable containers (LazyColumn, LazyVerticalGrid, Column)
 * providing unified metrics for scrollbar positioning, sizing, and scrubbing.
 */
@Stable
interface ScrollbarAdapter {
    /** True while a fling or drag is actively changing scroll offset. */
    val isScrollInProgress: Boolean

    /** True if content exceeds viewport and can scroll in either direction. */
    val canScroll: Boolean

    /** Ratio of visible content to total content (0f..1f), used for thumb height. */
    val visibleRatio: Float

    /** Current scroll position fraction from 0f (top) to 1f (bottom). */
    val scrollProgress: Float

    /** Scrolls the container to the given fraction (0f..1f). */
    suspend fun scrollToProgress(progress: Float)
}

/** [ScrollbarAdapter] for [LazyListState]. */
private class LazyListScrollbarAdapter(
    private val state: LazyListState,
) : ScrollbarAdapter {

    override val isScrollInProgress: Boolean
        get() = state.isScrollInProgress

    override val canScroll: Boolean
        get() = state.canScrollForward || state.canScrollBackward ||
            (state.layoutInfo.totalItemsCount > state.layoutInfo.visibleItemsInfo.size && state.layoutInfo.visibleItemsInfo.isNotEmpty())

    override val visibleRatio: Float
        get() {
            val total = state.layoutInfo.totalItemsCount
            val visible = state.layoutInfo.visibleItemsInfo.size
            if (total <= 0 || visible <= 0) return 1f
            return (visible.toFloat() / total.toFloat()).coerceIn(0.04f, 1f)
        }

    override val scrollProgress: Float
        get() {
            if (!state.canScrollBackward) return 0f
            if (!state.canScrollForward) return 1f
            val total = state.layoutInfo.totalItemsCount
            val visibleInfo = state.layoutInfo.visibleItemsInfo
            val visible = visibleInfo.size
            val maxFirstIndex = (total - visible).coerceAtLeast(1)
            val firstIndex = state.firstVisibleItemIndex
            val firstOffset = state.firstVisibleItemScrollOffset
            val firstItemSize = visibleInfo.firstOrNull()?.size ?: 1
            val exactIndex = firstIndex + (firstOffset.toFloat() / firstItemSize.coerceAtLeast(1).toFloat())
            return (exactIndex / maxFirstIndex.toFloat()).coerceIn(0f, 1f)
        }

    // Seeks by pixels rather than item index so the thumb tracks the finger 1:1.
    override suspend fun scrollToProgress(progress: Float) {
        val info = state.layoutInfo
        val total = info.totalItemsCount
        val visible = info.visibleItemsInfo
        if (total <= 0 || visible.isEmpty()) return
        if (progress <= 0.001f) {
            state.scrollToItem(0, 0)
            return
        }
        if (progress >= 0.999f) {
            state.scrollToItem(total - 1, 0)
            return
        }
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        val avgSize = visible.sumOf { it.size }.toFloat() / visible.size
        val maxOffset = (avgSize * total - viewport).coerceAtLeast(1f)
        val current = state.firstVisibleItemIndex * avgSize + state.firstVisibleItemScrollOffset
        val delta = progress * maxOffset - current
        if (abs(delta) >= 1f) state.scrollBy(delta)
    }
}

/** [ScrollbarAdapter] for [LazyGridState]. */
private class LazyGridScrollbarAdapter(
    private val state: LazyGridState,
) : ScrollbarAdapter {

    override val isScrollInProgress: Boolean
        get() = state.isScrollInProgress

    override val canScroll: Boolean
        get() = state.canScrollForward || state.canScrollBackward ||
            (state.layoutInfo.totalItemsCount > state.layoutInfo.visibleItemsInfo.size && state.layoutInfo.visibleItemsInfo.isNotEmpty())

    override val visibleRatio: Float
        get() {
            val total = state.layoutInfo.totalItemsCount
            val visible = state.layoutInfo.visibleItemsInfo.size
            if (total <= 0 || visible <= 0) return 1f
            return (visible.toFloat() / total.toFloat()).coerceIn(0.04f, 1f)
        }

    override val scrollProgress: Float
        get() {
            if (!state.canScrollBackward) return 0f
            if (!state.canScrollForward) return 1f
            val total = state.layoutInfo.totalItemsCount
            val visibleInfo = state.layoutInfo.visibleItemsInfo
            val visible = visibleInfo.size
            val maxFirstIndex = (total - visible).coerceAtLeast(1)
            val firstIndex = state.firstVisibleItemIndex
            val firstOffset = state.firstVisibleItemScrollOffset
            val firstItemHeight = visibleInfo.firstOrNull()?.size?.height ?: 1
            val exactIndex = firstIndex + (firstOffset.toFloat() / firstItemHeight.coerceAtLeast(1).toFloat())
            return (exactIndex / maxFirstIndex.toFloat()).coerceIn(0f, 1f)
        }

    // Seeks by pixels rather than item index so the thumb tracks the finger 1:1.
    override suspend fun scrollToProgress(progress: Float) {
        val info = state.layoutInfo
        val total = info.totalItemsCount
        val visible = info.visibleItemsInfo
        if (total <= 0 || visible.isEmpty()) return
        if (progress <= 0.001f) {
            state.scrollToItem(0, 0)
            return
        }
        if (progress >= 0.999f) {
            state.scrollToItem(total - 1, 0)
            return
        }
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        val avgHeight = visible.sumOf { it.size.height }.toFloat() / visible.size
        val maxOffset = (avgHeight * total - viewport).coerceAtLeast(1f)
        val current = state.firstVisibleItemIndex * avgHeight + state.firstVisibleItemScrollOffset
        val delta = progress * maxOffset - current
        if (abs(delta) >= 1f) state.scrollBy(delta)
    }
}

/** [ScrollbarAdapter] for regular [ScrollState]. */
private class ScrollStateScrollbarAdapter(
    private val state: ScrollState,
) : ScrollbarAdapter {

    override val isScrollInProgress: Boolean
        get() = state.isScrollInProgress

    override val canScroll: Boolean
        get() = state.maxValue > 0

    override val visibleRatio: Float
        get() {
            val total = state.maxValue + state.viewportSize
            if (total <= 0) return 1f
            return (state.viewportSize.toFloat() / total.toFloat()).coerceIn(0.04f, 1f)
        }

    override val scrollProgress: Float
        get() {
            if (state.maxValue <= 0) return 0f
            return (state.value.toFloat() / state.maxValue.toFloat()).coerceIn(0f, 1f)
        }

    override suspend fun scrollToProgress(progress: Float) {
        if (state.maxValue <= 0) return
        val target = (progress * state.maxValue).roundToInt().coerceIn(0, state.maxValue)
        state.scrollTo(target)
    }
}

@Composable
fun rememberScrollbarAdapter(state: LazyListState): ScrollbarAdapter =
    remember(state) { LazyListScrollbarAdapter(state) }

@Composable
fun rememberScrollbarAdapter(state: LazyGridState): ScrollbarAdapter =
    remember(state) { LazyGridScrollbarAdapter(state) }

@Composable
fun rememberScrollbarAdapter(state: ScrollState): ScrollbarAdapter =
    remember(state) { ScrollStateScrollbarAdapter(state) }

/**
 * Attaches an interactive vertical scrollbar to a scrollable container.
 *
 * - Rests as a faint thumb so it can always be found and grabbed.
 * - Brightens and thickens while scrolling or dragging.
 * - Grabbing the thumb starts the drag immediately, in place — no jump, no slop wait.
 * - Dragging anywhere on the 48 dp end strip jumps the thumb under the finger, then scrubs.
 * - Completely hidden when all items fit within the viewport.
 */
@Composable
fun Modifier.verticalScrollbar(
    state: LazyListState,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    activeColor: Color = MaterialTheme.colorScheme.primary,
    thickness: Dp = 6.dp,
    activeThickness: Dp = 10.dp,
    autoHideDelayMs: Long = 900L,
    endPadding: Dp = 4.dp,
    verticalPadding: Dp = 4.dp,
): Modifier = verticalScrollbar(
    adapter = rememberScrollbarAdapter(state),
    color = color,
    activeColor = activeColor,
    thickness = thickness,
    activeThickness = activeThickness,
    autoHideDelayMs = autoHideDelayMs,
    endPadding = endPadding,
    verticalPadding = verticalPadding,
)

/** [verticalScrollbar] overload for [LazyGridState]. */
@Composable
fun Modifier.verticalScrollbar(
    state: LazyGridState,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    activeColor: Color = MaterialTheme.colorScheme.primary,
    thickness: Dp = 6.dp,
    activeThickness: Dp = 10.dp,
    autoHideDelayMs: Long = 900L,
    endPadding: Dp = 4.dp,
    verticalPadding: Dp = 4.dp,
): Modifier = verticalScrollbar(
    adapter = rememberScrollbarAdapter(state),
    color = color,
    activeColor = activeColor,
    thickness = thickness,
    activeThickness = activeThickness,
    autoHideDelayMs = autoHideDelayMs,
    endPadding = endPadding,
    verticalPadding = verticalPadding,
)

/** [verticalScrollbar] overload for [ScrollState]. */
@Composable
fun Modifier.verticalScrollbar(
    state: ScrollState,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    activeColor: Color = MaterialTheme.colorScheme.primary,
    thickness: Dp = 6.dp,
    activeThickness: Dp = 10.dp,
    autoHideDelayMs: Long = 900L,
    endPadding: Dp = 4.dp,
    verticalPadding: Dp = 4.dp,
): Modifier = verticalScrollbar(
    adapter = rememberScrollbarAdapter(state),
    color = color,
    activeColor = activeColor,
    thickness = thickness,
    activeThickness = activeThickness,
    autoHideDelayMs = autoHideDelayMs,
    endPadding = endPadding,
    verticalPadding = verticalPadding,
)

/**
 * Core [verticalScrollbar] implementation driven by [ScrollbarAdapter].
 *
 * The thumb rests at a faint [IDLE_ALPHA] so it can always be found and grabbed, and
 * brightens to full strength while scrolling or dragging.
 */
@Composable
fun Modifier.verticalScrollbar(
    adapter: ScrollbarAdapter,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    activeColor: Color = MaterialTheme.colorScheme.primary,
    thickness: Dp = 6.dp,
    activeThickness: Dp = 10.dp,
    autoHideDelayMs: Long = 900L,
    endPadding: Dp = 4.dp,
    verticalPadding: Dp = 4.dp,
): Modifier {
    val coroutineScope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val isInteracting = adapter.isScrollInProgress || isDragging
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(isInteracting) {
        if (isInteracting) {
            alpha.animateTo(1f, tween(durationMillis = 120))
        } else {
            // Skip the delay on first composition so the resting thumb appears straight away.
            if (alpha.value > 0f) delay(autoHideDelayMs)
            alpha.animateTo(IDLE_ALPHA, tween(durationMillis = 320, easing = FastOutSlowInEasing))
        }
    }

    val currentThickness by animateDpAsState(
        targetValue = if (isDragging) activeThickness else thickness,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "scrollbarThickness",
    )

    val currentColor by animateColorAsState(
        targetValue = if (isDragging) activeColor else color,
        animationSpec = tween(durationMillis = 150),
        label = "scrollbarColor",
    )

    val layoutDirection = LocalLayoutDirection.current

    return this
        .pointerInput(adapter) {
            val touchSlop = viewConfiguration.touchSlop
            val hitAreaWidthPx = HIT_AREA_WIDTH.toPx()
            val grabMarginPx = GRAB_MARGIN.toPx()
            val minThumbPx = MIN_THUMB_HEIGHT.toPx()
            val thumbGapPx = THUMB_TRACK_GAP.toPx()
            val vPadPx = verticalPadding.toPx()

            awaitEachGesture {
                // Intercept in Initial pass so the container never scrolls under a grabbed thumb
                val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)

                val viewportHeight = size.height.toFloat() - vPadPx * 2
                if (viewportHeight <= 0f || !adapter.canScroll) {
                    return@awaitEachGesture
                }

                val isLtr = layoutDirection == LayoutDirection.Ltr
                val inStrip = if (isLtr) {
                    down.position.x >= size.width - hitAreaWidthPx
                } else {
                    down.position.x <= hitAreaWidthPx
                }

                if (!inStrip) {
                    return@awaitEachGesture
                }

                val thumbHeightPx = (viewportHeight * adapter.visibleRatio)
                    .coerceIn(minThumbPx, (viewportHeight - thumbGapPx).coerceAtLeast(minThumbPx))
                val trackHeight = (viewportHeight - thumbHeightPx).coerceAtLeast(0f)
                val thumbOffsetY = vPadPx + adapter.scrollProgress * trackHeight

                // Pressing the thumb (or just beside it) grabs it in place; pressing the empty
                // track re-centres the thumb under the finger once the drag is confirmed.
                val onThumb = down.position.y >= thumbOffsetY - grabMarginPx &&
                    down.position.y <= thumbOffsetY + thumbHeightPx + grabMarginPx
                val grabOffset = if (onThumb) {
                    (down.position.y - thumbOffsetY).coerceIn(0f, thumbHeightPx)
                } else {
                    thumbHeightPx / 2f
                }

                var dragStarted = false
                var lastProgress = adapter.scrollProgress
                var dragJob: Job? = null

                fun seekTo(y: Float) {
                    if (trackHeight <= 0f) return
                    val targetThumbY = (y - vPadPx - grabOffset).coerceIn(0f, trackHeight)
                    val progress = targetThumbY / trackHeight
                    if (abs(progress - lastProgress) < 0.0002f) return
                    lastProgress = progress
                    dragProgress = progress
                    dragJob?.cancel()
                    dragJob = coroutineScope.launch {
                        adapter.scrollToProgress(progress)
                    }
                }

                if (onThumb) {
                    // Grab immediately — no touch-slop wait, no jump to the finger.
                    down.consume()
                    dragProgress = lastProgress
                    dragStarted = true
                    isDragging = true
                    coroutineScope.launch { alpha.snapTo(1f) }
                }

                try {
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (!change.pressed) {
                            break
                        }

                        if (!dragStarted) {
                            val deltaY = change.position.y - down.position.y
                            val deltaX = change.position.x - down.position.x

                            // Detect intentional vertical drag along the scrollbar track
                            if (abs(deltaY) >= touchSlop && abs(deltaY) > abs(deltaX)) {
                                dragStarted = true
                                isDragging = true
                                coroutineScope.launch { alpha.snapTo(1f) }
                                change.consume()
                                seekTo(change.position.y)
                            }
                        } else {
                            // Drag is active: consume in Initial pass so container scrollable doesn't compete
                            change.consume()
                            seekTo(change.position.y)
                        }
                    }
                } finally {
                    // The last seek is left to finish: cancelling it here would swallow short drags.
                    if (dragStarted) {
                        isDragging = false
                    }
                }
            }
        }
        .drawWithContent {
            drawContent()

            val currentAlpha = alpha.value
            if (currentAlpha <= 0.01f || !adapter.canScroll) return@drawWithContent

            val vPadPx = verticalPadding.toPx()
            val viewportHeight = size.height - vPadPx * 2
            if (viewportHeight <= 0f) return@drawWithContent

            val minThumbPx = MIN_THUMB_HEIGHT.toPx()
            val thumbHeightPx = (viewportHeight * adapter.visibleRatio)
                .coerceIn(minThumbPx, (viewportHeight - THUMB_TRACK_GAP.toPx()).coerceAtLeast(minThumbPx))
            val trackHeight = (viewportHeight - thumbHeightPx).coerceAtLeast(0f)

            val progress = if (isDragging) dragProgress else adapter.scrollProgress
            val thumbOffsetY = vPadPx + progress * trackHeight

            val isLtr = layoutDirection == LayoutDirection.Ltr
            val thumbWidthPx = currentThickness.toPx()
            val thumbOffsetX = if (isLtr) {
                size.width - endPadding.toPx() - thumbWidthPx
            } else {
                endPadding.toPx()
            }

            // Draw a subtle track background while actively dragging for visual guiding
            if (isDragging) {
                drawRoundRect(
                    color = currentColor.copy(alpha = 0.12f),
                    topLeft = Offset(thumbOffsetX, vPadPx),
                    size = Size(thumbWidthPx, viewportHeight),
                    cornerRadius = CornerRadius(thumbWidthPx / 2f, thumbWidthPx / 2f),
                    alpha = currentAlpha,
                )
            }

            drawRoundRect(
                color = currentColor,
                topLeft = Offset(thumbOffsetX, thumbOffsetY),
                size = Size(thumbWidthPx, thumbHeightPx),
                cornerRadius = CornerRadius(thumbWidthPx / 2f, thumbWidthPx / 2f),
                alpha = currentAlpha,
            )
        }
}

/**
 * Standalone vertical scrollbar composable that can be positioned explicitly in a [Box].
 */
@Composable
fun HzVerticalScrollbar(
    adapter: ScrollbarAdapter,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    activeColor: Color = MaterialTheme.colorScheme.primary,
    thickness: Dp = 6.dp,
    activeThickness: Dp = 10.dp,
    autoHideDelayMs: Long = 900L,
    endPadding: Dp = 4.dp,
    verticalPadding: Dp = 4.dp,
) {
    Box(
        modifier = modifier
            .verticalScrollbar(
                adapter = adapter,
                color = color,
                activeColor = activeColor,
                thickness = thickness,
                activeThickness = activeThickness,
                autoHideDelayMs = autoHideDelayMs,
                endPadding = endPadding,
                verticalPadding = verticalPadding,
            )
    )
}

/** [HzVerticalScrollbar] overload for [LazyListState]. */
@Composable
fun HzVerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    activeColor: Color = MaterialTheme.colorScheme.primary,
    thickness: Dp = 6.dp,
    activeThickness: Dp = 10.dp,
    autoHideDelayMs: Long = 900L,
    endPadding: Dp = 4.dp,
    verticalPadding: Dp = 4.dp,
) {
    HzVerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier,
        color = color,
        activeColor = activeColor,
        thickness = thickness,
        activeThickness = activeThickness,
        autoHideDelayMs = autoHideDelayMs,
        endPadding = endPadding,
        verticalPadding = verticalPadding,
    )
}

/** [HzVerticalScrollbar] overload for [LazyGridState]. */
@Composable
fun HzVerticalScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    activeColor: Color = MaterialTheme.colorScheme.primary,
    thickness: Dp = 6.dp,
    activeThickness: Dp = 10.dp,
    autoHideDelayMs: Long = 900L,
    endPadding: Dp = 4.dp,
    verticalPadding: Dp = 4.dp,
) {
    HzVerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier,
        color = color,
        activeColor = activeColor,
        thickness = thickness,
        activeThickness = activeThickness,
        autoHideDelayMs = autoHideDelayMs,
        endPadding = endPadding,
        verticalPadding = verticalPadding,
    )
}

/**
 * Container that hosts scrollable content and overlays a draggable [HzVerticalScrollbar] along the end edge.
 */
@Composable
fun HzScrollbarBox(
    adapter: ScrollbarAdapter,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    activeColor: Color = MaterialTheme.colorScheme.primary,
    thickness: Dp = 6.dp,
    activeThickness: Dp = 10.dp,
    autoHideDelayMs: Long = 900L,
    endPadding: Dp = 4.dp,
    verticalPadding: Dp = 4.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        content()
        HzVerticalScrollbar(
            adapter = adapter,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            color = color,
            activeColor = activeColor,
            thickness = thickness,
            activeThickness = activeThickness,
            autoHideDelayMs = autoHideDelayMs,
            endPadding = endPadding,
            verticalPadding = verticalPadding,
        )
    }
}

/** [HzScrollbarBox] overload for [LazyListState]. */
@Composable
fun HzScrollbarBox(
    state: LazyListState,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    activeColor: Color = MaterialTheme.colorScheme.primary,
    thickness: Dp = 6.dp,
    activeThickness: Dp = 10.dp,
    autoHideDelayMs: Long = 900L,
    endPadding: Dp = 4.dp,
    verticalPadding: Dp = 4.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    HzScrollbarBox(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier,
        color = color,
        activeColor = activeColor,
        thickness = thickness,
        activeThickness = activeThickness,
        autoHideDelayMs = autoHideDelayMs,
        endPadding = endPadding,
        verticalPadding = verticalPadding,
        content = content,
    )
}

@PreviewLightDark
@Preview
@Composable
private fun ScrollbarPreview() {
    HzPlayerTheme {
        val listState = rememberLazyListState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScrollbar(listState)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(50) { index ->
                    Text(
                        text = "Preview Item #$index",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}
