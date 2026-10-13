/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.proxy

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.cast.dlna.CastDash
import org.schabi.newpipe.cast.dlna.DashTrack

class CastProxyServerTest {
    private val content = ByteArray(10_000) { (it % 251).toByte() }
    private val requestedRanges = ArrayList<HttpRange?>()

    /** Behaves like googlevideo: honors ranges, answers 416 past the end. */
    private val fakeUpstream = Upstream { url, range ->
        requestedRanges.add(range)
        if (url != REAL_URL) {
            throw IOException("unexpected url $url")
        }
        val total = content.size.toLong()
        if (range == null) {
            UpstreamResponse(200, total, null, "video/mp4", ByteArrayInputStream(content))
        } else {
            val start = range.start ?: (total - range.suffixLength!!).coerceAtLeast(0)
            val end = minOf(range.end ?: (total - 1), total - 1)
            if (start >= total) {
                UpstreamResponse(416, 0, "bytes */$total", null, null)
            } else {
                val slice = content.copyOfRange(start.toInt(), end.toInt() + 1)
                UpstreamResponse(206, slice.size.toLong(), "bytes $start-$end/$total", "video/mp4", ByteArrayInputStream(slice))
            }
        }
    }

    private lateinit var server: CastProxyServer
    private lateinit var proxyUrl: String

    @Before
    fun setUp() {
        server = CastProxyServer(fakeUpstream, InetAddress.getLoopbackAddress())
        server.start()
        proxyUrl = server.register(REAL_URL, "video/mp4")
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun open(url: String = proxyUrl, method: String = "GET", range: String? = null): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        range?.let { connection.setRequestProperty("Range", it) }
        return connection
    }

    @Test
    fun registeredUrlIsShortAndHttp() {
        assertTrue(proxyUrl.matches(Regex("http://127\\.0\\.0\\.1:\\d+/v/[0-9a-f]{24}\\.mp4")))
    }

    @Test
    fun getWithoutRangeReturnsWholeFile() {
        val connection = open()
        assertEquals(200, connection.responseCode)
        assertEquals("video/mp4", connection.contentType)
        assertEquals("bytes", connection.getHeaderField("Accept-Ranges"))
        assertEquals(content.size.toLong(), connection.contentLengthLong)
        assertEquals("Streaming", connection.getHeaderField("transferMode.dlna.org"))
        assertArrayEquals(content, connection.inputStream.readBytes())
        assertEquals(listOf<HttpRange?>(null), requestedRanges)
    }

    @Test
    fun getWithRangeReturnsPartialContent() {
        val connection = open(range = "bytes=100-199")
        assertEquals(206, connection.responseCode)
        assertEquals("bytes 100-199/10000", connection.getHeaderField("Content-Range"))
        assertEquals(100, connection.contentLengthLong)
        assertArrayEquals(content.copyOfRange(100, 200), connection.inputStream.readBytes())
        assertEquals(listOf<HttpRange?>(HttpRange(100, 199)), requestedRanges)
    }

    @Test
    fun getWithOpenAndSuffixRanges() {
        val open = open(range = "bytes=9990-")
        assertEquals(206, open.responseCode)
        assertEquals("bytes 9990-9999/10000", open.getHeaderField("Content-Range"))
        assertArrayEquals(content.copyOfRange(9990, 10000), open.inputStream.readBytes())

        val suffix = open(range = "bytes=-5")
        assertEquals(206, suffix.responseCode)
        assertEquals("bytes 9995-9999/10000", suffix.getHeaderField("Content-Range"))
        assertArrayEquals(content.copyOfRange(9995, 10000), suffix.inputStream.readBytes())
    }

    @Test
    fun headReturnsTotalLengthWithoutBody() {
        val connection = open(method = "HEAD")
        assertEquals(200, connection.responseCode)
        assertEquals(content.size.toLong(), connection.contentLengthLong)
        assertEquals("video/mp4", connection.contentType)
        // only one byte was asked to the upstream to learn the length
        assertEquals(listOf<HttpRange?>(HttpRange(0, 0)), requestedRanges)
    }

