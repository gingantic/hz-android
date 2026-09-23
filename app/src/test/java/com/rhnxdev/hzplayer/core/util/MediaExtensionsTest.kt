package com.rhnxdev.hzplayer.core.util

import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.SortType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the video/audio detection helpers used to route extensionless
 * stream URLs (e.g. bucket URLs) to the correct player surface, and for
 * [sortFilesByType] — the single sort entry point for the local and remote
 * browser screens.
 *
 * [probeContentType] / [isVideoStreamUrl] perform network IO and are intentionally
 * not covered here; [isVideoContentType] and [isVideoOrStreamDefault] are pure.
 */
class MediaExtensionsTest {

    @Test
    fun isVideoContentType_videoMp4_isVideo() {
        assertTrue(isVideoContentType("video/mp4"))
    }

    @Test
    fun isVideoContentType_videoWithCharsetParam_isVideo() {
        assertTrue(isVideoContentType("video/webm; charset=utf-8"))
    }

    @Test
    fun isVideoContentType_audioMpeg_isAudio() {
        assertFalse(isVideoContentType("audio/mpeg"))
    }

    @Test
    fun isVideoContentType_null_defaultsToVideo() {
        assertTrue(isVideoContentType(null))
    }

    @Test
    fun isVideoContentType_octetStream_defaultsToVideo() {
        assertTrue(isVideoContentType("application/octet-stream"))
    }

    @Test
    fun isVideoContentType_unknownDefaultStream_defaultsToVideo() {
        assertTrue(isVideoContentType("application/vnd.apple.mpegurl"))
    }

    @Test
    fun isVideoOrStreamDefault_recognizedVideoExtension_isVideo() {
        assertTrue(isVideoOrStreamDefault("https://example.com/clip.mp4"))
    }

    @Test
    fun isVideoOrStreamDefault_recognizedAudioExtension_isAudio() {
        assertFalse(isVideoOrStreamDefault("https://example.com/song.mp3"))
    }

    @Test
    fun isVideoOrStreamDefault_extensionlessBucketUrl_defaultsToVideo() {
        assertTrue(isVideoOrStreamDefault("https://bucket.s3.amazonaws.com/v/abc123"))
    }

    @Test
    fun isVideoExtension_bucketUrlWithoutExtension_isFalse() {
        assertFalse(isVideoExtension("https://bucket.s3.amazonaws.com/v/abc123"))
    }

    // --- isVideoMedia / isAudioMedia: MIME first, extension as fallback ---

    @Test
    fun isVideoMedia_mimeOnly_isVideo() {
        assertTrue(isVideoMedia("clip", "video/mp4"))
    }

    @Test
    fun isVideoMedia_extensionOnly_isVideo() {
        assertTrue(isVideoMedia("clip.mkv", null))
    }

    @Test
    fun isVideoMedia_audioMimeAndExtension_isNotVideo() {
        assertFalse(isVideoMedia("song.mp3", "audio/mpeg"))
    }

    @Test
    fun isAudioMedia_mimeOnly_isAudio() {
        assertTrue(isAudioMedia("track", "audio/mpeg"))
    }

    @Test
    fun isAudioMedia_extensionOnly_isAudio() {
        assertTrue(isAudioMedia("track.flac", null))
    }

    @Test
    fun isAudioMedia_genericMime_fallsBackToExtension() {
        assertTrue(isAudioMedia("track.flac", "application/octet-stream"))
    }

    @Test
    fun isAudioMedia_videoMimeAndExtension_isNotAudio() {
        assertFalse(isAudioMedia("clip.mkv", "video/x-matroska"))
    }

    // --- sortFilesByType: dirs-first partition, per-key ordering, direction ---

    @Test
    fun sortFilesByType_dirsComeFirst_thenFilesByName() {
        val items = listOf(
            folder("zebra.mp4"),
            folder("Movies", isDirectory = true),
            folder("apple.mp4"),
            folder("Archive", isDirectory = true),
        )

        val sorted = items.sortFilesByType(SortType.TITLE, SortDirection.ASCENDING)

        assertEquals(listOf("Archive", "Movies", "apple.mp4", "zebra.mp4"), sorted.map { it.name })
    }

    @Test
    fun sortFilesByType_titleSort_isCaseInsensitive() {
        // Plain String ordering would put every capitalised name first.
        val items = listOf(folder("Beta.mp4"), folder("alpha.mp4"), folder("Gamma.mp4"))

        val sorted = items.sortFilesByType(SortType.TITLE, SortDirection.ASCENDING)

        assertEquals(listOf("alpha.mp4", "Beta.mp4", "Gamma.mp4"), sorted.map { it.name })
    }

