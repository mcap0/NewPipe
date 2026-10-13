/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceDescriptionParserTest {
    private fun description(
        controlAvt: String,
        controlRc: String? = "/upnp/control/RenderingControl1",
        urlBase: String? = null,
        friendlyName: String = "[TV] Samsung 6 Series (43)"
    ): String {
        val base = urlBase?.let { "<URLBase>$it</URLBase>" }.orEmpty()
        val rc = controlRc?.let {
            "<service>" +
                "<serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>" +
                "<serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>" +
                "<controlURL>$it</controlURL>" +
                "<eventSubURL>/upnp/event/RenderingControl1</eventSubURL>" +
                "<SCPDURL>/RenderingControl_1.xml</SCPDURL>" +
                "</service>"
        }.orEmpty()
        return "<?xml version=\"1.0\"?>\n" +
            "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:sec=\"http://www.sec.co.kr/dlna\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion>" +
            base +
            "<device>" +
            "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>" +
            "<friendlyName>$friendlyName</friendlyName>" +
            "<manufacturer>Samsung Electronics</manufacturer>" +
            "<modelName>UE43TU7000</modelName>" +
            "<UDN>uuid:0a1b2c3d-0000-1000-8000-aabbccddeeff</UDN>" +
            "<sec:ProductCap>Tab,Streaming,Y2020</sec:ProductCap>" +
            "<serviceList>" +
            rc +
            "<service>" +
            "<serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>" +
            "<controlURL>/upnp/control/ConnectionManager1</controlURL>" +
            "</service>" +
            "<service>" +
            "<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>" +
            "<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>" +
            "<controlURL>$controlAvt</controlURL>" +
            "</service>" +
            "</serviceList>" +
            "</device>" +
            "</root>"
    }

    @Test
    fun parsesSamsungDescriptionWithAbsolutePaths() {
        val device = DeviceDescriptionParser.parse(
            description("/upnp/control/AVTransport1"),
            "http://192.168.1.50:9197/dmr"
        )!!
        assertEquals("[TV] Samsung 6 Series (43)", device.friendlyName)
        assertEquals("uuid:0a1b2c3d-0000-1000-8000-aabbccddeeff", device.udn)
        assertEquals("Samsung Electronics", device.manufacturer)
        assertEquals("UE43TU7000", device.modelName)
        assertEquals("http://192.168.1.50:9197/upnp/control/AVTransport1", device.avTransportControlUrl)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", device.avTransportServiceType)
        assertEquals("http://192.168.1.50:9197/upnp/control/RenderingControl1", device.renderingControlUrl)
        assertEquals("urn:schemas-upnp-org:service:RenderingControl:1", device.renderingControlServiceType)
    }

    @Test
    fun resolvesRelativeUrlsAgainstLocation() {
        val device = DeviceDescriptionParser.parse(
            description("AVTransport/control", "RenderingControl/control"),
            "http://10.0.0.7:49152/dev/desc.xml"
        )!!
        assertEquals("http://10.0.0.7:49152/dev/AVTransport/control", device.avTransportControlUrl)
        assertEquals("http://10.0.0.7:49152/dev/RenderingControl/control", device.renderingControlUrl)
    }

    @Test
    fun urlBaseHasPriorityOverLocation() {
        val device = DeviceDescriptionParser.parse(
            description("upnp/control/avt", urlBase = "http://10.0.0.8:8080/"),
            "http://10.0.0.7:49152/dev/desc.xml"
        )!!
        assertEquals("http://10.0.0.8:8080/upnp/control/avt", device.avTransportControlUrl)
        assertEquals("http://10.0.0.8:8080/upnp/control/RenderingControl1", device.renderingControlUrl)
    }

    @Test
    fun urlBaseWithoutPathIsTheRoot() {
        val device = DeviceDescriptionParser.parse(
            description("control/avt", urlBase = "http://10.0.0.8:8080"),
            "http://10.0.0.7:49152/dev/desc.xml"
        )!!
        assertEquals("http://10.0.0.8:8080/control/avt", device.avTransportControlUrl)
    }

    @Test
    fun absoluteControlUrlIsKept() {
        val device = DeviceDescriptionParser.parse(
            description("http://10.0.0.9:1234/AVT", controlRc = null),
            "http://10.0.0.7:49152/desc.xml"
        )!!
        assertEquals("http://10.0.0.9:1234/AVT", device.avTransportControlUrl)
        assertNull(device.renderingControlUrl)
        assertNull(device.renderingControlServiceType)
    }

    @Test
    fun findsRendererInEmbeddedDevice() {
        val xml = "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device>" +
            "<friendlyName>NAS</friendlyName>" +
            "<UDN>uuid:root</UDN>" +
            "<serviceList><service>" +
            "<serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>" +
            "<controlURL>/cd</controlURL>" +
            "</service></serviceList>" +
            "<deviceList><device>" +
            "<friendlyName>Living room</friendlyName>" +
            "<UDN>uuid:renderer</UDN>" +
            "<serviceList><service>" +
            "<serviceType>urn:schemas-upnp-org:service:AVTransport:2</serviceType>" +
            "<controlURL>/avt</controlURL>" +
            "</service></serviceList>" +
            "</device></deviceList>" +
            "</device></root>"
        val device = DeviceDescriptionParser.parse(xml, "http://10.0.0.3:80/desc.xml")!!
        assertEquals("Living room", device.friendlyName)
        assertEquals("uuid:renderer", device.udn)
        assertEquals("http://10.0.0.3:80/avt", device.avTransportControlUrl)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:2", device.avTransportServiceType)
    }

    @Test
    fun deviceWithoutAvTransportIsIgnored() {
        val xml = "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device>" +
            "<friendlyName>Router</friendlyName>" +
            "<serviceList><service>" +
            "<serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>" +
            "<controlURL>/ctl</controlURL>" +
            "</service></serviceList>" +
            "</device></root>"
        assertNull(DeviceDescriptionParser.parse(xml, "http://10.0.0.1/desc.xml"))
    }

    @Test
    fun missingFriendlyNameFallsBackToHost() {
        val xml = description("/avt", friendlyName = "")
        val device = DeviceDescriptionParser.parse(xml, "http://10.0.0.4:9197/dmr")!!
        assertEquals("10.0.0.4", device.friendlyName)
    }

    @Test
    fun descriptionWithBomAndEscapedNameIsParsed() {
        val xml = "\uFEFF" + description("/avt", friendlyName = "Tom &amp; Jerry&apos;s TV")
        val device = DeviceDescriptionParser.parse(xml, "http://10.0.0.4:9197/dmr")!!
        assertEquals("Tom & Jerry's TV", device.friendlyName)
    }

    @Test
    fun resolveUrlFollowsRfc3986() {
        val base = "http://1.2.3.4:5/a/b/desc.xml"
        assertEquals("http://1.2.3.4:5/x", DeviceDescriptionParser.resolveUrl(base, "/x"))
        assertEquals("http://1.2.3.4:5/a/b/x", DeviceDescriptionParser.resolveUrl(base, "x"))
        assertEquals("http://1.2.3.4:5/a/x", DeviceDescriptionParser.resolveUrl(base, "../x"))
        assertEquals("https://h/x", DeviceDescriptionParser.resolveUrl(base, " https://h/x "))
    }
}
