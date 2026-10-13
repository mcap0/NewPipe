/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpTest {
    @Test
    fun parsesSamsungSearchAnswer() {
        val answer = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "DATE: Tue, 13 Oct 2026 10:00:00 GMT\r\n" +
            "EXT:\r\n" +
            "LOCATION: http://192.168.1.50:9197/dmr\r\n" +
            "SERVER: SHP, UPnP/1.0, Samsung UPnP SDK/1.0\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "USN: uuid:0a1b2c3d-0000-1000-8000-aabbccddeeff::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n"

        val response = Ssdp.parseResponse(answer)!!
        assertEquals("http://192.168.1.50:9197/dmr", response.location)
        assertEquals(Ssdp.MEDIA_RENDERER, response.searchTarget)
        assertTrue(response.usn!!.startsWith("uuid:0a1b2c3d"))
        assertEquals("SHP, UPnP/1.0, Samsung UPnP SDK/1.0", response.server)
    }

    @Test
    fun headerNamesAreCaseInsensitiveAndLfOnlyIsAccepted() {
        val answer = "HTTP/1.1 200 OK\n" +
            "Location:   http://10.0.0.2:49152/description.xml  \n" +
            "st: urn:schemas-upnp-org:service:AVTransport:1\n\n"

        val response = Ssdp.parseResponse(answer)!!
        assertEquals("http://10.0.0.2:49152/description.xml", response.location)
        assertEquals(Ssdp.AV_TRANSPORT, response.searchTarget)
        assertNull(response.usn)
    }

    @Test
    fun locationValueWithColonsIsKeptWhole() {
        val response = Ssdp.parseResponse("HTTP/1.1 200 OK\r\nLOCATION: http://[fe80::1]:8080/a\r\n\r\n")!!
        assertEquals("http://[fe80::1]:8080/a", response.location)
    }

    @Test
    fun parsesNotifyAliveAndIgnoresByeBye() {
        val alive = "NOTIFY * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "NT: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "NTS: ssdp:alive\r\n" +
            "LOCATION: http://192.168.1.50:9197/dmr\r\n\r\n"
        assertEquals(Ssdp.MEDIA_RENDERER, Ssdp.parseResponse(alive)!!.searchTarget)

        val byeBye = alive.replace("ssdp:alive", "ssdp:byebye")
        assertNull(Ssdp.parseResponse(byeBye))
    }

    @Test
    fun rejectsInvalidAnswers() {
        assertNull(Ssdp.parseResponse(""))
        assertNull(Ssdp.parseResponse("HTTP/1.1 404 Not Found\r\nLOCATION: http://a/b\r\n\r\n"))
        assertNull(Ssdp.parseResponse("HTTP/1.1 200 OK\r\nST: x\r\n\r\n"))
        assertNull(Ssdp.parseResponse("M-SEARCH * HTTP/1.1\r\nLOCATION: http://a/b\r\n\r\n"))
    }

    @Test
    fun buildsSearchRequest() {
        val request = Ssdp.buildSearchRequest(Ssdp.MEDIA_RENDERER, 3)
        val lines = request.split("\r\n")
        assertEquals("M-SEARCH * HTTP/1.1", lines[0])
        assertTrue(lines.contains("HOST: 239.255.255.250:1900"))
        assertTrue(lines.contains("MAN: \"ssdp:discover\""))
        assertTrue(lines.contains("MX: 3"))
        assertTrue(lines.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertTrue(request.endsWith("\r\n\r\n"))
    }
}
