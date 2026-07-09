package com.rhnxdev.hzplayer.domain.model

/** Thrown when a remote server rejects the supplied credentials (wrong username/password). */
class RemoteAuthException(message: String = "Authentication failed") : Exception(message)
