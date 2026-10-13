/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SoapTest {
    private val avTransport = "urn:schemas-upnp-org:service:AVTransport:1"

    @Test
    fun buildsEnvelopeWithArgumentsInOrder() {
        val envelope = Soap.buildEnvelope(
            avTransport,
            "Seek",
            listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to "0:01:30")
        )
        assertTrue(envelope.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))

        val root = XmlUtils.parse(envelope).documentElement
        assertEquals("Envelope", root.localName)
        assertEquals("http://schemas.xmlsoap.org/soap/envelope/", root.namespaceURI)
        assertEquals("http://schemas.xmlsoap.org/soap/encoding/", root.getAttributeNS(root.namespaceURI, "encodingStyle"))

        val action = XmlUtils.findFirst(root, "Seek")!!
        assertEquals(avTransport, action.namespaceURI)
        val names = (0 until action.childNodes.length).map { action.childNodes.item(it).localName }
        assertEquals(listOf("InstanceID", "Unit", "Target"), names)
        assertEquals("0:01:30", XmlUtils.childText(action, "Target"))
    }

    @Test
    fun buildsEnvelopeWithoutArguments() {
        val root = XmlUtils.parse(Soap.buildEnvelope(avTransport, "Stop", emptyList())).documentElement
        val action = XmlUtils.findFirst(root, "Stop")!!
        assertEquals(0, action.childNodes.length)
    }

    @Test
    fun buildsSoapActionHeader() {
        assertEquals(
            "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
            Soap.soapActionHeader(avTransport, "Play")
        )
    }

    @Test
    fun parsesPositionInfoAnswer() {
        val answer = "<?xml version=\"1.0\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:GetPositionInfoResponse xmlns:u=\"$avTransport\">" +
            "<Track>1</Track><TrackDuration>0:04:10</TrackDuration>" +
            "<TrackMetaData>&lt;DIDL-Lite/&gt;</TrackMetaData>" +
            "<RelTime>0:01:02</RelTime><AbsTime>NOT_IMPLEMENTED</AbsTime>" +
            "</u:GetPositionInfoResponse></s:Body></s:Envelope>"
        val result = Soap.parseResponse("GetPositionInfo", 200, answer)
        assertEquals("0:04:10", result["TrackDuration"])
        assertEquals("0:01:02", result["RelTime"])
        assertEquals("<DIDL-Lite/>", result["TrackMetaData"])
    }

    @Test
    fun parsesUpnpError() {
        val fault = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
            "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>" +
            "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
            "<errorCode>714</errorCode><errorDescription>Illegal MIME-type</errorDescription>" +
            "</UPnPError></detail></s:Fault></s:Body></s:Envelope>"
        try {
            Soap.parseResponse("SetAVTransportURI", 500, fault)
            fail("Expected an exception")
        } catch (e: UpnpException) {
            assertEquals(714, e.errorCode)
            assertEquals("Illegal MIME-type", e.errorDescription)
            assertEquals("SetAVTransportURI", e.action)
        }
    }

    @Test
    fun httpErrorWithoutBodyIsReported() {
        try {
            Soap.parseResponse("Play", 503, "")
            fail("Expected an exception")
        } catch (e: UpnpException) {
            assertEquals(503, e.errorCode)
        }
    }

    @Test
    fun parsesTransportStates() {
        assertEquals(TransportState.PLAYING, TransportState.parse("PLAYING"))
        assertEquals(TransportState.PAUSED, TransportState.parse("PAUSED_PLAYBACK"))
        assertEquals(TransportState.STOPPED, TransportState.parse(" stopped "))
        assertEquals(TransportState.NO_MEDIA, TransportState.parse("NO_MEDIA_PRESENT"))
        assertEquals(TransportState.UNKNOWN, TransportState.parse("CUSTOM"))
        assertEquals(TransportState.UNKNOWN, TransportState.parse(null))
    }
}
