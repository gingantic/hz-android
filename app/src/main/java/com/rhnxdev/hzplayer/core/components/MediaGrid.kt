package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.core.designsystem.Spacing

@Composable
fun MediaGrid(
    items: List<Any>,
    itemContent: @Composable (Any) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 2,
    contentPadding: PaddingValues = PaddingValues(Spacing.sm),
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(items, key = { it.hashCode() }) { item ->
            itemContent(item)
        }
    }
}
