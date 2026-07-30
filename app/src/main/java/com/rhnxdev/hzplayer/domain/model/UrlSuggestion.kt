package com.rhnxdev.hzplayer.domain.model

/** A single URL-bar (omnibox) suggestion derived from browsing history. */
data class UrlSuggestion(
    val url: String,
    val title: String,
    val lastVisited: Long,
    val visitCount: Int,
)
