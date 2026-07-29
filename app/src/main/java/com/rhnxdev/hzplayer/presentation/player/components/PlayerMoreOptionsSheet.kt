package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.RepeatOneOn
import androidx.compose.material.icons.filled.Toc
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.util.formatDuration
import com.rhnxdev.hzplayer.domain.model.ChapterInfo
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import kotlinx.coroutines.flow.StateFlow

/**
 * "More options" bottom sheet for the video player: sleep timer, jump-to-time,
 * chapter navigation, repeat mode and play-as-audio. Each row either fires a
 * direct action (repeat cycle, play as audio) or opens a follow-up dialog
 * owned by [VideoPlayerScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerMoreOptionsSheet(
    repeatMode: RepeatMode,
    /** 1 Hz countdown; collected only inside the sleep-timer row's value label. */
    sleepTimerRemainingFlow: StateFlow<Long>,
    chapterCount: Int,
    onSleepTimerClick: () -> Unit,
    onJumpToClick: () -> Unit,
    onChaptersClick: () -> Unit,
    onCycleRepeat: () -> Unit,
    /** A-B loop points; drive the row's status label (null = idle). */
    abLoopStartMs: Long?,
    abLoopEndMs: Long?,
    onCycleAbRepeat: () -> Unit,
    onPlayAsAudio: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetScaffold(
        title = stringResource(R.string.more_options),
        icon = Icons.Default.MoreVert,
        onDismiss = onDismiss,
    ) {
        // Scrollable: in landscape the capped sheet height clips the last rows.
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            MoreOptionRow(
                icon = Icons.Default.Bedtime,
                label = stringResource(R.string.sleep_timer),
                value = { SleepTimerValueLabel(sleepTimerRemainingFlow) },
                onClick = onSleepTimerClick,
            )
            MoreOptionRow(
                icon = Icons.Default.AccessTime,
                label = stringResource(R.string.jump_to_time),
                onClick = onJumpToClick,
            )
            MoreOptionRow(
                icon = Icons.Default.Toc,
                label = stringResource(R.string.chapters),
                value = {
                    ValueText(
                        if (chapterCount > 0) chapterCount.toString()
                        else stringResource(R.string.no_chapters),
                    )
                },
                enabled = chapterCount > 0,
                onClick = onChaptersClick,
            )
            MoreOptionRow(
                icon = when (repeatMode) {
                    RepeatMode.NONE -> Icons.Default.Repeat
                    RepeatMode.ALL -> Icons.Default.RepeatOn
                    RepeatMode.ONE -> Icons.Default.RepeatOneOn
                },
                label = stringResource(R.string.repeat),
                value = {
                    ValueText(
                        stringResource(
                            when (repeatMode) {
                                RepeatMode.NONE -> R.string.repeat_mode_off
                                RepeatMode.ALL -> R.string.repeat_mode_all
                                RepeatMode.ONE -> R.string.repeat_mode_one
                            },
                        ),
                    )
                },
                onClick = onCycleRepeat,
            )
            MoreOptionRow(
                icon = Icons.Default.Loop,
                label = stringResource(R.string.ab_repeat_loop),
                value = {
                    ValueText(
                        stringResource(
                            when {
                                abLoopEndMs != null -> R.string.ab_repeat_looping
                                abLoopStartMs != null -> R.string.ab_repeat_point_a
                                else -> R.string.repeat_mode_off
                            },
                        ),
                    )
                },
                onClick = onCycleAbRepeat,
            )
            MoreOptionRow(
                icon = Icons.Default.Headphones,
                label = stringResource(R.string.play_as_audio),
                onClick = onPlayAsAudio,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** Collects the countdown flow so its 1 Hz tick recomposes only this label. */
@Composable
private fun SleepTimerValueLabel(remainingFlow: StateFlow<Long>) {
    val remainingMs by remainingFlow.collectAsStateWithLifecycle()
    ValueText(
        if (remainingMs > 0) {
            stringResource(R.string.sleep_timer_remaining, formatDuration(remainingMs))
        } else {
            stringResource(R.string.sleep_timer_off)
        },
    )
}

@Composable
private fun ValueText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun MoreOptionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    value: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f * contentAlpha),
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            modifier = Modifier.weight(1f),
        )
        value?.invoke()
    }
}