    @Test
    fun headWithRangeReturnsPartialHeaders() {
        val connection = open(method = "HEAD", range = "bytes=10-19")
        assertEquals(206, connection.responseCode)
        assertEquals("bytes 10-19/10000", connection.getHeaderField("Content-Range"))
        assertEquals(10, connection.contentLengthLong)
    }

    @Test
    fun rangePastTheEndIsNotSatisfiable() {
        val connection = open(range = "bytes=20000-")
        assertEquals(416, connection.responseCode)
        assertEquals("bytes */10000", connection.getHeaderField("Content-Range"))
    }

    @Test
    fun malformedRangeIsNotSatisfiable() {
        assertEquals(416, open(range = "bytes=0-1,4-5").responseCode)
        assertTrue(requestedRanges.isEmpty())
    }

    @Test
    fun unknownTokenIsNotFound() {
        val url = proxyUrl.replace(Regex("/v/[0-9a-f]+"), "/v/0123456789abcdef01234567")
        assertEquals(404, open(url).responseCode)
        assertEquals(404, open(proxyUrl.substringBefore("/v/") + "/other").responseCode)
    }

    @Test
    fun otherMethodsAreNotAllowed() {
        val socket = Socket(InetAddress.getLoopbackAddress(), server.port)
        socket.use {
            it.getOutputStream().write(
                "POST /v/x.mp4 HTTP/1.1\r\nHost: a\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)
            )
            val answer = String(it.getInputStream().readBytes(), StandardCharsets.ISO_8859_1)
            assertTrue(answer, answer.startsWith("HTTP/1.1 405"))
        }
    }

    @Test
    fun upstreamFailureIsBadGateway() {
        val failing = CastProxyServer({ _, _ -> throw IOException("offline") }, InetAddress.getLoopbackAddress())
        failing.start()
        try {
            val url = failing.register(REAL_URL, "video/mp4")
            assertEquals(502, open(url).responseCode)
        } finally {
            failing.stop()
        }
    }

    @Test
    fun parsesRequestHeaders() {
        val raw = "GET /v/abc.mp4 HTTP/1.1\r\nHost: x\r\nRANGE: bytes=1-2\r\n" +
            "getcontentFeatures.dlna.org: 1\r\n\r\n"
        val request = HttpRequest.read(ByteArrayInputStream(raw.toByteArray()))!!
        assertEquals("GET", request.method)
        assertEquals("/v/abc.mp4", request.path)
        assertEquals("bytes=1-2", request.header("Range"))
        assertEquals("1", request.header("getContentFeatures.dlna.org"))
    }

    @Test
    fun liveStreamAnswersHeadWithoutLengthAndRefusesRanges() {
        val track = DashTrack("https://g/v", "video/mp4", "avc1", 1, 0, 10, 11, 20)
        val url = server.registerMux(CastDash(track, track.copy(url = "https://g/a")), "video/mp2t")
        assertTrue(url.endsWith(".ts"))

        val head = open(url, method = "HEAD")
        assertEquals(200, head.responseCode)
        assertEquals("video/mp2t", head.contentType)
        assertEquals(-1L, head.contentLengthLong)
        assertEquals(CastProxyServer.DLNA_LIVE_CONTENT_FEATURES, head.getHeaderField("contentFeatures.dlna.org"))

        assertEquals(416, open(url, range = "bytes=100-").responseCode)
        // HEAD does not download anything
        assertTrue(requestedRanges.isEmpty())
    }

    @Test
    fun parsesStartTime() {
        assertEquals(0.0, CastProxyServer.startSeconds("/v/abc.ts"), 0.0)
        assertEquals(125.0, CastProxyServer.startSeconds("/v/abc.ts?t=125"), 0.0)
        assertEquals(0.0, CastProxyServer.startSeconds("/v/abc.ts?t=-4"), 0.0)
    }

    companion object {
        private const val REAL_URL = "https://rr1---sn-xyz.googlevideo.com/videoplayback?itag=18&x=1"
    }
}
