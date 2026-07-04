package com.rhnxdev.hzplayer.core.util

import com.rhnxdev.hzplayer.core.components.BreadcrumbItem

/**
 * Build breadcrumbs for a local filesystem path, starting with "Device".
 * Example: "/storage/emulated/0/Movies" →
 *   [Device(/), storage(/storage), emulated(/storage/emulated), 0(...), Movies(...)]
 */
fun buildBreadcrumbs(path: String): List<BreadcrumbItem> {
    if (path == "/" || path.isEmpty()) return listOf(BreadcrumbItem("Device", "/"))
    val parts = path.trimStart('/').split("/")
    val crumbs = mutableListOf(BreadcrumbItem("Device", "/"))
    var accumulated = ""
    for (part in parts) {
        accumulated = "$accumulated/$part"
        crumbs.add(BreadcrumbItem(part, accumulated))
    }
    return crumbs
}

/**
 * Build breadcrumbs for a remote server path, starting with the server name.
 * Example: "MyServer", "/videos/movies" →
 *   [MyServer(/), videos(/videos), movies(/videos/movies)]
 */
fun buildRemoteBreadcrumbs(serverName: String, path: String): List<BreadcrumbItem> {
    val crumbs = mutableListOf(BreadcrumbItem(serverName, "/"))
    if (path == "/" || path.isEmpty()) return crumbs
    val parts = path.trimStart('/').split("/").filter { it.isNotEmpty() }
    var accumulated = ""
    for (part in parts) {
        accumulated = "$accumulated/$part"
        crumbs.add(BreadcrumbItem(part, accumulated))
    }
    return crumbs
}
