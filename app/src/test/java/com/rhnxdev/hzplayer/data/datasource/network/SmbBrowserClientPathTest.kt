package com.rhnxdev.hzplayer.data.datasource.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards SMB path-traversal hardening: `normalizeRemotePath` must collapse "." and
 * ".." segments and reject any attempt to climb out of the share root (which could
 * expose admin `$` shares).
 */
@RunWith(RobolectricTestRunner::class)
class SmbBrowserClientPathTest {

    @Test
    fun root_normalizesToSlash() {
        assertEquals("/", SmbBrowserClient("h", 445, "", "").normalizeRemotePath("/"))
        assertEquals("/", SmbBrowserClient("h", 445, "", "").normalizeRemotePath(""))
    }

    @Test
    fun simplePath_preserved() {
        assertEquals(
            "/Share/Movies",
            SmbBrowserClient("h", 445, "", "").normalizeRemotePath("/Share/Movies"),
        )
    }

    @Test
    fun dotSegments_collapsed() {
        assertEquals(
            "/Share/Movies",
            SmbBrowserClient("h", 445, "", "").normalizeRemotePath("/Share/./Movies/."),
        )
    }

    @Test
    fun parentDot_staysWithinShare() {
        // "../Movies" from root must not escape; it collapses to "/Movies".
        assertEquals(
            "/Movies",
            SmbBrowserClient("h", 445, "", "").normalizeRemotePath("/Share/../Movies"),
        )
    }

    @Test
    fun traversalAboveRoot_rejected() {
        // ".." with no segments left to pop = escape attempt → null.
        assertNull(SmbBrowserClient("h", 445, "", "").normalizeRemotePath("/../.."))
        assertNull(SmbBrowserClient("h", 445, "", "").normalizeRemotePath("/Share/../../.."))
    }

    @Test
    fun leadingSlashAndEmptySegments_trimmed() {
        assertEquals(
            "/a/b",
            SmbBrowserClient("h", 445, "", "").normalizeRemotePath("//a//b/"),
        )
    }
}
