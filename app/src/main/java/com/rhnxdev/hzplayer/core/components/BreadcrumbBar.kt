package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.core.designsystem.Spacing

data class BreadcrumbItem(
    val name: String,
    val path: String,
)

@Composable
fun BreadcrumbBar(
    breadcrumbs: List<BreadcrumbItem>,
    onBreadcrumbClicked: (String) -> Unit,
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    chevronColor: Color = secondaryColor.copy(alpha = 0.5f),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        breadcrumbs.forEachIndexed { index, crumb ->
            val isLast = index == breadcrumbs.lastIndex

            Text(
                text = crumb.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (isLast) primaryColor else secondaryColor,
                modifier = if (!isLast) Modifier.clickable { onBreadcrumbClicked(crumb.path) }
                else Modifier,
            )

            if (!isLast) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .width(16.dp)
                        .height(16.dp),
                    tint = chevronColor,
                )
            }
        }
    }
}
