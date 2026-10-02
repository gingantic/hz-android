package com.rhnxdev.hzplayer.browser

import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient

/** Kind of JavaScript dialog a page requested. */
enum class JsDialogType { ALERT, CONFIRM, PROMPT, BEFORE_UNLOAD }

/** Carries the WebView result callback that must be confirmed/cancelled to unblock the page. */
sealed interface JsDialogResult {
    data class Confirm(val result: JsResult) : JsDialogResult
    data class Prompt(val result: JsPromptResult) : JsDialogResult
}

/**
 * A pending JavaScript dialog (alert/confirm/prompt/beforeunload) awaiting the
 * user. The page's JS thread is blocked until [TabManager.resolveJsDialog] is
 * called, so the UI must always resolve it.
 *
 * @param url     origin page URL, shown so the user knows who is asking
 * @param message the dialog body text from the page
 * @param defaultValue prompt pre-fill (prompt only; empty otherwise)
 */
data class JsDialogRequest(
    val type: JsDialogType,
    val url: String,
    val message: String,
    val defaultValue: String = "",
    val result: JsDialogResult,
)

/**
 * A pending `<input type="file">` picker request. The host Activity launches the
 * system chooser and returns the result via [TabManager.deliverFileChooserResult].
 *
 * @param acceptTypes MIME types / extensions the input accepts (may be empty = any)
 * @param allowMultiple true when the input has the `multiple` attribute
 * @param captureEnabled true when the input hints at camera/mic capture
 */
data class FileChooserRequest(
    val acceptTypes: List<String>,
    val allowMultiple: Boolean,
    val captureEnabled: Boolean,
    val params: WebChromeClient.FileChooserParams,
)
