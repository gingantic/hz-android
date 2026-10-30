package com.rhnxdev.hzplayer.domain.model

/**
 * A remote server the user can browse.
 *
 * @param discoveryName — normalised machine name learned at discovery time; lets the app
 *   find the server again when its IP changes. Null for servers added by hand.
 * @param lastKnownIp — last numeric IPv4 this server was seen at, or null if unknown.
 */
data class ServerConfig(
    val id: Long = 0,
    val name: String,
    val protocol: NetworkProtocol,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    val allowAnonymous: Boolean = false,
    val basePath: String = "/",
    val createdAt: Long = System.currentTimeMillis(),
    val discoveryName: String? = null,
    val lastKnownIp: String? = null,
)
