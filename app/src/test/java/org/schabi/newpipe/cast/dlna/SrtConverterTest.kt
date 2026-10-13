/*
 * SPDX-FileCopyrightText: 2026 NewPipe contributors <https://newpipe.net>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtConverterTest {
    @Test
    fun convertsTtml() {
        val ttml = """<?xml version="1.0" encoding="utf-8" ?>
            <tt xml:lang="en" xmlns="http://www.w3.org/ns/ttml" xmlns:tts="http://www.w3.org/ns/ttml#styling">
            <head><styling><style xml:id="s1" tts:color="white"/></styling></head>
            <body><div>
            <p begin="00:00:01.080" end="00:00:03.320" style="s1">Hello &amp; <span tts:fontStyle="italic">welcome</span></p>
            <p begin="00:00:03.320" end="00:00:05.000">first line<br/>second line</p>
            <p begin="00:01:02.000" dur="1.5s">later</p>
            <p begin="00:01:05.000" end="00:01:06.000"> </p>
            </div></body></tt>"""
        assertEquals(
            "1\n00:00:01,080 --> 00:00:03,320\nHello & welcome\n\n" +
                "2\n00:00:03,320 --> 00:00:05,000\nfirst line\nsecond line\n\n" +
                "3\n00:01:02,000 --> 00:01:03,500\nlater\n\n",
            SrtConverter.fromTtml(ttml)
        )
    }

    @Test
    fun convertsTtmlTicks() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttp="http://www.w3.org/ns/ttml#parameter" ttp:tickRate="10000000">
            <body><div><p begin="15000000t" end="30000000t">ticks</p></div></body></tt>"""
        assertEquals("1\n00:00:01,500 --> 00:00:03,000\nticks\n\n", SrtConverter.fromTtml(ttml))
    }

    @Test
    fun convertsVtt() {
        val vtt = "WEBVTT\nKind: captions\nLanguage: en\n\n" +
            "00:00:00.500 --> 00:00:02.000 align:start position:0%\n<c>Hi</c> <00:00:01.000><c>there</c>\n\n" +
            "note\n01:02.000 --> 01:03.250\nshort &amp; sweet\n"
        assertEquals(
            "1\n00:00:00,500 --> 00:00:02,000\nHi there\n\n" +
                "2\n00:01:02,000 --> 00:01:03,250\nshort & sweet\n\n",
            SrtConverter.fromVtt(vtt)
        )
    }

    @Test
    fun shiftsForVideosStartedInTheMiddle() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\ngone\n\n" +
            "2\n00:00:09,000 --> 00:00:12,000\ncut\n\n" +
            "3\n00:01:00,000 --> 00:01:01,000\nlater\n\n"
        assertEquals(
            "1\n00:00:00,000 --> 00:00:02,000\ncut\n\n2\n00:00:50,000 --> 00:00:51,000\nlater\n\n",
            SrtConverter.shift(srt, 10_000)
        )
    }

    @Test
    fun supportedFormats() {
        assertTrue(SrtConverter.isSupported("ttml"))
        assertTrue(SrtConverter.isSupported("VTT"))
        assertFalse(SrtConverter.isSupported("srv3"))
        assertFalse(SrtConverter.isSupported(null))
        assertNull(SrtConverter.convert("x", "json3"))
        assertEquals("x", SrtConverter.convert("x", "srt"))
    }

    @Test
    fun formatsTime() {
        assertEquals("01:02:03,004", SrtConverter.formatTime(3_723_004))
    }
}
