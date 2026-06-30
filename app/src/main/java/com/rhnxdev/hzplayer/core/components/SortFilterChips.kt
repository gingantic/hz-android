package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

data class SortChipOption(
    val label: String,
    val value: Any,
)

@Composable
fun SortFilterChips(
    options: List<SortChipOption>,
    selectedValue: Any,
    onOptionSelected: (SortChipOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option.value == selectedValue,
                onClick = { onOptionSelected(option) },
                label = {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun SortFilterChipsPreview() {
    val options = listOf(
        SortChipOption("Title", "title"),
        SortChipOption("Date", "date"),
        SortChipOption("Duration", "duration"),
        SortChipOption("Size", "size"),
    )

    HzPlayerTheme {
        SortFilterChips(
            options = options,
            selectedValue = "title",
            onOptionSelected = {},
        )
    }
}