/**
 * Sleep-timer picker: fixed presets, an "end of video" mode, and Off. Shows the
 * live countdown in the header row while a timer is armed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerDialog(
    sleepTimerRemainingFlow: StateFlow<Long>,
    /** Called with a duration in ms, `SLEEP_TIMER_END_OF_VIDEO` (-1), or 0 for Off. */
    onSetTimer: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val remainingMs by sleepTimerRemainingFlow.collectAsStateWithLifecycle()
    val presetsMin = remember { listOf(15, 30, 45, 60, 90, 120) }
    SheetScaffold(
        title = stringResource(R.string.sleep_timer),
        icon = Icons.Default.Bedtime,
        onDismiss = onDismiss,
    ) {
        // Scrollable: eight rows never fit the capped sheet height in landscape.
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            if (remainingMs > 0) {
                Text(
                    text = stringResource(R.string.sleep_timer_remaining, formatDuration(remainingMs)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                )
            }
            SleepTimerRow(
                label = stringResource(R.string.sleep_timer_off),
                selected = remainingMs <= 0,
            ) {
                onSetTimer(0L)
                onDismiss()
            }
            presetsMin.forEach { minutes ->
                SleepTimerRow(
                    label = stringResource(R.string.sleep_timer_minutes, minutes),
                    selected = false,
                ) {
                    onSetTimer(minutes * 60_000L)
                    onDismiss()
                }
            }
            SleepTimerRow(
                label = stringResource(R.string.sleep_timer_end_of_video),
                selected = false,
            ) {
                onSetTimer(-1L)
                onDismiss()
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SleepTimerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * "Jump to position" with a built-in numpad. Digits fill in from the right,
 * microwave style: the last two digits are seconds, the two before them are
 * minutes, and anything further shifts into hours (e.g. typing `230` → 2:30).
 * The sheet is fixed-height — nothing scrolls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JumpToTimeDialog(
    durationMs: Long,
    onJump: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var digits by rememberSaveable { mutableStateOf("") }
    val padded = digits.padStart(6, '0')
    val targetMs = (padded.substring(0, 2).toLong() * 3600 +
        padded.substring(2, 4).toLong() * 60 +
        padded.substring(4, 6).toLong()) * 1000L
    val onKey: (Char) -> Unit = { c ->
        // Max 6 digits (HH:MM:SS); a leading zero is a no-op.
        if (digits.length < 6 && !(digits.isEmpty() && c == '0')) digits += c
    }
    val submit = {
        onJump(targetMs.coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE))
        onDismiss()
    }
    SheetScaffold(
        title = stringResource(R.string.jump_to_time),
        icon = Icons.Default.AccessTime,
        onDismiss = onDismiss,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── HH:MM:SS display: typed digits highlighted from the right ──
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val typedColor = MaterialTheme.colorScheme.onSurface
                val dimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                val typedFrom = 6 - digits.length
                Text(
                    text = buildAnnotatedString {
                        for (i in 0 until 6) {
                            val color = if (i >= typedFrom) typedColor else dimColor
                            withStyle(SpanStyle(color = color)) { append(padded[i]) }
                            if (i == 1 || i == 3) {
                                withStyle(SpanStyle(color = color)) { append(':') }
                            }
                        }
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${formatDuration(targetMs)} / ${formatDuration(durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }

            Spacer(modifier = Modifier.width(20.dp))

            // ── Built-in numpad ──
            Column(
                modifier = Modifier.width(228.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("123", "456", "789").forEach { rowKeys ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowKeys.forEach { c ->
                            NumpadKey(onClick = { onKey(c) }) {
                                Text(
                                    text = c.toString(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumpadKey(
                        enabled = digits.isNotEmpty(),
                        onClick = { digits = digits.dropLast(1) },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Backspace,
                            contentDescription = stringResource(R.string.backspace),
                            tint = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (digits.isNotEmpty()) 0.8f else 0.3f,
                            ),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    NumpadKey(onClick = { onKey('0') }) {
                        Text(
                            text = "0",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    NumpadKey(
                        enabled = digits.isNotEmpty(),
                        accent = true,
                        onClick = submit,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.jump),
                            tint = if (digits.isNotEmpty()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun RowScope.NumpadKey(
    onClick: () -> Unit,
    enabled: Boolean = true,
    accent: Boolean = false,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .weight(1f)
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    accent && enabled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                    enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)
                },
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * Chapter list for the current video. The row of the chapter containing the
 * playback position is highlighted; tapping a row seeks to its start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterSelectionDialog(
    chapters: List<ChapterInfo>,
    /** High-frequency position; collected once here to pick the active chapter. */
    positionFlow: StateFlow<Long>,
    onChapterSelected: (ChapterInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val position by positionFlow.collectAsStateWithLifecycle()
    val currentIndex = chapters.indexOfLast { position >= it.startMs }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (currentIndex > 0) listState.scrollToItem(currentIndex)
    }
    SheetScaffold(
        title = stringResource(R.string.chapters),
        icon = Icons.Default.Toc,
        onDismiss = onDismiss,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(vertical = 8.dp),
        ) {
            items(chapters) { chapter ->
                val index = chapters.indexOf(chapter)
                val isCurrent = index == currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onChapterSelected(chapter)
                            onDismiss()
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = chapter.title.ifBlank {
                            stringResource(R.string.chapter_fallback, index + 1)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = formatDuration(chapter.startMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}
