/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DidlLiteTest {
    private val media = CastMedia(
        url = "http://192.168.1.20:41234/v/abc.mp4",
        title = "Tom & Jerry <Live> \"Best\" 'of' 🎬🔥",
        uploader = "Ben & Co",
        mimeType = "video/mp4",
        durationSeconds = 3725
    )

    @Test
    fun escapesSpecialCharacters() {
        assertEquals("a &amp; b &lt;c&gt; &quot;d&quot; &apos;e&apos;", XmlUtils.escape("a & b <c> \"d\" 'e'"))
    }

    @Test
    fun keepsEmojisAndRemovesInvalidCharacters() {
        assertEquals("ok 🎬 ok", XmlUtils.escape("ok 🎬 ok"))
        assertEquals("ab", XmlUtils.escape("a\u0000\u0008b"))
        assertEquals("tab\tnew\nline", XmlUtils.escape("tab\tnew\nline"))
        // unpaired surrogates make XML invalid and are dropped
        assertEquals("ab", XmlUtils.escape("a\uD83Cb"))
        assertEquals("ab", XmlUtils.escape("a\uDFAC" + "b"))
    }

    @Test
    fun buildsValidDidlWithAllFields() {
        val didl = DidlLite.build(media)
        val root = XmlUtils.parse(didl).documentElement
        assertEquals("DIDL-Lite", root.localName)
        val item = XmlUtils.child(root, "item")!!
        assertEquals(media.title, XmlUtils.childText(item, "title"))
        assertEquals("Ben & Co", XmlUtils.childText(item, "creator"))
        assertEquals("Ben & Co", XmlUtils.childText(item, "artist"))
        assertEquals("object.item.videoItem", XmlUtils.childText(item, "class"))

        val res = XmlUtils.child(item, "res")!!
        assertEquals("http-get:*:video/mp4:*", res.getAttribute("protocolInfo"))
        assertEquals("1:02:05.000", res.getAttribute("duration"))
        assertEquals(media.url, res.textContent)
    }

    @Test
    fun liveStreamCarriesItsDlnaFeatures() {
        val format = TsFormat("video/mpeg", "AVC_TS_HD_EU_ISO")
        val live = media.copy(mimeType = format.mimeType, dlnaFeatures = format.dlnaFeatures)
        val res = XmlUtils.child(XmlUtils.child(XmlUtils.parse(DidlLite.build(live)).documentElement, "item")!!, "res")!!
        assertEquals(
            "http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_HD_EU_ISO;" + TsFormat.LIVE_FEATURES,
            res.getAttribute("protocolInfo")
        )
    }

    @Test
    fun rawTextNeverAppearsUnescaped() {
        val didl = DidlLite.build(media)
        assertFalse(didl.contains("Tom & Jerry"))
        assertTrue(didl.contains("Tom &amp; Jerry &lt;Live&gt; &quot;Best&quot; &apos;of&apos; 🎬🔥"))
    }

    @Test
    fun durationIsOmittedWhenUnknown() {
        val didl = DidlLite.build(media.copy(durationSeconds = -1))
        val res = XmlUtils.findFirst(XmlUtils.parse(didl).documentElement, "res")!!
        assertFalse(res.hasAttribute("duration"))
    }

    @Test
    fun urlWithQueryIsEscaped() {
        val didl = DidlLite.build(media.copy(url = "https://h/videoplayback?a=1&b=2"))
        assertTrue(didl.contains("https://h/videoplayback?a=1&amp;b=2"))
        val res = XmlUtils.findFirst(XmlUtils.parse(didl).documentElement, "res")!!
        assertEquals("https://h/videoplayback?a=1&b=2", res.textContent)
    }

    @Test
    fun didlSurvivesBeingPutInsideSoapEnvelope() {
        val didl = DidlLite.build(media)
        val envelope = Soap.buildEnvelope(
            "urn:schemas-upnp-org:service:AVTransport:1",
            "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to media.url, "CurrentURIMetaData" to didl)
        )
        // the metadata is escaped a second time inside the envelope
        assertTrue(envelope.contains("&lt;DIDL-Lite"))
        assertTrue(envelope.contains("Tom &amp;amp; Jerry"))

        val metadata = XmlUtils.findFirst(XmlUtils.parse(envelope).documentElement, "CurrentURIMetaData")!!
        assertEquals(didl, metadata.textContent)
        val title = XmlUtils.findFirst(XmlUtils.parse(metadata.textContent).documentElement, "title")!!
        assertEquals(media.title, title.textContent)
    }

    @Test
    fun formatsAndParsesUpnpTime() {
        assertEquals("0:00:00", UpnpTime.format(0))
        assertEquals("0:03:25", UpnpTime.format(205))
        assertEquals("10:00:01", UpnpTime.format(36001))
        assertEquals("0:00:00", UpnpTime.format(-5))

        assertEquals(205, UpnpTime.parse("0:03:25"))
        assertEquals(205, UpnpTime.parse("00:03:25.500"))
        assertEquals(3725, UpnpTime.parse("1:02:05.1/3"))
        assertEquals(-1, UpnpTime.parse("NOT_IMPLEMENTED"))
        assertEquals(-1, UpnpTime.parse(""))
        assertEquals(-1, UpnpTime.parse(null))
    }

    @Test
    fun announcesSubtitles() {
        val withSubtitles = media.copy(subtitleUrl = "http://192.168.1.20:41234/v/def.srt")
        val item = XmlUtils.child(XmlUtils.parse(DidlLite.build(withSubtitles)).documentElement, "item")!!
        assertEquals(withSubtitles.subtitleUrl, XmlUtils.childText(item, "CaptionInfoEx"))
        assertEquals("srt", XmlUtils.child(item, "CaptionInfoEx")!!.getAttributeNS("http://www.sec.co.kr/", "type"))
        val res = XmlUtils.children(item, "res")
        assertEquals(2, res.size)
        assertEquals(media.url, res[0].textContent)
        assertEquals("http-get:*:text/srt:*", res[1].getAttribute("protocolInfo"))

        assertFalse(DidlLite.build(media).contains("CaptionInfo"))
    }
}