    @Test
    fun sortFilesByType_dateModifiedSort_isNumeric() {
        val items = listOf(
            folder("new.mp4", modified = 300),
            folder("old.mp4", modified = 100),
            folder("mid.mp4", modified = 200),
        )

        val sorted = items.sortFilesByType(SortType.DATE_MODIFIED, SortDirection.ASCENDING)

        assertEquals(listOf("old.mp4", "mid.mp4", "new.mp4"), sorted.map { it.name })
    }

    @Test
    fun sortFilesByType_fileSizeSort_isNumeric() {
        val items = listOf(
            folder("big.mp4", size = 900),
            folder("small.mp4", size = 100),
            folder("mid.mp4", size = 500),
        )

        val sorted = items.sortFilesByType(SortType.FILE_SIZE, SortDirection.ASCENDING)

        assertEquals(listOf("small.mp4", "mid.mp4", "big.mp4"), sorted.map { it.name })
    }

    @Test
    fun sortFilesByType_durationSort_filesByDuration_dirsByName() {
        // Dirs have no duration — they keep name order and stay ahead of the files.
        val items = listOf(
            folder("zDir", isDirectory = true),
            folder("long.mp4", duration = 900),
            folder("aDir", isDirectory = true),
            folder("short.mp4", duration = 100),
        )

        val sorted = items.sortFilesByType(SortType.DURATION, SortDirection.ASCENDING)

        assertEquals(listOf("aDir", "zDir", "short.mp4", "long.mp4"), sorted.map { it.name })
    }

    @Test
    fun sortFilesByType_descending_reversesEachGroup_dirsStayFirst() {
        // Direction reverses within the partitions, never across them.
        val items = listOf(
            folder("aDir", isDirectory = true),
            folder("zDir", isDirectory = true),
            folder("a.mp4"),
            folder("z.mp4"),
        )

        val sorted = items.sortFilesByType(SortType.TITLE, SortDirection.DESCENDING)

        assertEquals(listOf("zDir", "aDir", "z.mp4", "a.mp4"), sorted.map { it.name })
    }

    @Test
    fun sortFilesByType_unhandledSortType_fallsBackToName() {
        // ARTIST / ALBUM / DATE_ADDED carry no meaning for a file listing; the
        // else branch keeps name order rather than leaving the list unsorted.
        val items = listOf(folder("b.mp4"), folder("a.mp4"))

        val sorted = items.sortFilesByType(SortType.ARTIST, SortDirection.ASCENDING)

        assertEquals(listOf("a.mp4", "b.mp4"), sorted.map { it.name })
    }

    @Test
    fun sortFilesByType_defaultDirection_isAscending() {
        val items = listOf(folder("b.mp4"), folder("a.mp4"))

        val sorted = sortFilesByType(
            items,
            SortType.TITLE,
            isDirectory = { it.isDirectory },
            name = { it.name },
            dateModified = { it.dateModified },
            size = { it.fileSize },
        )

        assertEquals(listOf("a.mp4", "b.mp4"), sorted.map { it.name })
    }

    @Test
    fun sortFilesByType_remoteFileItem_sortsDirsFirstByName() {
        val items = listOf(
            remoteFile("z.mp4"),
            remoteFile("Dir", isDirectory = true),
            remoteFile("a.mp4"),
        )

        val sorted = items.sortFilesByType(SortType.TITLE, SortDirection.ASCENDING)

        assertEquals(listOf("Dir", "a.mp4", "z.mp4"), sorted.map { it.name })
    }

    @Test
    fun sortFilesByType_remoteFileItem_durationSort_keepsListingOrder() {
        // RemoteFileItem carries no duration, so every entry compares equal and the
        // stable sort preserves listing order — but the dirs-first split still applies.
        val items = listOf(
            remoteFile("b.mp4"),
            remoteFile("Dir", isDirectory = true),
            remoteFile("a.mp4"),
        )

        val sorted = items.sortFilesByType(SortType.DURATION, SortDirection.ASCENDING)

        assertEquals(listOf("Dir", "b.mp4", "a.mp4"), sorted.map { it.name })
    }

    private fun folder(
        name: String,
        isDirectory: Boolean = false,
        size: Long = 0,
        modified: Long = 0,
        duration: Long = 0,
    ) = FolderItem(
        id = name.hashCode().toLong(),
        name = name,
        path = "/$name",
        isDirectory = isDirectory,
        fileSize = size,
        dateModified = modified,
        durationMs = duration,
    )

    private fun remoteFile(
        name: String,
        isDirectory: Boolean = false,
        size: Long = 0,
        modified: Long = 0,
    ) = RemoteFileItem(
        name = name,
        path = "/$name",
        isDirectory = isDirectory,
        fileSize = size,
        dateModified = modified,
    )
}
