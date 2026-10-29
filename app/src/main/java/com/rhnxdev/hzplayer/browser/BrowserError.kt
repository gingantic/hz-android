package com.rhnxdev.hzplayer.browser

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.rhnxdev.hzplayer.R

/** Groups kinds for presentation (icon, tint); has no behaviour of its own. */
enum class BrowserErrorCategory { NETWORK, SECURITY, SERVER, CONTENT, BROWSER }

/** What the error page offers for recovery. */
enum class RetryPolicy {
    /** Reloading cannot help (bad certificate, unsupported link): no Retry button. */
    NONE,

    /** Retry button only. */
    MANUAL,

    /** Retry button, and the page reloads by itself when the network comes back. */
    ON_RECONNECT,
}

/**
 * Every reason the browser can fail to show a page, or to act on a link.
 *
 * This is the single vocabulary for browser errors: WebView codes are mapped
 * into it once, by [BrowserErrorMapper], and the UI reads only this. Adding a
 * case here (with its two strings) is all a new error needs.
 *
 * @param titleRes — short headline
 * @param messageRes — one-sentence explanation, no placeholders
 * @param retry — recovery the error page offers
 */
enum class BrowserErrorKind(
    val category: BrowserErrorCategory,
    @StringRes val titleRes: Int,
    @StringRes val messageRes: Int,
    val retry: RetryPolicy,
) {
    // ── Network ─────────────────────────────────────────────────
    OFFLINE(
        BrowserErrorCategory.NETWORK,
        R.string.browser_error_title_offline, R.string.browser_error_msg_offline,
        RetryPolicy.ON_RECONNECT,
    ),
    DNS_FAILURE(
        BrowserErrorCategory.NETWORK,
        R.string.browser_error_title_dns, R.string.browser_error_msg_dns,
        RetryPolicy.ON_RECONNECT,
    ),
    CONNECTION_FAILED(
        BrowserErrorCategory.NETWORK,
        R.string.browser_error_title_connect, R.string.browser_error_msg_connect,
        RetryPolicy.ON_RECONNECT,
    ),
    TIMEOUT(
        BrowserErrorCategory.NETWORK,
        R.string.browser_error_title_timeout, R.string.browser_error_msg_timeout,
        RetryPolicy.ON_RECONNECT,
    ),
    REDIRECT_LOOP(
        BrowserErrorCategory.NETWORK,
        R.string.browser_error_title_redirect, R.string.browser_error_msg_redirect,
        RetryPolicy.MANUAL,
    ),
    /** Cache-only mode is on and the page was never cached. Retrying cannot help. */
    NOT_CACHED(
        BrowserErrorCategory.NETWORK,
        R.string.browser_error_title_not_cached, R.string.browser_error_msg_not_cached,
        RetryPolicy.NONE,
    ),

    // ── Security: certificates (reasons reuse the existing SSL strings) ──
    CERT_EXPIRED(
        BrowserErrorCategory.SECURITY,
        R.string.browser_error_title_cert, R.string.browser_ssl_reason_expired,
        RetryPolicy.NONE,
    ),
    CERT_MISMATCH(
        BrowserErrorCategory.SECURITY,
        R.string.browser_error_title_cert, R.string.browser_ssl_reason_mismatch,
        RetryPolicy.NONE,
    ),
    CERT_NOT_YET_VALID(
        BrowserErrorCategory.SECURITY,
        R.string.browser_error_title_cert, R.string.browser_ssl_reason_not_yet_valid,
        RetryPolicy.NONE,
    ),
    CERT_UNTRUSTED(
        BrowserErrorCategory.SECURITY,
        R.string.browser_error_title_cert, R.string.browser_ssl_reason_untrusted,
        RetryPolicy.NONE,
    ),
    CERT_DATE_INVALID(
        BrowserErrorCategory.SECURITY,
        R.string.browser_error_title_cert, R.string.browser_ssl_reason_date_invalid,
        RetryPolicy.NONE,
    ),
    CERT_INVALID(
        BrowserErrorCategory.SECURITY,
        R.string.browser_error_title_cert, R.string.browser_ssl_reason_invalid,
        RetryPolicy.NONE,
    ),
    /** The handshake failed and no certificate detail was reported. */
    SSL_HANDSHAKE(
        BrowserErrorCategory.SECURITY,
        R.string.browser_error_title_cert, R.string.browser_ssl_reason_generic,
        RetryPolicy.MANUAL,
    ),

    // ── Security: unsafe content ────────────────────────────────
    // WebView's own Safe Browsing interstitial (malware / phishing) is kept as
    // is: it is native, localized and security-critical. This covers the
    // remaining case, a load refused as unsafe without that interstitial.
    UNSAFE_CONTENT(
        BrowserErrorCategory.SECURITY,
        R.string.browser_error_title_threat, R.string.browser_error_msg_threat,
        RetryPolicy.NONE,
    ),

    // ── Server / HTTP ───────────────────────────────────────────
    NOT_FOUND(
        BrowserErrorCategory.SERVER,
        R.string.browser_error_title_not_found, R.string.browser_error_msg_not_found,
        RetryPolicy.MANUAL,
    ),
    FORBIDDEN(
        BrowserErrorCategory.SERVER,
        R.string.browser_error_title_forbidden, R.string.browser_error_msg_forbidden,
        RetryPolicy.NONE,
    ),
    AUTH_REQUIRED(
        BrowserErrorCategory.SERVER,
        R.string.browser_error_title_auth, R.string.browser_error_msg_auth,
        RetryPolicy.NONE,
    ),
    RATE_LIMITED(
        BrowserErrorCategory.SERVER,
        R.string.browser_error_title_rate_limited, R.string.browser_error_msg_rate_limited,
        RetryPolicy.MANUAL,
    ),
    SERVER_ERROR(
        BrowserErrorCategory.SERVER,
        R.string.browser_error_title_server, R.string.browser_error_msg_server,
        RetryPolicy.MANUAL,
    ),
    CLIENT_ERROR(
        BrowserErrorCategory.SERVER,
        R.string.browser_error_title_client, R.string.browser_error_msg_client,
        RetryPolicy.MANUAL,
    ),

    // ── Content / address ───────────────────────────────────────
    BAD_URL(
        BrowserErrorCategory.CONTENT,
        R.string.browser_error_title_bad_url, R.string.browser_error_msg_bad_url,
        RetryPolicy.NONE,
    ),
    UNSUPPORTED_SCHEME(
        BrowserErrorCategory.CONTENT,
        R.string.browser_error_title_link, R.string.browser_error_msg_scheme,
        RetryPolicy.NONE,
    ),
    FILE_ERROR(
        BrowserErrorCategory.CONTENT,
        R.string.browser_error_title_file, R.string.browser_error_msg_file,
        RetryPolicy.NONE,
    ),
    /** A WebView failure with no more specific cause. */
    UNKNOWN(
        BrowserErrorCategory.CONTENT,
        R.string.browser_error_title_unknown, R.string.browser_error_msg_unknown,
        RetryPolicy.MANUAL,
    ),

    // ── Links handed to other apps (shown as a notice, not a page) ──
    NO_APP_FOR_LINK(
        BrowserErrorCategory.CONTENT,
        R.string.browser_error_title_link, R.string.browser_no_app_for_link,
        RetryPolicy.NONE,
    ),
    INTENT_LINK_BLOCKED(
        BrowserErrorCategory.CONTENT,
        R.string.browser_error_title_link, R.string.browser_intent_link_blocked,
        RetryPolicy.NONE,
    ),

    // ── Renderer ────────────────────────────────────────────────
    RENDERER_CRASHED(
        BrowserErrorCategory.BROWSER,
        R.string.browser_error_title_crashed, R.string.browser_error_msg_crashed,
        RetryPolicy.MANUAL,
    ),
    RENDERER_KILLED(
        BrowserErrorCategory.BROWSER,
        R.string.browser_error_title_killed, R.string.browser_error_msg_killed,
        RetryPolicy.MANUAL,
    ),
    /** The device has no usable WebView (provider missing, disabled or updating). */
    WEBVIEW_UNAVAILABLE(
        BrowserErrorCategory.BROWSER,
        R.string.browser_error_title_no_webview, R.string.browser_error_msg_no_webview,
        RetryPolicy.NONE,
    );

    /** True when the page's own WebView process is gone, so a reload needs a fresh WebView. */
    val isRendererFailure: Boolean
        get() = this == RENDERER_CRASHED || this == RENDERER_KILLED

    /** True for the specific certificate problems (not the generic [SSL_HANDSHAKE]). */
    val isCertificate: Boolean
        get() = when (this) {
            CERT_EXPIRED, CERT_MISMATCH, CERT_NOT_YET_VALID,
            CERT_UNTRUSTED, CERT_DATE_INVALID, CERT_INVALID -> true
            else -> false
        }
}

/**
 * One failure, ready for the UI. Carries data only: text is resolved from
 * [BrowserErrorKind] resources where it is drawn, never stored here.
 *
 * @param url — the address that failed (may be blank when none applies)
 * @param detail — short technical code safe to display, e.g. `net::ERR_NAME_NOT_RESOLVED` or `HTTP 404`
 */
@Immutable
data class BrowserError(
    val kind: BrowserErrorKind,
    val url: String = "",
    val detail: String? = null,
) {
    /**
     * Host of an http(s) [url], for display. Null for other schemes, so a
     * `tel:` or `market://` link never shows a bogus host.
     */
    val host: String?
        get() = runCatching { java.net.URI(url) }.getOrNull()
            ?.takeIf { it.scheme.equals("http", true) || it.scheme.equals("https", true) }
            ?.host
}
