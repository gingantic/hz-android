package com.rhnxdev.hzplayer.presentation.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(
                start = Spacing.lg,
                bottom = Spacing.sm,
            ),
        )
        content()
    }
}

@PreviewLightDark
@Preview
@Composable
private fun SettingsSectionPreview() {
    HzPlayerTheme {
        SettingsSection(title = "Video") {
            Text(
                text = "Settings content here",
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        }
    }
}
