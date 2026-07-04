package com.rhnxdev.hzplayer.core.util

import com.rhnxdev.hzplayer.domain.model.NetworkProtocol

val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp",
    "m4v", "mpg", "mpeg", "ts", "mts", "vob", "m3u8", "mpd",
)

val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "wav", "ogg", "aac", "wma", "m4a",
    "opus", "ape", "aiff", "dsf", "dff",
)

val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub", "lrc")

fun isVideoExtension(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").substringBefore('?').lowercase()
    return ext in VIDEO_EXTENSIONS
}

fun isAudioExtension(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in AUDIO_EXTENSIONS
}

fun isSubtitleExtension(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in SUBTITLE_EXTENSIONS
}

fun defaultPort(protocol: NetworkProtocol): Int = when (protocol) {
    NetworkProtocol.FTP -> 21
    NetworkProtocol.SFTP -> 22
    NetworkProtocol.SMB -> 445
}
