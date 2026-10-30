package com.rhnxdev.hzplayer.data.datasource.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards PROPFIND parsing. The parser once returned an empty list for every real
 * response (it looked for `href` at the wrong XML depth), and kept hrefs percent-encoded
 * so playback encoded them a second time.
 */
@RunWith(RobolectricTestRunner::class)
class WebDavBrowserClientParseTest {

    private val client = WebDavBrowserClient("h", 80, "", "", useTls = false)

    private fun multistatus(vararg responses: String) =
        """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">${responses.joinToString("")}</D:multistatus>"""

    private fun response(href: String, name: String, collection: Boolean, size: Long? = null) =
        """<D:response><D:href>$href</D:href><D:propstat><D:prop>
           <D:displayname>$name</D:displayname>
           ${size?.let { "<D:getcontentlength>$it</D:getcontentlength>" } ?: ""}
           <D:resourcetype>${if (collection) "<D:collection/>" else ""}</D:resourcetype>
           </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"""

    @Test
    fun listing_returnsChildrenAndSkipsTheFolderItself() {
        val xml = multistatus(
            response("/", "", collection = true),
            response("/hello.txt", "hello.txt", collection = false, size = 35),
            response("/media/", "media", collection = true),
        )
        val items = client.parsePropfindMultistatus(xml, "/")
        assertEquals(listOf("media", "hello.txt"), items.map { it.name }) // folders sort first
        assertTrue(items.first { it.name == "media" }.isDirectory)
        assertEquals(35L, items.first { it.name == "hello.txt" }.fileSize)
    }

    @Test
    fun nestedListing_skipsSelfEvenWhenRequestedPathIsEncodedOrCased() {
        val xml = multistatus(
            response("/Media/", "Media", collection = true),
            response("/Media/clip%20one.mp4", "clip one.mp4", collection = false, size = 10),
        )
        val items = client.parsePropfindMultistatus(xml, "/media")
        assertEquals(1, items.size)
        assertEquals("clip one.mp4", items[0].name)
    }

    @Test
    fun hrefs_areDecodedOnce() {
        val xml = multistatus(
            response("/", "", collection = true),
            response("/special%20%231%20100%25%20done/", "special #1 100% done", collection = true),
            response("/%E5%90%8D%E5%89%8D/", "", collection = true), // no displayname: name comes from href
            response("/a+b.txt", "a+b.txt", collection = false, size = 1),
        )
        val paths = client.parsePropfindMultistatus(xml, "/").associate { it.name to it.path }
        assertEquals("/special #1 100% done", paths["special #1 100% done"])
        assertEquals("/名前", paths["名前"])
        assertEquals("/a+b.txt", paths["a+b.txt"]) // '+' is not a space in a path
    }

    @Test
    fun absoluteUrlHref_isReducedToItsPath() {
        val xml = multistatus(
            response("http://h:80/", "", collection = true),
            response("http://h:80/docs/readme.txt", "readme.txt", collection = false, size = 3),
        )
        val items = client.parsePropfindMultistatus(xml, "/")
        assertEquals(listOf("/docs/readme.txt"), items.map { it.path })
    }

    @Test
    fun emptyOrMalformedXml_returnsWhatWasParsed() {
        assertTrue(client.parsePropfindMultistatus(multistatus(), "/").isEmpty())
        val truncated = multistatus(response("/a.txt", "a.txt", collection = false, size = 1))
            .substringBefore("</D:multistatus>")
        assertFalse(client.parsePropfindMultistatus(truncated, "/").isEmpty())
    }
}
