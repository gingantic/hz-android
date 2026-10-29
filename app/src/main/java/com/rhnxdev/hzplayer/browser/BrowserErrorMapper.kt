package com.rhnxdev.hzplayer.browser

import android.net.http.SslError
import android.webkit.WebViewClient

/**
 * The only place raw WebView error signals become a [BrowserError].
 *
 * Every WebView callback that can fail a page (`onReceivedError`,
 * `onReceivedHttpError`, `onReceivedSslError`, `onRenderProcessGone`) goes
 * through one function here, so the codes are
 * interpreted in one place and the UI never sees them. Raw descriptions are
 * only kept when they are `net::` codes, which carry no URL or credentials.
 *
 * Only compile-time constants from android.* are used, so this runs in plain
 * JVM unit tests.
 */
object BrowserErrorMapper {

    /**
     * @param online — current connectivity; a connection failure while offline is reported as [BrowserErrorKind.OFFLINE]
     * @param description — `WebResourceError.getDescription()`, e.g. `net::ERR_NAME_NOT_RESOLVED`
     */
    fun fromWebResource(
        errorCode: Int,
        description: String?,
        url: String,
        online: Boolean,
    ): BrowserError {
        val netCode = description?.takeIf { it.startsWith("net::") }
        val disconnected = netCode?.contains("ERR_INTERNET_DISCONNECTED") == true
        val kind = when {
            // Cancelled certificate errors can arrive here as a generic handshake
            // failure; the net:: code still says which certificate problem it was.
            netCode?.contains("ERR_CERT_") == true -> certKindOf(netCode)
            netCode?.contains("ERR_CACHE_MISS") == true -> BrowserErrorKind.NOT_CACHED
            else -> kindOfCode(errorCode, online, disconnected)
        }
        return BrowserError(kind, url, netCode)
    }

    private fun certKindOf(netCode: String): BrowserErrorKind = when {
        netCode.contains("COMMON_NAME_INVALID") -> BrowserErrorKind.CERT_MISMATCH
        netCode.contains("DATE_INVALID") -> BrowserErrorKind.CERT_DATE_INVALID
        netCode.contains("AUTHORITY_INVALID") -> BrowserErrorKind.CERT_UNTRUSTED
        else -> BrowserErrorKind.CERT_INVALID
    }

    private fun kindOfCode(errorCode: Int, online: Boolean, disconnected: Boolean): BrowserErrorKind =
        when (errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_IO,
            WebViewClient.ERROR_TIMEOUT,
            WebViewClient.ERROR_UNKNOWN -> when {
                !online || disconnected -> BrowserErrorKind.OFFLINE
                errorCode == WebViewClient.ERROR_HOST_LOOKUP -> BrowserErrorKind.DNS_FAILURE
                errorCode == WebViewClient.ERROR_TIMEOUT -> BrowserErrorKind.TIMEOUT
                errorCode == WebViewClient.ERROR_UNKNOWN -> BrowserErrorKind.UNKNOWN
                else -> BrowserErrorKind.CONNECTION_FAILED
            }
            WebViewClient.ERROR_REDIRECT_LOOP -> BrowserErrorKind.REDIRECT_LOOP
            WebViewClient.ERROR_TOO_MANY_REQUESTS -> BrowserErrorKind.RATE_LIMITED
            WebViewClient.ERROR_UNSUPPORTED_AUTH_SCHEME,
            WebViewClient.ERROR_AUTHENTICATION,
            WebViewClient.ERROR_PROXY_AUTHENTICATION -> BrowserErrorKind.AUTH_REQUIRED
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> BrowserErrorKind.SSL_HANDSHAKE
            WebViewClient.ERROR_BAD_URL -> BrowserErrorKind.BAD_URL
            WebViewClient.ERROR_UNSUPPORTED_SCHEME -> BrowserErrorKind.UNSUPPORTED_SCHEME
            WebViewClient.ERROR_FILE,
            WebViewClient.ERROR_FILE_NOT_FOUND -> BrowserErrorKind.FILE_ERROR
            WebViewClient.ERROR_UNSAFE_RESOURCE -> BrowserErrorKind.UNSAFE_CONTENT
            else -> BrowserErrorKind.UNKNOWN
        }

    /** Null for statuses below 400, which are not errors. */
    fun fromHttpStatus(status: Int, url: String): BrowserError? {
        val kind = when (status) {
            in 0..399 -> return null
            401, 407 -> BrowserErrorKind.AUTH_REQUIRED
            403 -> BrowserErrorKind.FORBIDDEN
            404, 410 -> BrowserErrorKind.NOT_FOUND
            408 -> BrowserErrorKind.TIMEOUT
            429 -> BrowserErrorKind.RATE_LIMITED
            in 400..499 -> BrowserErrorKind.CLIENT_ERROR
            in 500..599 -> BrowserErrorKind.SERVER_ERROR
            else -> BrowserErrorKind.UNKNOWN
        }
        return BrowserError(kind, url, "HTTP $status")
    }

    /** @param primaryError — an `SslError.SSL_*` code, null when WebView reported none */
    fun fromSsl(primaryError: Int?, url: String): BrowserError {
        val kind = when (primaryError) {
            SslError.SSL_EXPIRED -> BrowserErrorKind.CERT_EXPIRED
            SslError.SSL_IDMISMATCH -> BrowserErrorKind.CERT_MISMATCH
            SslError.SSL_NOTYETVALID -> BrowserErrorKind.CERT_NOT_YET_VALID
            SslError.SSL_UNTRUSTED -> BrowserErrorKind.CERT_UNTRUSTED
            SslError.SSL_DATE_INVALID -> BrowserErrorKind.CERT_DATE_INVALID
            SslError.SSL_INVALID -> BrowserErrorKind.CERT_INVALID
            else -> BrowserErrorKind.SSL_HANDSHAKE
        }
        return BrowserError(kind, url)
    }

    /** @param didCrash — false means the system killed the renderer (usually low memory) */
    fun fromRenderer(didCrash: Boolean, url: String): BrowserError =
        BrowserError(
            if (didCrash) BrowserErrorKind.RENDERER_CRASHED else BrowserErrorKind.RENDERER_KILLED,
            url,
        )

    fun noAppForLink(url: String) = BrowserError(BrowserErrorKind.NO_APP_FOR_LINK, url)

    fun intentLinkBlocked(url: String) = BrowserError(BrowserErrorKind.INTENT_LINK_BLOCKED, url)
}
