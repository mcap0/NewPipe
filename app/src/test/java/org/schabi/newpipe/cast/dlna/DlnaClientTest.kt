/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Tests [DlnaClient] against a fake renderer which records the SOAP requests.
 */
class DlnaClientTest {
    private class Received(val path: String, val soapAction: String?, val contentType: String?, val body: String)

    private val received = ArrayList<Received>()
    private var answers = HashMap<String, Pair<Int, String>>()
    private lateinit var server: HttpServer
    private lateinit var client: DlnaClient

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val body = String(exchange.requestBody.readBytes(), StandardCharsets.UTF_8)
            val soapAction = exchange.requestHeaders.getFirst("SOAPACTION")
            synchronized(received) {
                received.add(Received(exchange.requestURI.path, soapAction, exchange.requestHeaders.getFirst("Content-Type"), body))
            }
            val action = soapAction?.substringAfter('#')?.removeSuffix("\"").orEmpty()
            val (code, answer) = answers[action] ?: (200 to emptyAnswer(action))
            val bytes = answer.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        client = DlnaClient(
            DlnaDevice(
                udn = "uuid:test",
                friendlyName = "Fake TV",
                manufacturer = null,
                modelName = null,
                location = "$base/dmr",
                avTransportControlUrl = "$base/upnp/control/AVTransport1",
                avTransportServiceType = AVT,
                renderingControlUrl = "$base/upnp/control/RenderingControl1",
                renderingControlServiceType = RC
            )
        )
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun emptyAnswer(action: String, content: String = "", service: String = AVT) = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
        "<s:Body><u:${action}Response xmlns:u=\"$service\">$content</u:${action}Response></s:Body></s:Envelope>"

    @Test
    fun setAvTransportUriSendsMetadata() {
        val media = CastMedia("http://192.168.1.2:1234/v/a.mp4", "A & B 🎵", "Me", "video/mp4", 61)
        client.setAvTransportUri(media)

        val request = received.single()
        assertEquals("/upnp/control/AVTransport1", request.path)
        assertEquals("\"$AVT#SetAVTransportURI\"", request.soapAction)
        assertTrue(request.contentType!!.startsWith("text/xml"))

        val action = XmlUtils.findFirst(XmlUtils.parse(request.body).documentElement, "SetAVTransportURI")!!
        assertEquals("0", XmlUtils.childText(action, "InstanceID"))
        assertEquals(media.url, XmlUtils.childText(action, "CurrentURI"))
        assertEquals(DidlLite.build(media), XmlUtils.childText(action, "CurrentURIMetaData"))
    }

    @Test
    fun controlActionsUseTheRightArguments() {
        client.play()
        client.pause()
        client.seek(3725)
        client.stop()

        val actions = received.map { it.soapAction!!.substringAfter('#').removeSuffix("\"") }
        assertEquals(listOf("Play", "Pause", "Seek", "Stop"), actions)
        val play = XmlUtils.findFirst(XmlUtils.parse(received[0].body).documentElement, "Play")!!
        assertEquals("1", XmlUtils.childText(play, "Speed"))
        val seek = XmlUtils.findFirst(XmlUtils.parse(received[2].body).documentElement, "Seek")!!
        assertEquals("REL_TIME", XmlUtils.childText(seek, "Unit"))
        assertEquals("1:02:05", XmlUtils.childText(seek, "Target"))
    }

    @Test
    fun readsPositionAndTransportState() {
        answers["GetPositionInfo"] = 200 to emptyAnswer(
            "GetPositionInfo",
            "<Track>1</Track><TrackDuration>0:10:00</TrackDuration><RelTime>0:00:42</RelTime>"
        )
        answers["GetTransportInfo"] = 200 to emptyAnswer(
            "GetTransportInfo",
            "<CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>" +
                "<CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>"
        )
        assertEquals(PositionInfo(42, 600), client.getPositionInfo())
        assertEquals(TransportState.PAUSED, client.getTransportInfo())
    }

    @Test
    fun volumeUsesRenderingControl() {
        answers["GetVolume"] = 200 to emptyAnswer("GetVolume", "<CurrentVolume>17</CurrentVolume>", RC)
        assertTrue(client.hasVolumeControl)
        assertEquals(17, client.getVolume())
        client.setVolume(18)

        assertEquals("/upnp/control/RenderingControl1", received[1].path)
        assertEquals("\"$RC#SetVolume\"", received[1].soapAction)
        val setVolume = XmlUtils.findFirst(XmlUtils.parse(received[1].body).documentElement, "SetVolume")!!
        assertEquals("Master", XmlUtils.childText(setVolume, "Channel"))
        assertEquals("18", XmlUtils.childText(setVolume, "DesiredVolume"))
    }

    @Test
    fun upnpErrorsAreThrownAndLogged() {
        CastLog.clear()
        answers["Play"] = 500 to "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>701</errorCode>" +
            "<errorDescription>Transition not available</errorDescription></UPnPError>" +
            "</detail></s:Fault></s:Body></s:Envelope>"
        try {
            client.play()
            fail("Expected an exception")
        } catch (e: UpnpException) {
            assertEquals(701, e.errorCode)
        }
        assertTrue(CastLog.dump().contains("UPnP error 701"))
    }

    companion object {
        private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"
    }
}
