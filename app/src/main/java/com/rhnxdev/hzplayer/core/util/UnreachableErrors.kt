package com.rhnxdev.hzplayer.core.util

import com.rhnxdev.hzplayer.domain.model.RemoteAuthException
import jcifs.smb.SmbAuthException
import kotlinx.coroutines.TimeoutCancellationException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * True when this failure means "the server could not be reached at its saved address".
 * That is the only case where looking for the machine at a new IP can help.
 *
 * Walks the cause chain because jcifs, sshj and OkHttp wrap socket errors.
 * A rejected login anywhere in the chain returns false: the server was reached, so a LAN sweep
 * would be wasted effort and could send the saved credentials to the wrong host.
 *
 * ConnectException and NoRouteToHostException are SocketException subclasses.
 */
fun Throwable.isUnreachable(): Boolean {
    val chain = generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH).toList()
    if (chain.any { it is RemoteAuthException || it is SmbAuthException }) return false
    return chain.any {
        it is UnknownHostException ||
            it is SocketException ||
            it is SocketTimeoutException ||
            it is TimeoutCancellationException ||
            it.isJcifsConnectFailure()
    }
}

/** jcifs reports an unreachable host as a plain SmbException/TransportException with only a message. */
private fun Throwable.isJcifsConnectFailure(): Boolean {
    val type = javaClass.name
    if (type != "jcifs.smb.SmbException" && type != "jcifs.util.transport.TransportException") return false
    val text = message?.lowercase().orEmpty()
    return "failed to connect" in text || "timed out" in text || "timeout" in text || "unreachable" in text
}

private const val MAX_CAUSE_DEPTH = 8
