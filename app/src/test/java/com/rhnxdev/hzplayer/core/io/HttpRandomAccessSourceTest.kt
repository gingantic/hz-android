package com.rhnxdev.hzplayer.core.io

import com.rhnxdev.hzplayer.domain.player.NativeErrorClass
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Runs the source against a plain-socket HTTP server that reproduces the failure modes seen on
 * real CDNs. Socket-based on purpose: the unit-test classpath has no HTTP server, and a mock
 * server library would be a new dependency.
 */
class HttpRandomAccessSourceTest {

    private val data = ByteArray(600_000) { (it * 31 + it / 251).toByte() }
    private lateinit var listener: ServerSocket
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val rangeHeaders = CopyOnWriteArrayList<String?>()
    private val noWait = longArrayOf(0, 0, 0)

    private fun url(path: String) = "http://127.0.0.1:${listener.localPort}$path"

    @Before
    fun startServer() {
        listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!listener.isClosed) {
                val client = try { listener.accept() } catch (_: IOException) { break }
                thread(isDaemon = true) { runCatching { serve(client) } }
            }
        }
    }

    @After
    fun stopServer() = listener.close()

    /**
     * Path flags: `norange` ignores Range; `drop1` kills the first body halfway; `dropall` kills
     * every body halfway; `chunked` has no length; `gone` is 404; `busy` is 503 on requests 2-3;
     * `playlist` serves an HLS content type.
     */
    private fun serve(socket: Socket) = socket.use { s ->
        val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val path = reader.readLine().split(' ')[1]
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        rangeHeaders.add(range)
        val n = hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
        val out = s.getOutputStream()
        fun head(status: String, vararg lines: String) {
            out.write((listOf("HTTP/1.1 $status") + lines + "Connection: close" + "" + "").joinToString("\r\n").toByteArray())
        }

        if ("gone" in path) return head("404 Not Found", "Content-Length: 0")
        if ("busy" in path && n in 2..3) return head("503 Service Unavailable", "Content-Length: 0")

        val chunked = "chunked" in path
        var start = 0
        val headers = ArrayList<String>()
        var status = "200 OK"
        if (range != null && "norange" !in path && !chunked) {
            start = range.substringAfter("bytes=").substringBefore('-').toInt()
            status = "206 Partial Content"
            headers += "Content-Range: bytes $start-${data.size - 1}/${data.size}"
        }
        if ("playlist" in path) headers += "Content-Type: application/vnd.apple.mpegurl"
        val body = data.copyOfRange(start, data.size)
        headers += if (chunked) "Transfer-Encoding: chunked" else "Content-Length: ${body.size}"
        head(status, *headers.toTypedArray())

        val kill = "dropall" in path || ("drop1" in path && n == 1)
        val sendable = if (kill) body.size / 2 else body.size
        if (chunked) {
            var off = 0
            while (off < sendable) {
                val len = minOf(16_384, sendable - off)
                out.write("${len.toString(16)}\r\n".toByteArray())
                out.write(body, off, len)
                out.write("\r\n".toByteArray())
                off += len
            }
            if (!kill) out.write("0\r\n\r\n".toByteArray())
        } else {
            out.write(body, 0, sendable) // a truncated body under a full Content-Length = dropped connection
        }
        out.flush()
    }

    private fun source(path: String, retries: LongArray = noWait) =
        HttpRandomAccessSource(url(path), mapOf("User-Agent" to "test", "Referer" to "https://example.com/"), retryDelaysMs = retries)

    /** Reads [from] up to the end in 64 KiB steps; every byte must match. */
    private fun readToEnd(src: HttpRandomAccessSource, from: Long = 0) {
        val buf = ByteArray(65_536)
        var pos = from
        while (true) {
            val n = src.readAt(pos, buf, buf.size)
            if (n < 0) break
            assertArrayEquals("bytes at $pos", data.copyOfRange(pos.toInt(), pos.toInt() + n), buf.copyOf(n))
            pos += n
        }
        assertEquals(data.size.toLong(), pos)
    }

    @Test
    fun `range server is served sequentially and by random access`() {
        val s = source("/ok")
        assertTrue(s.prepare())
        assertEquals(data.size.toLong(), s.getSize())
        readToEnd(s)
        val buf = ByteArray(1000)
        assertEquals(1000, s.readAt(400_000, buf, 1000))
        assertArrayEquals(data.copyOfRange(400_000, 401_000), buf)
        assertEquals(1000, s.readAt(10, buf, 1000)) // backward
        assertArrayEquals(data.copyOfRange(10, 1010), buf)
        s.close()
    }

    @Test
    fun `server that ignores Range still plays and seeks`() {
        val s = source("/norange")
        assertTrue(s.prepare())
        assertEquals(data.size.toLong(), s.getSize())
        val buf = ByteArray(2000)
        assertEquals(2000, s.readAt(500_000, buf, 2000)) // forward: read through
        assertArrayEquals(data.copyOfRange(500_000, 502_000), buf)
        assertEquals(2000, s.readAt(300, buf, 2000)) // backward: restart from 0 and skip
        assertArrayEquals(data.copyOfRange(300, 2300), buf)
        s.close()
    }

    @Test
    fun `dropped connection resumes from the last good byte on a Range server`() {
        val s = source("/drop1")
        assertTrue(s.prepare())
        readToEnd(s)
        assertEquals(NativeErrorClass.NONE, s.lastErrorClass)
        assertTrue("expected a resumed ranged request: $rangeHeaders", rangeHeaders.any { it != null && it != "bytes=0-" })
        s.close()
    }

    @Test
    fun `dropped connection recovers on a server without Range support`() {
        val s = source("/norange-drop1")
        assertTrue(s.prepare())
        readToEnd(s) // the failure that used to spin for 15 s and end playback
        s.close()
    }

    @Test
    fun `persistent drops end in a network error after the retry budget`() {
        val s = source("/dropall")
        assertTrue(s.prepare())
        val buf = ByteArray(65_536)
        var pos = 0L
        try {
            while (true) {
                val n = s.readAt(pos, buf, buf.size)
                if (n < 0) fail("expected a failure, reached EOF")
                pos += n
            }
        } catch (_: IOException) {
            assertEquals(NativeErrorClass.NETWORK, s.lastErrorClass)
        }
        s.close()
    }

    @Test
    fun `transient 503 is retried and not surfaced`() {
        val s = source("/busy")
        assertTrue(s.prepare())
        readToEnd(s)
        assertEquals(NativeErrorClass.NONE, s.lastErrorClass)
        s.close()
    }

    @Test
    fun `sources that cannot be served are declined so FFmpeg keeps them`() {
        assertFalse("404", source("/gone").prepare())
        assertFalse("chunked has no length", source("/chunked").prepare())
        assertFalse("HLS content type", source("/playlist").prepare())
    }

    @Test
    fun `abort cancels instead of reporting end of file`() {
        val s = source("/ok")
        assertTrue(s.prepare())
        s.abortRead()
        try {
            s.readAt(0, ByteArray(10), 10)
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        s.close()
    }

    @Test
    fun `progressive url detection`() {
        assertTrue(isProgressiveHttp("https://h/video.mp4?token=a.m3u8", null))
        assertTrue(isProgressiveHttp("https://h/download?id=1", "video/*"))
        assertFalse(isProgressiveHttp("https://h/master.m3u8?x=1", null))
        assertFalse(isProgressiveHttp("https://h/stream", "application/x-mpegURL"))
        assertFalse(isProgressiveHttp("https://h/m.mpd", null))
    }
}
