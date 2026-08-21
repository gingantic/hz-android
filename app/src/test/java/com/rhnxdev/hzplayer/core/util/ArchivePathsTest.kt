package com.rhnxdev.hzplayer.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the archive path/URI codecs. These are the seam between
 * the File Browser and playback — a corrupt round-trip means the wrong bytes get
 * played, so the encode→decode identity is the thing worth pinning.
 */
class ArchivePathsTest {

    @Test
    fun archiveUri_roundTrips_pathsWithSpacesAndSlashes() {
        val container = "/storage/emulated/0/My Movies/pack.zip"
        val entry = "season 1/ep 01.mkv"
        val (c, e, p) = ArchiveUri.parse(ArchiveUri.build(container, entry))!!
        assertEquals(container, c)
        assertEquals(entry, e)
        assertNull(p)
    }

    @Test
    fun archiveUri_roundTrips_withPassword() {
        val container = "/storage/emulated/0/pack.zip"
        val entry = "video.mp4"
        val password = "secret_password@123"
        val (c, e, p) = ArchiveUri.parse(ArchiveUri.build(container, entry, password))!!
        assertEquals(container, c)
        assertEquals(entry, e)
        assertEquals(password, p)
    }

    @Test
    fun archiveUri_parse_malformed_isNull() {
        assertNull(ArchiveUri.parse("file:///not/an/archive.mkv"))
        assertNull(ArchiveUri.parse("archive:///onlycontainer"))
        assertNull(ArchiveUri.parse("archive://onlycontainer"))
    }

    @Test
    fun archiveUri_parse_supportsMultipleSlashFormats() {
        val built = ArchiveUri.build("/storage/0/pack.zip", "video.mp4")
        val triple3 = ArchiveUri.parse(built)
        val triple2 = ArchiveUri.parse(built.replace("archive:///", "archive://"))
        val triple1 = ArchiveUri.parse(built.replace("archive:///", "archive:/"))
        assertEquals(triple3, triple2)
        assertEquals(triple3, triple1)
    }

    @Test
    fun browsePath_roundTrips_containerAndPrefix() {
        val container = "/storage/emulated/0/pack.zip"
        val prefix = "a/b/"
        val path = ArchiveBrowsePath.build(container, prefix)
        assertTrue(ArchiveBrowsePath.isArchiveBrowsePath(path))
        assertFalse(ArchiveBrowsePath.isRealFilePath(path))
        val (c, p) = ArchiveBrowsePath.parse(path)
        assertEquals(container, c)
        assertEquals(prefix, p)
    }

    @Test
    fun browsePath_rootPrefix_isEmpty() {
        val (c, p) = ArchiveBrowsePath.parse(ArchiveBrowsePath.build("/x.7z", ""))
        assertEquals("/x.7z", c)
        assertEquals("", p)
    }

    @Test
    fun isRealFilePath_distinguishesVirtualFromReal() {
        assertTrue(ArchiveBrowsePath.isRealFilePath("/storage/emulated/0/x.zip"))
        assertFalse(ArchiveBrowsePath.isRealFilePath(ArchiveUri.build("/x.zip", "a.mp4")))
    }

    @Test
    fun archiveBreadcrumbs_containerThenNestedDirs() {
        val crumbs = buildArchiveBreadcrumbs("/storage/0/pack.zip", "a/b/")
        assertEquals(listOf("pack.zip", "a", "b"), crumbs.map { it.name })
        // last crumb re-lists the deepest level
        assertEquals(ArchiveBrowsePath.build("/storage/0/pack.zip", "a/b/"), crumbs.last().path)
    }

    @Test
    fun isArchiveExtension_matchesKnownFormats() {
        assertTrue(isArchiveExtension("movie.pack.7z"))
        assertTrue(isArchiveExtension("BACKUP.ZIP"))
        assertFalse(isArchiveExtension("movie.mkv"))
        assertFalse(isArchiveExtension("noext"))
    }

    @Test
    fun isSolidArchiveExtension_identifiesSolidFormats() {
        assertTrue(isSolidArchiveExtension("movie.pack.7z"))
        assertTrue(isSolidArchiveExtension("archive.rar"))
        assertTrue(isSolidArchiveExtension("archive.tar.gz"))
        assertTrue(isSolidArchiveExtension("archive.tar.xz"))
        assertFalse(isSolidArchiveExtension("backup.zip"))
        assertFalse(isSolidArchiveExtension("image.iso"))
        assertFalse(isSolidArchiveExtension("file.cab"))
    }
}
