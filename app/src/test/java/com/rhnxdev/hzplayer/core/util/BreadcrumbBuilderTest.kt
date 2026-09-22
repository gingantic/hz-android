package com.rhnxdev.hzplayer.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Breadcrumb paths double as navigation targets — a crumb whose path is not the
 * directory the user actually opened pops the wrong layer, so both the labels and
 * the accumulated paths are pinned here.
 */
class BreadcrumbBuilderTest {

    private val labels = mapOf(
        "/storage/emulated/0" to "Internal Storage",
        "/storage/1234-5678" to "SD Card",
    )

    @Test
    fun internalStorage_collapsesMountPrefix() {
        val crumbs = buildBreadcrumbs("/storage/emulated/0/Movies/Action", labels)
        assertEquals(listOf("Internal Storage", "Movies", "Action"), crumbs.map { it.name })
        assertEquals(
            listOf("/storage/emulated/0", "/storage/emulated/0/Movies", "/storage/emulated/0/Movies/Action"),
            crumbs.map { it.path },
        )
    }

    @Test
    fun internalStorageRoot_isSingleCrumb() {
        val crumbs = buildBreadcrumbs("/storage/emulated/0", labels)
        assertEquals(1, crumbs.size)
        assertEquals("Internal Storage", crumbs[0].name)
        assertEquals("/storage/emulated/0", crumbs[0].path)
    }

    @Test
    fun sdCard_usesItsLabel() {
        val crumbs = buildBreadcrumbs("/storage/1234-5678/Music", labels)
        assertEquals(listOf("SD Card", "Music"), crumbs.map { it.name })
        assertEquals(listOf("/storage/1234-5678", "/storage/1234-5678/Music"), crumbs.map { it.path })
    }

    @Test
    fun unlabelledVolume_collapsesToItsMountFolder() {
        val crumbs = buildBreadcrumbs("/storage/9ABC-DEF0/Music", labels)
        assertEquals(listOf("9ABC-DEF0", "Music"), crumbs.map { it.name })
        assertEquals(listOf("/storage/9ABC-DEF0", "/storage/9ABC-DEF0/Music"), crumbs.map { it.path })
    }

    @Test
    fun secondaryUserProfile_keepsRawChain() {
        val crumbs = buildBreadcrumbs("/storage/emulated/10/Docs", labels)
        assertEquals(listOf("Device", "storage", "emulated", "10", "Docs"), crumbs.map { it.name })
    }

    @Test
    fun nonStoragePath_keepsRawChain() {
        val crumbs = buildBreadcrumbs("/data/app", labels)
        assertEquals(listOf("Device", "data", "app"), crumbs.map { it.name })
        assertEquals(listOf("/", "/data", "/data/app"), crumbs.map { it.path })
    }

    @Test
    fun rootPath_isDeviceCrumb() {
        val crumbs = buildBreadcrumbs("/", labels)
        assertEquals(listOf("Device"), crumbs.map { it.name })
        assertEquals(listOf("/"), crumbs.map { it.path })
    }

    @Test
    fun trailingSlash_isIgnored() {
        val crumbs = buildBreadcrumbs("/storage/emulated/0/Movies/", labels)
        assertEquals(listOf("Internal Storage", "Movies"), crumbs.map { it.name })
        assertEquals("/storage/emulated/0/Movies", crumbs.last().path)
    }
}
