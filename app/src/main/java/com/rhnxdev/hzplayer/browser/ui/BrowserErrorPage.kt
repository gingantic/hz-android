package com.rhnxdev.hzplayer.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.browser.BrowserError
import com.rhnxdev.hzplayer.browser.BrowserErrorCategory
import com.rhnxdev.hzplayer.browser.BrowserErrorKind
import com.rhnxdev.hzplayer.browser.RetryPolicy
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

/**
 * The page shown in place of a tab's content when it can't be displayed.
 *
 * One layout serves every [BrowserErrorKind]: icon, title, message and the
 * recovery actions all come from the kind, so a new error needs no UI work.
 * Opaque and click-absorbing, so the failed WebView underneath is never
 * visible or tappable.
 *
 * @param error — what failed
 * @param autoRetry — the page reloads itself when the network returns, so say so
 * @param onRetry — try the page again; offered only when the kind's [RetryPolicy] allows it
 */
@Composable
fun BrowserErrorPage(
    error: BrowserError,
    autoRetry: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = error.kind
    val (container, content) = errorColors(kind.category)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Absorb touches so they never reach the WebView underneath.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = errorIcon(kind.category),
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(36.dp),
                )
            }

            Spacer(Modifier.height(Spacing.lg))

            Text(
                text = stringResource(kind.titleRes),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            error.host?.let { host ->
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = host,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(Spacing.sm))

            Text(
                text = stringResource(kind.messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            error.detail?.let { detail ->
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = stringResource(R.string.browser_error_code, detail),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }

            if (kind.retry != RetryPolicy.NONE) {
                Spacer(Modifier.height(Spacing.xl))
                Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

/** Container and glyph colours for a category's icon badge. */
@Composable
private fun errorColors(category: BrowserErrorCategory): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (category) {
        BrowserErrorCategory.NETWORK -> c.primaryContainer to c.onPrimaryContainer
        BrowserErrorCategory.SECURITY,
        BrowserErrorCategory.BROWSER -> c.errorContainer to c.onErrorContainer
        BrowserErrorCategory.SERVER,
        BrowserErrorCategory.CONTENT -> c.secondaryContainer to c.onSecondaryContainer
    }
}

private fun errorIcon(category: BrowserErrorCategory): ImageVector = when (category) {
    BrowserErrorCategory.NETWORK -> Icons.Default.WifiOff
    BrowserErrorCategory.SECURITY -> Icons.Default.GppBad
    BrowserErrorCategory.SERVER -> Icons.Default.CloudOff
    BrowserErrorCategory.CONTENT -> Icons.Default.LinkOff
    BrowserErrorCategory.BROWSER -> Icons.Default.ErrorOutline
}

/**
 * Text for a [BrowserError] shown as a brief notice rather than a page.
 * Security notices name the site (`Blocked example.com — reason`), others
 * are just the message.
 */
@Composable
fun BrowserError.noticeText(): String {
    val message = stringResource(kind.messageRes)
    val host = host
    return when {
        kind.category != BrowserErrorCategory.SECURITY -> message
        host != null -> stringResource(R.string.browser_ssl_blocked, host, message)
        else -> stringResource(R.string.browser_ssl_blocked_this_site, message)
    }
}

@PreviewLightDark
@Preview
@Composable
private fun BrowserErrorPageOfflinePreview() {
    HzPlayerTheme {
        BrowserErrorPage(
            error = BrowserError(
                kind = BrowserErrorKind.OFFLINE,
                url = "https://example.com/watch",
                detail = "net::ERR_INTERNET_DISCONNECTED",
            ),
            autoRetry = true,
            onRetry = {},
        )
    }
}

@PreviewLightDark
@Preview
@Composable
private fun BrowserErrorPageCertificatePreview() {
    HzPlayerTheme {
        BrowserErrorPage(
            error = BrowserError(
                kind = BrowserErrorKind.CERT_EXPIRED,
                url = "https://expired.example.com",
            ),
            autoRetry = false,
            onRetry = {},
        )
    }
}
