package com.rhnxdev.hzplayer.core.util

import android.webkit.MimeTypeMap
import java.net.URLConnection

private val MIME_MAP = mapOf(
    "mp4" to "video/mp4", "mkv" to "video/x-matroska", "avi" to "video/avi",
    "mov" to "video/quicktime", "wmv" to "video/x-ms-wmv", "flv" to "video/x-flv",
    "webm" to "video/webm", "3gp" to "video/3gpp", "m4v" to "video/mp4",
    "mpg" to "video/mpeg", "mpeg" to "video/mpeg", "ts" to "video/mp2t",
    "mp3" to "audio/mpeg", "flac" to "audio/flac", "wav" to "audio/wav",
    "ogg" to "audio/ogg", "aac" to "audio/aac", "wma" to "audio/x-ms-wma",
    "m4a" to "audio/mp4", "opus" to "audio/opus", "ape" to "audio/x-ape",
    "aiff" to "audio/aiff", "dsf" to "audio/dsf", "dff" to "audio/dff",
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
    "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
    "pdf" to "application/pdf", "zip" to "application/zip", "rar" to "application/vnd.rar",
    "7z" to "application/x-7z-compressed", "gz" to "application/gzip",
    "txt" to "text/plain",
    "srt" to "text/plain", "sub" to "text/plain",
    "ass" to "text/plain", "ssa" to "text/plain", "vtt" to "text/plain",
    "lrc" to "text/plain", "nfo" to "text/plain",
    "iso" to "application/x-iso9660-image",
)

fun guessMimeType(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    return mime ?: MIME_MAP[ext] ?: URLConnection.guessContentTypeFromName(name)
}
