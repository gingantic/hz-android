package com.rhnxdev.hzplayer.browser.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.browser.JsDialogRequest
import com.rhnxdev.hzplayer.browser.JsDialogType

/**
 * Material dialog for a page's JavaScript alert / confirm / prompt / beforeunload.
 * The page's JS thread is blocked until [onResult] fires, so dismissing (tap
 * outside / back) is treated as cancel to always unblock it.
 *
 * @param onResult (confirmed, promptInput) — promptInput is non-null only for prompt
 */
@Composable
fun JsDialog(
    request: JsDialogRequest?,
    onResult: (confirmed: Boolean, input: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (request == null) return

    var promptText by remember(request) { mutableStateOf(request.defaultValue) }

    val host = remember(request) {
        runCatching { android.net.Uri.parse(request.url).host }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    val pageTitle = host ?: stringResource(R.string.browser_js_this_page)
    val (title, confirmLabel) = when (request.type) {
        JsDialogType.BEFORE_UNLOAD ->
            stringResource(R.string.browser_js_leave_title) to stringResource(R.string.browser_js_leave_confirm)
        else -> pageTitle to stringResource(R.string.browser_ok)
    }

    val dismissLabel = when (request.type) {
        JsDialogType.ALERT         -> null            // alert has only one button
        JsDialogType.BEFORE_UNLOAD -> stringResource(R.string.browser_js_stay)
        else                       -> stringResource(R.string.dialog_cancel)
    }

    AlertDialog(
        onDismissRequest = { onResult(false, null) },
        modifier = modifier,
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val body = request.message.ifBlank {
                    if (request.type == JsDialogType.BEFORE_UNLOAD)
                        stringResource(R.string.browser_js_unsaved_changes) else ""
                }
                if (body.isNotBlank()) {
                    Text(text = body, style = MaterialTheme.typography.bodyMedium)
                }
                if (request.type == JsDialogType.PROMPT) {
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onResult(true, if (request.type == JsDialogType.PROMPT) promptText else null)
            }) { Text(confirmLabel) }
        },
        dismissButton = dismissLabel?.let {
            {
                TextButton(onClick = { onResult(false, null) }) { Text(it) }
            }
        },
    )
}
