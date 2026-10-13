/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LanHttpTest {
    private fun read(raw: String, isHead: Boolean = false) = LanHttp.readResponse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)), isHead)

    @Test
    fun readsBodyWithContentLength() {
        val response = read("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nX-A: b\r\n\r\nhelloEXTRA")
        assertEquals(200, response.code)
        assertEquals("hello", response.bodyAsString())
        assertEquals("b", response.headers["x-a"])
    }

    @Test
    fun readsChunkedBody() {
        val response = read(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "4\r\n<roo\r\n" + "A;ext=1\r\nt>é</root\r\n" + "1\r\n>\r\n" + "0\r\nTrailer: x\r\n\r\n"
        )
        assertEquals("<root>é</root>", response.bodyAsString())
    }

    @Test
    fun readsBodyUntilConnectionCloses() {
        assertEquals("all of it", read("HTTP/1.0 200 OK\r\n\r\nall of it").bodyAsString())
    }

    @Test
    fun headAnswerHasNoBody() {
        val response = read("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n", isHead = true)
        assertEquals(0, response.body.size)
    }

    @Test
    fun rejectsMalformedAnswers() {
        listOf("", "garbage\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort").forEach {
            try {
                read(it)
                fail("Expected an exception for: $it")
            } catch (_: IOException) {
                // expected
            }
        }
    }

    @Test
    fun onlyLocalAddressesAreAllowed() {
        assertTrue(LanHttp.isLocalAddress(InetAddress.getByName("192.168.1.10")))
        assertTrue(LanHttp.isLocalAddress(InetAddress.getByName("10.1.2.3")))
        assertTrue(LanHttp.isLocalAddress(InetAddress.getByName("172.16.0.1")))
        assertTrue(LanHttp.isLocalAddress(InetAddress.getByName("169.254.1.1")))
        assertTrue(LanHttp.isLocalAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(LanHttp.isLocalAddress(InetAddress.getByName("fd12:3456::1")))
        assertTrue(LanHttp.isLocalAddress(InetAddress.getByName("fe80::1")))
        assertFalse(LanHttp.isLocalAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(LanHttp.isLocalAddress(InetAddress.getByName("2001:4860::8888")))
    }

    @Test
    fun refusesPublicAndHttpsUrls() {
        listOf("http://8.8.8.8/desc.xml", "https://192.168.1.1/desc.xml", "not a url").forEach {
            try {
                LanHttp.request(it)
                fail("Expected an exception for: $it")
            } catch (_: IOException) {
                // expected
            }
        }
    }
}
