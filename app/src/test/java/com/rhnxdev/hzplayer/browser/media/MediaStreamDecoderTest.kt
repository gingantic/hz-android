package com.rhnxdev.hzplayer.browser.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStreamDecoderTest {

    @Test
    fun standardMp4WithMasterInFilename_returnsFalse() {
        val url = "https://example.com/videos/master.mp4"
        assertFalse(MediaStreamDecoder.isDisguisedHlsStream(url))
    }

    @Test
    fun standardMp4WithPlaylistInFilename_returnsFalse() {
        val url = "https://example.com/media/playlist_video.mp4"
        assertFalse(MediaStreamDecoder.isDisguisedHlsStream(url))
    }

    @Test
    fun actualM3u8Extension_returnsTrue() {
        val url = "https://example.com/live/master.m3u8"
        assertTrue(MediaStreamDecoder.isDisguisedHlsStream(url))
    }

    @Test
    fun disguisedHlsTxt_returnsTrue() {
        val url = "https://example.com/cl-master/stream.txt"
        assertTrue(MediaStreamDecoder.isDisguisedHlsStream(url))
    }

    @Test
    fun mpegurlMimeType_returnsTrue() {
        val url = "https://example.com/stream"
        assertTrue(MediaStreamDecoder.isDisguisedHlsStream(url, "application/x-mpegURL"))
    }

    @Test
    fun masterStreamUrl_returnsTrue() {
        val url1 = "https://example.com/hls/master.m3u8"
        val url2 = "https://example.com/api/video/master.json"
        val url3 = "https://example.com/cl-master/index.txt"
        assertTrue(MediaStreamDecoder.isMasterStreamUrl(url1))
        assertTrue(MediaStreamDecoder.isMasterStreamUrl(url2))
        assertTrue(MediaStreamDecoder.isMasterStreamUrl(url3))
    }

    @Test
    fun isMediaUrl_recognizesMasterJsonAndDisguisedStreams() {
        val urlJson = "https://example.com/hls/master.json"
        assertTrue(MediaSnifferEngine.isMediaUrl(urlJson))

        val urlMasterM3u8 = "https://example.com/stream/master.m3u8?token=123"
        assertTrue(MediaSnifferEngine.isMediaUrl(urlMasterM3u8))
    }

    @Test
    fun consolidateMediaTree_groupsVariantsUnderMasterStream() {
        val masterItem = MediaSnifferEngine.createMediaItem(
            rawUrl = "https://example.com/hls/123/master.m3u8",
            pageUrl = "https://example.com",
            pageTitle = "Test Video"
        )
        val indexItem = MediaSnifferEngine.createMediaItem(
            rawUrl = "https://example.com/hls/123/720p/index.m3u8",
            pageUrl = "https://example.com",
            pageTitle = "Test Video"
        )

        val consolidated = MediaSnifferEngine.consolidateMediaTree(listOf(masterItem, indexItem))
        org.junit.Assert.assertEquals(1, consolidated.size)
        org.junit.Assert.assertTrue(consolidated[0].isMasterStream)
        org.junit.Assert.assertEquals(1, consolidated[0].childVariants.size)
        org.junit.Assert.assertEquals(masterItem.url, consolidated[0].childVariants[0].masterUrl)
        org.junit.Assert.assertEquals(masterItem.url, consolidated[0].childVariants[0].playUrl)
    }

    @Test
    fun jumboMumboUrl_withValidStreamMime_isDetectedAsMasterStream() {
        val jumboUrl = "https://cdn.example.com/api/v2/get_stream?data=a8f9c1d3e7&s=991823"
        val mimeType = "application/x-mpegURL"
        assertTrue(MediaSnifferEngine.isMediaUrl(jumboUrl, mapOf("content-type" to mimeType)))

        val item = MediaSnifferEngine.createMediaItem(
            rawUrl = jumboUrl,
            pageUrl = "https://example.com",
            pageTitle = "Jumbo Mumbo Stream",
            mimeType = mimeType
        )
        assertTrue(item.isMasterStream)
        org.junit.Assert.assertEquals(MediaType.STREAM_HLS, item.mediaType)
    }

    @Test
    fun deriveTitle_prioritizesWebsitePageTitleOverRawFilename() {
        val item = MediaSnifferEngine.createMediaItem(
            rawUrl = "https://example.com/stream/master.m3u8",
            pageUrl = "https://example.com/movie/123",
            pageTitle = "My Favorite Anime - Episode 1 (Sub)"
        )
        org.junit.Assert.assertEquals("My Favorite Anime - Episode 1 (Sub)", item.title)
    }

    @Test
    fun streamHistoryItem_parsesHeadersMapFromJson() {
        val historyItem = com.rhnxdev.hzplayer.domain.model.StreamHistoryItem(
            url = "https://cdn.example.com/live/master.m3u8",
            title = "Test Stream",
            headersJson = """{"User-Agent":"CustomBrowser","Referer":"https://example.com/video","Cookie":"session=abc123token"}""",
            pageUrl = "https://example.com/video",
            mimeType = "application/x-mpegURL"
        )
        val headers = historyItem.headersMap
        org.junit.Assert.assertEquals("CustomBrowser", headers["User-Agent"])
        org.junit.Assert.assertEquals("https://example.com/video", headers["Referer"])
        org.junit.Assert.assertEquals("session=abc123token", headers["Cookie"])
    }
}
