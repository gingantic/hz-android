package com.rhnxdev.hzplayer.data.mapper

import com.rhnxdev.hzplayer.data.datasource.local.room.entities.ServerConfigEntity
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.StreamHistoryEntity
import com.rhnxdev.hzplayer.data.security.PasswordCrypto
import com.rhnxdev.hzplayer.domain.model.NetworkProtocol
import com.rhnxdev.hzplayer.domain.model.ServerConfig
import com.rhnxdev.hzplayer.domain.model.StreamHistoryItem

fun ServerConfigEntity.toDomain(crypto: PasswordCrypto): ServerConfig = ServerConfig(
    id = id,
    name = name,
    protocol = try { NetworkProtocol.valueOf(protocol) } catch (_: Exception) { NetworkProtocol.FTP },
    host = host,
    port = port,
    username = username,
    password = crypto.decrypt(encryptedPassword),
    basePath = basePath,
    createdAt = createdAt,
)

fun ServerConfig.toEntity(crypto: PasswordCrypto): ServerConfigEntity = ServerConfigEntity(
    id = id,
    name = name,
    protocol = protocol.name,
    host = host,
    port = port,
    username = username,
    encryptedPassword = crypto.encrypt(password),
    basePath = basePath,
    createdAt = createdAt,
)

fun StreamHistoryEntity.toDomain(): StreamHistoryItem = StreamHistoryItem(
    id = id,
    url = url,
    title = title,
    isFavorite = isFavorite,
    lastPlayedAt = lastPlayedAt,
)

fun StreamHistoryItem.toEntity(): StreamHistoryEntity = StreamHistoryEntity(
    id = id,
    url = url,
    title = title,
    isFavorite = isFavorite,
    lastPlayedAt = lastPlayedAt,
)
