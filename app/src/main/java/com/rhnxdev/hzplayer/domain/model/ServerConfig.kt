package com.rhnxdev.hzplayer.domain.model

data class ServerConfig(
    val id: Long = 0,
    val name: String,
    val protocol: NetworkProtocol,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    val basePath: String = "/",
    val createdAt: Long = System.currentTimeMillis(),
)
