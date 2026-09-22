package com.rhnxdev.hzplayer.core.util

import com.rhnxdev.hzplayer.core.components.BreadcrumbItem

/**
 * Build breadcrumbs for a local filesystem path. A storage volume collapses into
 * a single crumb named by [volumeLabels], so the mount noise never reaches the bar:
 *   "/storage/emulated/0/Movies" → [Internal Storage(/storage/emulated/0), Movies(...)]
 *   "/storage/1234-5678/Music"   → [SD Card(/storage/1234-5678), Music(...)]
 * Paths outside a volume keep the raw chain:
 *   "/data/app" → [Device(/), data(/data), app(/data/app)]
 *
 * @param volumeLabels — display name per mount path, see [storageVolumeLabels].
 */
fun buildBreadcrumbs(path: String, volumeLabels: Map<String, String>): List<BreadcrumbItem> {
    if (path == "/" || path.isEmpty()) return listOf(BreadcrumbItem("Device", "/"))

    val mount = volumeMount(path, volumeLabels)
    val crumbs = mutableListOf<BreadcrumbItem>()
    var accumulated = ""
    if (mount != null) {
        crumbs.add(BreadcrumbItem(mount.label, mount.path))
        accumulated = mount.path
    } else {
        crumbs.add(BreadcrumbItem("Device", "/"))
    }

    val remainder = if (mount != null) path.removePrefix(mount.path) else path
    for (part in remainder.trim('/').split('/')) {
        if (part.isEmpty()) continue
        accumulated = "$accumulated/$part"
        crumbs.add(BreadcrumbItem(part, accumulated))
    }
    return crumbs
}

/** A storage volume root: [path] is its mount point, [label] its display name. */
private data class VolumeMount(val path: String, val label: String)

/**
 * The volume [path] sits at or under, or null when it is not inside one. Prefers
 * a labelled mount from [volumeLabels]; a /storage/<volume> path the system does
 * not report (some OEM mounts) still collapses, named after its mount folder.
 */
private fun volumeMount(path: String, volumeLabels: Map<String, String>): VolumeMount? {
    volumeLabels.entries
        .filter { path == it.key || path.startsWith("${it.key}/") }
        .maxByOrNull { it.key.length }
        ?.let { return VolumeMount(it.key, it.value) }

    val segments = path.trim('/').split('/')
    // emulated/self are aliases of the primary volume, which the map always labels.
    if (segments.size < 3 || segments[0] != "storage" || segments[1] == "emulated" || segments[1] == "self") {
        return null
    }
    return VolumeMount("/storage/${segments[1]}", segments[1])
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
