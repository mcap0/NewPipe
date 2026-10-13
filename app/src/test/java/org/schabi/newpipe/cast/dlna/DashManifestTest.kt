/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class DashManifestTest {
    private val dash = CastDash(
        video = DashTrack(
            url = "https://g/video",
            mimeType = "video/mp4",
            codecs = "avc1.640028",
            bandwidth = 2_500_000,
            initStart = 0,
            initEnd = 740,
            indexStart = 741,
            indexEnd = 2000,
            width = 1920,
            height = 1080
        ),
        audio = DashTrack(
            url = "https://g/audio",
            mimeType = "audio/mp4",
            codecs = "mp4a.40.2",
            bandwidth = 0,
            initStart = 0,
            initEnd = 631,
            indexStart = 632,
            indexEnd = 900
        )
    )

    private fun build(duration: Long = 754) = DashManifest.build(
        dash,
        "http://192.168.1.2:4000/v/abc.mp4?x=1&y=2",
        "http://192.168.1.2:4000/v/def.m4a",
        duration
    )

    @Test
    fun buildsValidStaticManifest() {
        val root = XmlUtils.parse(build()).documentElement
        assertEquals("MPD", root.localName ?: root.tagName)
        assertEquals("static", root.getAttribute("type"))
        assertEquals("PT754S", root.getAttribute("mediaPresentationDuration"))

        val representations = root.getElementsByTagName("Representation")
        assertEquals(2, representations.length)

        val video = representations.item(0) as Element
        assertEquals("avc1.640028", video.getAttribute("codecs"))
        assertEquals("2500000", video.getAttribute("bandwidth"))
        assertEquals("1080", video.getAttribute("height"))
        assertEquals(
            "http://192.168.1.2:4000/v/abc.mp4?x=1&y=2",
            video.getElementsByTagName("BaseURL").item(0).textContent
        )
        val segmentBase = video.getElementsByTagName("SegmentBase").item(0) as Element
        assertEquals("741-2000", segmentBase.getAttribute("indexRange"))
        val init = segmentBase.getElementsByTagName("Initialization").item(0) as Element
        assertEquals("0-740", init.getAttribute("range"))

        val audio = representations.item(1) as Element
        assertEquals("mp4a.40.2", audio.getAttribute("codecs"))
        // a bandwidth is required by the standard, even when unknown
        assertEquals("1", audio.getAttribute("bandwidth"))
        assertFalse(audio.hasAttribute("height"))
    }

    @Test
    fun omitsUnknownDuration() {
        assertFalse(build(duration = 0).contains("mediaPresentationDuration"))
        assertTrue(build().contains("mediaPresentationDuration"))
    }
}
